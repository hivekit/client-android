package io.hivekit.client

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class TransportMode { WEBSOCKET, HTTP }
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATING, AUTHENTICATED, RECONNECTING, CLOSED }
data class ServerInfo(val version: String?, val buildDate: String?)
data class ReconnectPolicy(
    val enabled: Boolean = true,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val maxAttempts: Int = Int.MAX_VALUE,
    val jitter: Double = 0.2,
) {
    init { require(initialDelay.isPositive() && maxDelay >= initialDelay && maxAttempts >= 0 && jitter in 0.0..1.0) }
    internal fun delayMillis(attempt: Int): Long =
        (minOf(maxDelay.inWholeMilliseconds.toDouble(), initialDelay.inWholeMilliseconds * 2.0.pow(minOf(attempt, 30))) *
            (1 + Random.nextDouble(-jitter.coerceAtLeast(0.000001), jitter.coerceAtLeast(0.000001)))).toLong().coerceAtLeast(1)
}
data class HivekitConfig(
    val webSocketUrl: String? = null,
    val httpUrl: String? = null,
    val httpRoot: String? = null,
    val adminDashboardPath: String = "/admin/",
    val mode: TransportMode = TransportMode.WEBSOCKET,
    val requestTimeout: Duration = 15.seconds,
    val heartbeatInterval: Duration = 60.seconds,
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
    val outgoingMessageBufferTime: Duration = Duration.ZERO,
    val maxPendingRequests: Int = 1024,
    val eventBufferCapacity: Int = 256,
) {
    init {
        require(requestTimeout.isPositive() && requestTimeout.isFinite())
        require(heartbeatInterval >= Duration.ZERO && outgoingMessageBufferTime >= Duration.ZERO)
        require(maxPendingRequests > 0 && eventBufferCapacity > 0)
    }
}

/**
 * Android API 23+ / JVM client. Own it in an application or ViewModel and close it on disposal.
 * Calls are main-safe. Cancelling a call does not undo a write already sent to Hivekit.
 */
class HivekitClient(
    val config: HivekitConfig = HivekitConfig(),
    private val tokenProvider: (suspend () -> String)? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val http = HttpClient(OkHttp) {
        install(WebSockets)
        install(HttpCookies)
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
    }
    private val status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = status.asStateFlow()
    private val info = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = info.asStateFlow()
    private val latency = MutableStateFlow<Duration?>(null)
    val ping: StateFlow<Duration?> = latency.asStateFlow()
    private val errorEvents = MutableSharedFlow<Throwable>(extraBufferCapacity = config.eventBufferCapacity)
    /** Diagnostic events; individual requests also throw their own errors. */
    val errors: SharedFlow<Throwable> = errorEvents.asSharedFlow()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val lifecycle = Mutex()
    private val authMutex = Mutex()
    private val sendMutex = Mutex()
    private val generationCounter = AtomicLong()
    internal val generation: Long get() = generationCounter.get()
    @Volatile private var socket: DefaultClientWebSocketSession? = null
    @Volatile private var connectionJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var authResponse: CompletableDeferred<Unit>? = null
    @Volatile private var token: String? = null
    @Volatile private var endpoint: String? = config.webSocketUrl
    @Volatile private var manuallyDisconnected = true
    @Volatile private var closed = false
    private val outgoing = Channel<Pair<Long, JsonObject>>(config.maxPendingRequests)
    internal val subscriptions = SubscriptionRegistry(this)
    internal val publications = PubSubRegistry(this)
    val realms = Realms(this)
    val system = SystemApi(this)
    val version: String = SDK_VERSION
    val url: String? get() = if (config.mode == TransportMode.HTTP) config.httpUrl else endpoint

    init {
        scope.launch {
            for (first in outgoing) {
                val batch = mutableListOf(first)
                if (config.outgoingMessageBufferTime.isPositive()) delay(config.outgoingMessageBufferTime)
                while (batch.size < config.maxPendingRequests) {
                    batch.add(outgoing.tryReceive().getOrNull() ?: break)
                }
                try {
                    sendMutex.withLock {
                        val active = socket ?: throw ConnectionLostException()
                        // Never deliver a prior connection's queued writes on a new connection.
                        val messages = batch.filter { it.first == generation }.map { it.second }.filter {
                            it.text("cid") == null || pending.containsKey(it.text("cid"))
                        }
                        if (messages.isNotEmpty()) active.send(Frame.Text(JsonArray(messages).toString()))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failPending(e); report(e); socket?.cancel() }
            }
        }
    }

    /** Opens the socket. When a token provider is configured, also waits for authentication. */
    suspend fun connect(url: String = requireNotNull(config.webSocketUrl) { "Supply webSocketUrl" }) {
        checkOpen()
        require(config.mode == TransportMode.WEBSOCKET) { "HTTP mode does not use connect()" }
        val opened = lifecycle.withLock {
            check(connectionJob?.isActive != true) { "Client already connecting or connected" }
            endpoint = url
            manuallyDisconnected = false
            CompletableDeferred<Unit>().also { ready -> connectionJob = scope.launch { connectionLoop(url, ready) } }
        }
        try { withTimeout(config.requestTimeout) { opened.await() } }
        catch (e: Exception) { withContext(NonCancellable) { disconnect() }; throw e }
    }

    private suspend fun connectionLoop(url: String, opened: CompletableDeferred<Unit>) {
        var attempts = 0
        var hasOpened = false
        while (currentCoroutineContext().isActive && !manuallyDisconnected) {
            status.value = if (hasOpened) ConnectionStatus.RECONNECTING else ConnectionStatus.CONNECTING
            var current: DefaultClientWebSocketSession? = null
            try {
                current = http.webSocketSession(urlString = url)
                sendMutex.withLock {
                    socket = current
                    generationCounter.incrementAndGet()
                }
                status.value = ConnectionStatus.CONNECTED
                authResponse = CompletableDeferred()
                hasOpened = true
                val connectionToken = tokenProvider?.invoke() ?: token
                if (connectionToken == null) opened.complete(Unit)
                coroutineScope {
                    val active = current
                    val receiving = launch {
                        for (frame in active.incoming) {
                            if (frame is Frame.Text) receive(frame.readText())
                        }
                    }
                    if (connectionToken != null) {
                        authenticate(connectionToken)
                        opened.complete(Unit)
                    }
                    receiving.join()
                }
                throw ConnectionLostException()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                report(ConnectionLostException())
            } catch (e: Exception) {
                report(e)
                if (e is HivekitException && e.code in listOf(401, 403)) {
                    opened.completeExceptionally(e)
                    break
                }
            } finally {
                heartbeatJob?.cancel()
                status.value = when { closed -> ConnectionStatus.CLOSED; manuallyDisconnected -> ConnectionStatus.DISCONNECTED; else -> ConnectionStatus.RECONNECTING }
                sendMutex.withLock { socket = null }
                current?.cancel()
                authResponse?.completeExceptionally(ConnectionLostException())
                failPending(ConnectionLostException(outcomeUnknown = true))
                subscriptions.stale()
            }
            if (manuallyDisconnected || !config.reconnect.enabled || attempts >= config.reconnect.maxAttempts) break
            status.value = ConnectionStatus.RECONNECTING
            delay(config.reconnect.delayMillis(attempts++))
        }
        if (!closed) status.value = ConnectionStatus.DISCONNECTED
        opened.completeExceptionally(ConnectionLostException())
    }

    /** JWT or admin token. HTTP tokens are validated by the next request. */
    suspend fun authenticate(token: String) {
        checkOpen()
        require(token.isNotBlank()) { "Token must not be blank" }
        if (config.mode == TransportMode.HTTP) {
            this.token = token
            status.value = ConnectionStatus.AUTHENTICATED
            return
        }
        authMutex.withLock {
            val active = socket ?: throw IllegalStateException("Connect before authenticating")
            check(status.value != ConnectionStatus.AUTHENTICATED) { "Already authenticated; reconnect to change identity" }
            val response = authResponse ?: throw ConnectionLostException()
            this.token = token
            status.value = ConnectionStatus.AUTHENTICATING
            active.send(Frame.Text("Bearer " + token))
            try { withTimeout(config.requestTimeout) { response.await() } }
            catch (e: Exception) { active.cancel(); throw e }
        }
    }

    suspend fun disconnect() {
        lifecycle.withLock {
            manuallyDisconnected = true
            connectionJob?.cancelAndJoin()
            connectionJob = null
            socket = null
            failPending(ConnectionLostException(outcomeUnknown = true))
            subscriptions.stale()
            if (!closed) status.value = ConnectionStatus.DISCONNECTED
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        manuallyDisconnected = true
        status.value = ConnectionStatus.CLOSED
        failPending(HivekitException("Client is closed"))
        subscriptions.close()
        publications.close()
        outgoing.close()
        scope.cancel()
        http.close()
    }

    internal fun checkOpen() = check(!closed) { "Client is closed" }
    internal fun requireRealtime() {
        checkOpen()
        require(config.mode == TransportMode.WEBSOCKET) { "Realtime observations require WebSocket mode" }
    }
    internal fun report(error: Throwable) { errorEvents.tryEmit(error) }
    internal suspend fun awaitAuthenticated() {
        checkOpen()
        if (config.mode == TransportMode.HTTP) return
        check(connectionJob?.isActive == true) { "Connect before sending requests" }
        connectionStatus.first {
            it == ConnectionStatus.AUTHENTICATED || it == ConnectionStatus.CLOSED || it == ConnectionStatus.DISCONNECTED
        }.also { if (it != ConnectionStatus.AUTHENTICATED) throw ConnectionLostException() }
    }
    internal suspend fun request(message: JsonObject): JsonObject = withTimeout(config.requestTimeout) {
        checkOpen()
        if (config.mode == TransportMode.HTTP) return@withTimeout post(message)
        awaitAuthenticated()
        val id = getId("request")
        val response = CompletableDeferred<JsonObject>()
        synchronized(pending) {
            if (status.value != ConnectionStatus.AUTHENTICATED) throw ConnectionLostException()
            check(pending.size < config.maxPendingRequests) { "Too many pending Hivekit requests" }
            pending[id] = response
        }
        try {
            if (!outgoing.trySend(generation to JsonObject(message + ("cid" to JsonPrimitive(id)))).isSuccess) {
                throw HivekitException("Outgoing queue is full")
            }
            response.await().checkResponse()
        } finally { pending.remove(id) }
    }

    /** Local enqueue only on WebSocket; there is no per-object server acknowledgement. */
    internal suspend fun sendUnacknowledged(message: JsonObject) = withTimeout(config.requestTimeout) {
        checkOpen()
        if (config.mode == TransportMode.HTTP) { post(message); return@withTimeout }
        awaitAuthenticated()
        if (!outgoing.trySend(generation to message).isSuccess) throw HivekitException("Outgoing queue is full")
    }

    private suspend fun post(message: JsonObject): JsonObject {
        val credential = tokenProvider?.invoke() ?: token
        val response = http.post(requireNotNull(config.httpUrl) { "Supply httpUrl for HTTP mode" }) {
            contentType(ContentType.Application.Json)
            credential?.let { bearerAuth(it) }
            setBody(JsonArray(listOf(message)).toString())
        }
        val body = response.bodyAsText()
        val parsed = runCatching { wireJson.parseToJsonElement(body) }.getOrNull()
        if (!response.status.isSuccess()) {
            val error = when (parsed) {
                is JsonObject -> parsed
                is JsonArray -> parsed.firstOrNull() as? JsonObject
                else -> null
            }
            throw HivekitException(error?.text("err") ?: (parsed as? JsonPrimitive)?.contentOrNull
                ?: body.ifBlank { "HTTP " + response.status.value },
                error?.get("erc")?.jsonPrimitive?.intOrNull, response.status.value)
        }
        val messages = parsed as? JsonArray ?: throw ProtocolException("Expected an array of HTTP responses")
        val reply = messages.filterIsInstance<JsonObject>().firstOrNull { it.text("res") == "err" }
            ?: messages.firstOrNull() as? JsonObject
        if (reply != null) return reply.checkResponse(response.status.value)
        if (message.text("act") == "set") return buildJsonObject { put("res", "suc") }
        throw ProtocolException("Missing HTTP response")
    }

    private fun failPending(error: Throwable) = synchronized(pending) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private suspend fun receive(text: String) {
        val messages = try { wireJson.parseToJsonElement(text) as? JsonArray }
            catch (e: Exception) { throw ProtocolException("Invalid JSON from Hivekit", e) }
            ?: throw ProtocolException("Expected an array of WebSocket messages")
        for (element in messages) {
            val msg = element as? JsonObject ?: throw ProtocolException("Expected a message object")
            when {
                msg.text("typ") == "sys" && msg.text("act") == "aut" -> {
                    if (msg.text("res") == "err") {
                        val failure = HivekitException(msg.text("err") ?: "Authentication failed",
                            msg["erc"]?.jsonPrimitive?.intOrNull ?: 401)
                        authResponse?.completeExceptionally(failure)
                        throw failure
                    }
                    info.value = ServerInfo(msg.obj("dat").text("version"), msg.obj("dat").text("buildDate"))
                    status.value = ConnectionStatus.AUTHENTICATED
                    authResponse?.complete(Unit)
                    heartbeatJob?.cancel()
                    heartbeatJob = scope.launch { heartbeat() }
                    scope.launch {
                        try { subscriptions.restore(); publications.restore() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { report(e) }
                    }
                }
                msg.text("cid") != null -> pending.remove(msg.text("cid"))?.complete(msg)
                msg.text("res") == "err" -> report(HivekitException(msg.text("err") ?: "Server error",
                    msg["erc"]?.jsonPrimitive?.intOrNull))
                msg.text("typ") == "sub" -> subscriptions.receive(msg)
                msg.text("typ") == "rea" && msg.text("act") == "pub" -> publications.receive(msg)
                else -> report(ProtocolException("Unrecognized server message"))
            }
        }
    }

    private suspend fun heartbeat() {
        if (config.heartbeatInterval == Duration.ZERO) return
        while (currentCoroutineContext().isActive) {
            delay(config.heartbeatInterval)
            val started = System.nanoTime()
            try {
                request(message("sys", "hbt"))
                latency.value = kotlin.time.Duration.parse((System.nanoTime() - started).toString() + "ns")
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                report(e); socket?.cancel(); return
            } catch (e: Exception) { report(e); socket?.cancel(); return }
        }
    }

    internal suspend fun authenticateAdmin(password: String) {
        checkOpen()
        val root = requireNotNull(config.httpRoot) { "Admin authentication requires an explicit httpRoot" }
        val response = http.post(root.trimEnd('/') + "/" + config.adminDashboardPath.trim('/') + "/api/authenticate-admin") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("username", "admin"); put("password", password) }.toString())
        }
        if (!response.status.isSuccess()) throw HivekitException("Admin authentication failed", httpStatus = response.status.value)
    }

    companion object {
        private val random = SecureRandom()
        private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        @JvmStatic fun getId(prefix: String): String {
            val bytes = ByteArray(21).also(random::nextBytes)
            return prefix + "-" + bytes.map { alphabet[it.toInt() and 63] }.joinToString("")
        }
    }
}
