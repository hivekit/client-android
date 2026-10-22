package io.hivekit.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class AdminCookieTest {
    @Test fun adminCookieAuthenticatesSubsequentWebSocket(): Unit = runBlocking {
        val acceptedSocket = CompletableDeferred<WebSocket>()
        val socketClosed = CompletableDeferred<Unit>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "hkt=session-token; Path=/"))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    acceptedSocket.complete(webSocket)
                    webSocket.send("""[{"typ":"sys","act":"aut","res":"suc","dat":{"version":"test"}}]""")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socketClosed.complete(Unit)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socketClosed.completeExceptionally(t)
                }
            }))
            server.start()
            HivekitClient(HivekitConfig(
                httpRoot = server.url("/").toString(),
                webSocketUrl = server.url("/ws").toString().replace("http", "ws"),
                reconnect = ReconnectPolicy(enabled = false),
            )).use { client ->
                client.system.authenticateAdmin("test-password")
                assertEquals("/admin/api/authenticate-admin", server.takeRequest().path)
                client.connect()
                withTimeout(3000) { client.connectionStatus.first { it == ConnectionStatus.AUTHENTICATED } }
                assertEquals("hkt=session-token", server.takeRequest().getHeader("Cookie"))
                // Finish the fixture's close handshake before MockWebServer shuts down its queues.
                assertTrue(acceptedSocket.await().close(1000, "test complete"))
                withTimeout(3000) { socketClosed.await() }
            }
        }
    }
}
