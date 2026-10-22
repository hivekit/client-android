package io.hivekit.client

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/** A controllable protocol peer; tests assert wire behaviour rather than internal implementation. */
internal class TestServer : Closeable {
    val server = MockWebServer()
    val requests = Channel<JsonObject>(Channel.UNLIMITED)
    val sockets = CopyOnWriteArrayList<WebSocket>()
    @Volatile var handler: ((JsonObject, WebSocket) -> Boolean)? = null
    @Volatile var authenticationError = false
    init { enqueueSocket(); server.start() }
    fun enqueueSocket() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.startsWith("Bearer ")) {
                    webSocket.send(if (authenticationError)
                        """[{"typ":"sys","act":"aut","res":"err","erc":401,"err":"Invalid token"}]"""
                    else """[{"typ":"sys","act":"aut","res":"suc","dat":{"version":"test","buildDate":"today"}}]""")
                    return
                }
                wireJson.parseToJsonElement(text).jsonArray.forEach { element ->
                    val msg = element.jsonObject
                    requests.trySend(msg)
                    if (handler?.invoke(msg, webSocket) == true) return@forEach
                    if (msg.text("act") != "set") reply(webSocket, msg)
                    if (msg.text("typ") == "sub" && msg.text("act") == "cre" &&
                        msg.obj("dat")["exe"]?.jsonPrimitive?.booleanOrNull == true) {
                        val kind = msg.obj("dat").text("typ") ?: "obj"
                        send(webSocket, buildJsonObject {
                            put("typ", "sub"); put("id", msg.text("id")); put("uty", "ful"); put("seq", -1)
                            put(kind, JsonObject(emptyMap()))
                        })
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
    }
    fun client(timeout: Duration = 3.seconds, heartbeat: Duration = Duration.ZERO, capacity: Int = 256) =
        HivekitClient(HivekitConfig(webSocketUrl = server.url("/ws").toString().replace("http", "ws"),
            requestTimeout = timeout, heartbeatInterval = heartbeat, eventBufferCapacity = capacity,
            reconnect = ReconnectPolicy(initialDelay = 25.milliseconds, maxDelay = 25.milliseconds, maxAttempts = 3, jitter = 0.0)))
    suspend fun ready(client: HivekitClient) { client.connect(); client.authenticate("test-token") }
    fun send(socket: WebSocket = sockets.last(), msg: JsonObject) { socket.send(JsonArray(listOf(msg)).toString()) }
    fun reply(socket: WebSocket, request: JsonObject, data: JsonElement = JsonObject(emptyMap())) =
        send(socket, buildJsonObject { put("cid", request.text("cid")); put("res", "suc"); put("dat", data) })
    suspend fun next(predicate: (JsonObject) -> Boolean = { true }): JsonObject = withTimeout(5.seconds) {
        while (true) {
            val msg = requests.receive()
            if (predicate(msg)) return@withTimeout msg
        }
        error("unreachable")
    }
    override fun close() { sockets.forEach { runCatching { it.close(1000, "test complete") } }; requests.close(); server.close() }
}
internal suspend fun <T> Subscription<T>.fresh(): SubscriptionState<T> = withTimeout(5.seconds) {
    state.first { it.initialized && !it.stale }
}
