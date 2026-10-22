package io.hivekit.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Run only against the isolated in-memory server started by tools/Test-Server.ps1. */
@EnabledIfEnvironmentVariable(named = "HIVEKIT_TEST_HTTP_URL", matches = ".+")
class ServerIntegrationTest {
    private fun token(tenant: String = "android-sdk-tests", providers: JsonObject? = null): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(text: String) = encoder.encodeToString(text.toByteArray(Charsets.UTF_8))
        val body = buildJsonObject {
            put("sub", "android-sdk-tests"); put("tnd", tenant)
            put("iat", System.currentTimeMillis() / 1000)
            put("exp", System.currentTimeMillis() / 1000 + 600)
            put("per", buildJsonObject {
                put("$" + "hkt_realm", "CRUDP")
                put("*", buildJsonObject { put("*", "CRUDP") })
            })
            providers?.let { put("dpv", it) }
        }
        val unsigned = encode("""{"alg":"HS256","typ":"JWT"}""") + "." + encode(body.toString())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec((System.getenv("HIVEKIT_TEST_SECRET") ?: "android-integration-only").toByteArray(), "HmacSHA256"))
        return unsigned + "." + encoder.encodeToString(mac.doFinal(unsigned.toByteArray(Charsets.UTF_8)))
    }
    private suspend fun client(mode: TransportMode, tenant: String = "android-sdk-tests"): HivekitClient {
        val client = HivekitClient(HivekitConfig(mode = mode,
            httpUrl = System.getenv("HIVEKIT_TEST_HTTP_URL"),
            webSocketUrl = System.getenv("HIVEKIT_TEST_WS_URL"), heartbeatInterval = 1.seconds))
        try {
            if (mode == TransportMode.WEBSOCKET) client.connect()
            client.authenticate(token(tenant))
            return client
        } catch (e: Exception) { client.close(); throw e }
    }
    @Test fun entityParityInBothTransports(): Unit = runBlocking {
        for (mode in TransportMode.entries) client(mode).use { client ->
            val id = HivekitClient.getId("android")
            client.realms.create(id, "Android integration", buildJsonObject { put("keep", 1) })
            try {
                val realm = client.realms.get(id)
                realm.setData("added", JsonPrimitive(2))
                assertEquals(2, realm.getData().size)
                realm.setData("added", JsonNull)
                assertNull(realm.getData("added"))
                realm.setLabel("Updated")
                assertEquals("Updated", client.realms.get(id).snapshot.label)

                realm.objects.create("truck/12", ObjectPatch(label = "Truck", location = HivekitLocation(52.0, 13.0),
                    data = buildJsonObject { put("charge", 0.4) },
                    staticData = buildJsonObject { put("model", "Hilux") }, categoryId = PatchValue.Value("pickup")))
                assertEquals("pickup", realm.objects.get("truck/12").categoryId)
                realm.objects.update("truck/12", ObjectPatch(data = buildJsonObject { put("charge", JsonNull) },
                    categoryId = PatchValue.Clear))
                val cleared = realm.objects.get("truck/12")
                assertNull(cleared.data["charge"])
                assertTrue(cleared.categoryId.isNullOrEmpty())
                val listed = realm.objects.list(ListOptions(fields = listOf("staticData", "categoryId")))
                assertEquals(JsonPrimitive("Hilux"), listed["truck/12"]?.staticData?.get("model"))
                realm.objects.set("truck/13", ObjectPatch(label = "Second truck"))
                withTimeout(5.seconds) {
                    while (!realm.objects.list().containsKey("truck/13")) delay(25)
                }

                realm.areas.create("bay", AreaPatch(shape = Shape.Circle(13.0, 52.0, 100.0),
                    staticData = buildJsonObject { put("zone", "workshop") }))
                realm.areas.update("bay", AreaPatch(label = "Service bay"))
                assertEquals(JsonPrimitive("workshop"), realm.areas.get("bay").staticData["zone"])
                assertTrue(realm.areas.list().containsKey("bay"))

                realm.tasks.create("inspect", TaskPatch(targetId = "truck/12", description = "Inspect truck",
                    steps = listOf(TaskStep(label = "Inspect tyres"))))
                realm.tasks.update("inspect", TaskPatch(status = TaskStatus.IN_PROGRESS))
                assertEquals(TaskStatus.IN_PROGRESS, realm.tasks.get("inspect").status)
                assertTrue(realm.tasks.list().containsKey("inspect"))

                realm.instructions.create("rule", InstructionPatch("when object(charge<0.2) then notify(\"reviewed\")"))
                assertTrue(realm.instructions.get("rule").instructionString.contains("reviewed"))
                realm.instructions.update("rule", InstructionPatch(label = "Review trucks"))
                assertTrue(realm.instructions.list().containsKey("rule"))
                assertTrue(realm.search("truck").isNotEmpty())
                assertTrue(client.system.getServerStats().isNotEmpty())
                realm.instructions.delete("rule")
                realm.tasks.delete("inspect")
                realm.areas.delete("bay")
                realm.objects.delete("truck/12")
                realm.objects.delete("truck/13")
            } finally { client.realms.delete(id) }
        }
    }
    @Test fun realtimeFilteringPubsubAndTenantIsolation(): Unit = runBlocking {
        client(TransportMode.WEBSOCKET).use { client ->
            val id = HivekitClient.getId("android-live")
            client.realms.create(id)
            try {
                val realm = client.realms.get(id)
                val sub = realm.objects.subscribe(SubscriptionOptions(where = listOf("charge<0.5")))
                sub.fresh()
                realm.objects.create("truck", ObjectPatch(data = buildJsonObject { put("charge", 0.4) }))
                withTimeout(5.seconds) { sub.state.first { it.data?.containsKey("truck") == true } }
                realm.objects.update("truck", ObjectPatch(data = buildJsonObject { put("charge", 0.8) }))
                withTimeout(5.seconds) { sub.state.first { it.data?.isEmpty() == true } }
                sub.update(SubscriptionOptions())
                withTimeout(5.seconds) { sub.state.first { it.data?.containsKey("truck") == true } }
                val event = realm.pubsub.subscribe("alert", "truck/*")
                realm.pubsub.publish("alert", buildJsonObject { put("value", 12) }, "truck/12")
                assertEquals(JsonPrimitive(12), withTimeout(5.seconds) { event.events.first() }.data.jsonObject["value"])
                event.cancel()
                sub.cancel()
                client(TransportMode.HTTP, "other-android-tenant").use { other ->
                    assertFalse(other.realms.list().containsKey(id))
                    assertFailsWith<HivekitException> { other.realms.get(id) }
                }
            } finally { client.realms.delete(id) }
        }
    }

    @Test fun dataProviderPresenceIsObservable(): Unit = runBlocking {
        client(TransportMode.WEBSOCKET).use { reader ->
            val id = HivekitClient.getId("android-presence")
            reader.realms.create(id)
            try {
                val realm = reader.realms.get(id)
                realm.objects.create("truck/12")
                val events = realm.pubsub.subscribe("connectionStatusChanged")
                val provider = HivekitClient(HivekitConfig(webSocketUrl = System.getenv("HIVEKIT_TEST_WS_URL")))
                try {
                    provider.connect()
                    provider.authenticate(token(providers = buildJsonObject {
                        put(id, JsonArray(listOf(JsonPrimitive("truck/*"))))
                    }))
                    withTimeout(5000) { events.events.first { it.data.jsonObject.text("cst") == "con" } }
                    assertEquals(PresenceStatus.CONNECTED, realm.objects.get("truck/12").connectionStatus)
                    provider.disconnect()
                    withTimeout(5000) { events.events.first { it.data.jsonObject.text("cst") == "dis" } }
                    assertEquals(PresenceStatus.DISCONNECTED, realm.objects.get("truck/12").connectionStatus)
                } finally { provider.close(); events.cancel() }
            } finally { reader.realms.delete(id) }
        }
    }

}
