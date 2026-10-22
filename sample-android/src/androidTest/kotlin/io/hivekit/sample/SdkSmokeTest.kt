package io.hivekit.sample

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hivekit.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class SdkSmokeTest {
    @Test fun createsClientAndModelsOnDevice() {
        HivekitClient().use { client ->
            assertEquals(ConnectionStatus.DISCONNECTED, client.connectionStatus.value)
            assertTrue(HivekitClient.getId("device").startsWith("device-"))
            val obj = HivekitObject(data = buildJsonObject { put("battery", 0) })
            assertEquals(JsonPrimitive(0), obj.effectiveValue("battery"))
        }
    }

    /** Enabled by tools/Test-Server.ps1 -AndroidDevice against its test-only server. */
    @Test fun realWebSocketRoundTripOnDevice(): Unit = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("hivekitWs")
        assumeNotNull(url)
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val now = System.currentTimeMillis() / 1000
        val payload = buildJsonObject {
            put("sub", "android-device-tests"); put("tnd", "android-device-tests")
            put("iat", now); put("exp", now + 600)
            put("per", buildJsonObject {
                put("$" + "hkt_realm", "CRUDP")
                put("*", buildJsonObject { put("*", "CRUDP") })
            })
        }
        val unsigned = encode("""{"alg":"HS256","typ":"JWT"}""".toByteArray()) + "." + encode(payload.toString().toByteArray())
        val signer = Mac.getInstance("HmacSHA256")
        signer.init(SecretKeySpec("android-integration-only".toByteArray(), "HmacSHA256"))
        val token = unsigned + "." + encode(signer.doFinal(unsigned.toByteArray()))
        HivekitClient(HivekitConfig(webSocketUrl = url)).use { client ->
            client.connect()
            client.authenticate(token)
            val id = HivekitClient.getId("api23")
            client.realms.create(id)
            try {
                val realm = client.realms.get(id)
                val subscription = realm.objects.subscribe()
                try {
                    realm.objects.create("truck", ObjectPatch(location = HivekitLocation(52.0, 13.0),
                        data = buildJsonObject { put("charge", 0.4) }))
                    assertEquals(JsonPrimitive(0.4), realm.objects.get("truck").data["charge"])
                    withTimeout(10000) { subscription.state.first { it.data?.containsKey("truck") == true } }
                } finally { subscription.cancel() }
            } finally { client.realms.delete(id) }
        }
    }
}
