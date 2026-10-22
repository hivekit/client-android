package io.hivekit.client

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class HttpTest {
    @Test fun nativeRequestsMetadataAndSetCompletion(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"res":"suc","dat":{"lab":"Truck","dat":{"speed":0},"sdt":{"model":"Hilux"},"cat":"pickup"}}]"""))
            server.enqueue(MockResponse().setBody("[]"))
            server.start()
            HivekitClient(HivekitConfig(mode = TransportMode.HTTP, httpUrl = server.url("/native").toString())).use { client ->
                client.authenticate("token")
                val realm = Realm(client, RealmData("fleet", null, JsonObject(emptyMap())))
                val truck = realm.objects.get("truck/12")
                assertEquals("truck/12", truck.id)
                assertEquals("pickup", truck.categoryId)
                val request = server.takeRequest()
                assertEquals("Bearer token", request.getHeader("Authorization"))
                val msg = wireJson.parseToJsonElement(request.body.readUtf8()).jsonArray.single().jsonObject
                assertEquals("rea", msg.text("act"))
                assertEquals("fleet", msg.text("rea"))
                realm.objects.set("truck/12", ObjectPatch(categoryId = PatchValue.Clear))
                assertEquals(JsonNull, wireJson.parseToJsonElement(server.takeRequest().body.readUtf8())
                    .jsonArray.single().jsonObject["cat"])
                assertFailsWith<IllegalArgumentException> { realm.objects.subscribe() }
            }
        }
    }
    @Test fun structuredPlainTextAndEmptyHttpErrorsAreReported(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            HivekitClient(HivekitConfig(mode = TransportMode.HTTP, httpUrl = server.url("/").toString())).use { client ->
                client.authenticate("token")
                server.enqueue(MockResponse().setResponseCode(403).setBody("""{"err":"Forbidden","erc":403}"""))
                val structured = assertFailsWith<HivekitException> { client.realms.list() }
                assertEquals(403, structured.code); assertEquals(403, structured.httpStatus)
                server.enqueue(MockResponse().setResponseCode(503).setBody("Unavailable"))
                assertEquals("Unavailable", assertFailsWith<HivekitException> { client.realms.list() }.message)
                server.enqueue(MockResponse().setResponseCode(500))
                assertEquals("HTTP 500", assertFailsWith<HivekitException> { client.realms.list() }.message)
                server.enqueue(MockResponse().setBody("""[{"res":"err","erc":409,"err":"Exists"}]"""))
                assertEquals(409, assertFailsWith<HivekitException> { client.realms.create("a") }.code)
            }
        }
    }
    @Test fun cancellationAndDeadlinesTerminateRequests(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            HivekitClient(HivekitConfig(mode = TransportMode.HTTP, httpUrl = server.url("/").toString(),
                requestTimeout = 150.milliseconds)).use { client ->
                assertFailsWith<Exception> { client.realms.list() }
            }
        }
    }
}
