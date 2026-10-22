package io.hivekit.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class AdminCookieTest {
    @Test fun adminCookieAuthenticatesSubsequentWebSocket(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "hkt=session-token; Path=/"))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""[{"typ":"sys","act":"aut","res":"suc","dat":{"version":"test"}}]""")
                }
            }))
            server.start()
            HivekitClient(HivekitConfig(
                httpRoot = server.url("/").toString(),
                webSocketUrl = server.url("/ws").toString().replace("http", "ws"),
            )).use { client ->
                client.system.authenticateAdmin("test-password")
                assertEquals("/admin/api/authenticate-admin", server.takeRequest().path)
                client.connect()
                withTimeout(3000) { client.connectionStatus.first { it == ConnectionStatus.AUTHENTICATED } }
                assertEquals("hkt=session-token", server.takeRequest().getHeader("Cookie"))
            }
        }
    }
}
