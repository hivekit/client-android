package io.hivekit.client

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class SubscriptionState<T>(
    val data: T? = null,
    val initialized: Boolean = false,
    /** True once a full snapshot has been received. */
    val complete: Boolean = false,
    val stale: Boolean = true,
    val error: Throwable? = null,
)
data class SubscriptionUpdate<T>(
    val data: T,
    val added: Set<String> = emptySet(),
    val updated: Set<String> = emptySet(),
    val removed: Set<String> = emptySet(),
    val full: Boolean = false,
)


internal data class SubscriptionKey(val realm: String, val options: JsonObject)
internal class RawGroup(val key: SubscriptionKey) {
    @Volatile var id: String = ""
    @Volatile var generation: Long = -1
    var value: JsonElement = JsonNull
    var initialized = false
    var complete = false
    var isStale = true
    var error: Throwable? = null
    var sequence: Int? = null
    var waitingForFull = false
    val recovering = AtomicBoolean(false)
    val listeners = ConcurrentHashMap<String, Subscription<*>>()
}

/**
 * Shared server subscription with independent cancellation and mutable criteria.
 * State is multicast and conflated. Updates has a single consumer and a bounded buffer;
 * a slow consumer's updates stream fails explicitly, while state continues to refresh.
 */
class Subscription<T> internal constructor(
    private val registry: SubscriptionRegistry,
    @Volatile internal var group: RawGroup,
    private val decoder: (JsonElement) -> T,
    private val kind: String,
    capacity: Int,
) {
    internal val listenerId = HivekitClient.getId("observer")
    private val operation = Mutex()
    @Volatile private var cancelled = false
    private val mutableState = MutableStateFlow(SubscriptionState<T>())
    val state: StateFlow<SubscriptionState<T>> = mutableState.asStateFlow()
    private val events = Channel<SubscriptionUpdate<T>>(capacity)
    val updates: Flow<SubscriptionUpdate<T>> = events.receiveAsFlow()
    val id: String get() = group.id

    internal fun accept(source: RawGroup, value: JsonElement, full: Boolean, added: Set<String>, updated: Set<String>, removed: Set<String>) {
        if (cancelled || source !== group) return
        try {
            val decoded = decoder(value)
            mutableState.value = SubscriptionState(decoded, true, source.complete, source.isStale, source.error)
            if (!events.trySend(SubscriptionUpdate(decoded, added, updated, removed, full)).isSuccess) {
                events.close(SlowConsumerException())
            }
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(stale = true, error = ProtocolException("Cannot decode subscription", e))
            events.close(e)
        }
    }

    /** Replaces this observer's criteria; other observers retain their existing criteria. */
    suspend fun update(options: SubscriptionOptions) = operation.withLock {
        check(!cancelled) { "Subscription is cancelled" }
        registry.change(this, options.encode(kind))
    }
    suspend fun cancel() = operation.withLock {
        if (!cancelled) {
            cancelled = true
            registry.remove(this)
            finish()
        }
    }
    internal fun stale(error: Throwable? = null) {
        mutableState.value = mutableState.value.copy(stale = true, error = error)
    }
    internal fun finish() { cancelled = true; events.close() }
    internal fun hydrate() = synchronized(group) {
        if (kind == "log" || kind == "sys") return@synchronized
        if (group.initialized) accept(group, group.value, group.complete, emptySet(), emptySet(), emptySet())
        else mutableState.value = SubscriptionState(stale = group.isStale, error = group.error)
    }
}

internal class SubscriptionRegistry(private val client: HivekitClient) {
    // Protects registration/refcount changes across concurrent subscribe, update and cancel.
    // The socket receiver never waits for this mutex while a request awaits its response.
    private val changes = Mutex()
    private val groups = ConcurrentHashMap<SubscriptionKey, RawGroup>()
    private val byId = ConcurrentHashMap<String, RawGroup>()

    suspend fun <T> subscribe(realm: String, kind: String, options: SubscriptionOptions,
        decoder: (JsonElement) -> T): Subscription<T> {
        client.requireRealtime()
        return changes.withLock {
            val key = SubscriptionKey(realm, options.encode(kind))
            val existing = groups[key]
            val group = existing ?: RawGroup(key).also { groups[key] = it }
            val subscription = Subscription(this, group, decoder, kind, client.config.eventBufferCapacity)
            group.listeners[subscription.listenerId] = subscription
            if (existing != null) subscription.hydrate()
            else try {
                // Attach before registration: logs and initial snapshots may precede the acknowledgement.
                register(group, false)
            } catch (e: Exception) {
                group.listeners.remove(subscription.listenerId)
                groups.remove(key, group)
                byId.remove(group.id, group)
                subscription.finish()
                withContext(NonCancellable) { delete(group) }
                throw e
            }
            subscription
        }
    }

    private suspend fun acquire(key: SubscriptionKey): RawGroup {
        groups[key]?.let { return it }
        val group = RawGroup(key)
        groups[key] = group
        try { register(group, false) }
        catch (e: Exception) {
            groups.remove(key, group)
            byId.remove(group.id, group)
            withContext(NonCancellable) { delete(group) }
            throw e
        }
        return group
    }

    private suspend fun register(group: RawGroup, freshSnapshot: Boolean) {
        client.awaitAuthenticated()
        val oldId = group.id
        val oldGeneration = group.generation
        if (oldId.isNotEmpty()) byId.remove(oldId, group)
        val options = if (freshSnapshot) JsonObject(group.key.options + ("exe" to JsonPrimitive(true))) else group.key.options
        synchronized(group) {
            group.id = HivekitClient.getId("subscription")
            group.generation = client.generation
            group.sequence = null
            group.isStale = true
            group.error = null
            group.waitingForFull = options["exe"]?.jsonPrimitive?.booleanOrNull == true
        }
        byId[group.id] = group
        if (oldId.isNotEmpty() && oldGeneration == client.generation) {
            runCatching { client.request(message("sub", "del", oldId, group.key.realm)) }
        }
        try { client.request(message("sub", "cre", group.id, group.key.realm, options)) }
        catch (e: Exception) { group.generation = -1; throw e }
    }

    suspend fun <T> change(subscription: Subscription<T>, options: JsonObject) = changes.withLock {
        val previous = subscription.group
        val key = SubscriptionKey(previous.key.realm, options)
        if (key == previous.key) return@withLock
        // Create first; a rejected update leaves the original subscription intact.
        val next = acquire(key)
        synchronized(next) {
            subscription.group = next
            next.listeners[subscription.listenerId] = subscription
            subscription.hydrate()
        }
        previous.listeners.remove(subscription.listenerId)
        discardIfUnused(previous)
    }

    suspend fun remove(subscription: Subscription<*>) = withContext(NonCancellable) {
        changes.withLock {
            val group = subscription.group
            group.listeners.remove(subscription.listenerId)
            discardIfUnused(group)
        }
    }
    private suspend fun discardIfUnused(group: RawGroup) {
        if (group.listeners.isNotEmpty()) return
        groups.remove(group.key, group)
        byId.remove(group.id, group)
        delete(group)
    }
    private suspend fun delete(group: RawGroup) {
        if (group.id.isEmpty() || group.generation != client.generation ||
            client.connectionStatus.value != ConnectionStatus.AUTHENTICATED) return
        try { client.request(message("sub", "del", group.id, group.key.realm)) }
        catch (e: Exception) { client.report(e) }
    }

    fun stale() {
        groups.values.forEach { group -> synchronized(group) {
            group.isStale = true
            group.sequence = null
            group.listeners.values.forEach { it.stale() }
        } }
    }
    suspend fun restore() = changes.withLock {
        for (group in groups.values) {
            if (group.generation == client.generation) continue
            try { register(group, true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { group.listeners.values.forEach { it.stale(e) }; client.report(e) }
        }
    }

    fun receive(msg: JsonObject) {
        val group = byId[msg.text("id")] ?: return // Late notification after cancellation.
        synchronized(group) {
            val full = when (msg.text("uty")) {
                "ful" -> true; "dta" -> false
                else -> { recover(group, ProtocolException("Unknown subscription update type")); return }
            }
            val seq = msg["seq"]?.jsonPrimitive?.intOrNull
            val previous = group.sequence
            if (!full && seq != null && seq >= 0 && previous != null) {
                if (seq <= previous) return
                if (seq != previous + 1) { recover(group, SubscriptionGapException()); return }
            }
            if (group.waitingForFull && !full) return
            if (full) group.sequence = null
            if (seq != null && seq >= 0) group.sequence = seq
            val kind = group.key.options.text("typ")
            val added = mutableSetOf<String>()
            val updated = mutableSetOf<String>()
            val removed = mutableSetOf<String>()
            group.value = when (kind) {
                "obj", "tsk" -> {
                    val bucket = msg.obj(kind)
                    val result = if (full) mutableMapOf() else (group.value as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                    bucket.obj("cre").forEach { (id, value) -> result[id] = value; added.add(id) }
                    bucket.obj("upd").forEach { (id, value) -> result[id] = value; updated.add(id) }
                    bucket.obj("del").keys.forEach { result.remove(it); removed.add(it) }
                    JsonObject(result)
                }
                "are", "ins", "sub" -> {
                    val values = msg.obj(kind)
                    if (full) values else JsonObject(((group.value as? JsonObject) ?: JsonObject(emptyMap())) + values)
                }
                "rea" -> msg.obj("rea")
                "log" -> msg["log"] ?: JsonArray(emptyList())
                "sys" -> msg["dat"] ?: JsonObject(emptyMap())
                else -> { recover(group, ProtocolException("Unknown subscription entity type")); return }
            }
            group.initialized = true
            if (full) { group.complete = true; group.waitingForFull = false }
            group.isStale = false
            group.error = null
            group.listeners.values.forEach { it.accept(group, group.value, full, added, updated, removed) }
        }
    }
    private fun recover(group: RawGroup, error: Throwable) {
        group.waitingForFull = true
        group.isStale = true
        group.error = error
        group.listeners.values.forEach { it.stale(error) }
        client.report(error)
        if (!group.recovering.compareAndSet(false, true)) return
        client.scope.launch {
            try { changes.withLock { if (groups[group.key] === group) register(group, true) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { group.listeners.values.forEach { it.stale(e) }; client.report(e) }
            finally { group.recovering.set(false) }
        }
    }
    fun close() {
        groups.values.forEach { group -> group.listeners.values.forEach { it.stale(HivekitException("Client is closed")); it.finish() } }
        groups.clear(); byId.clear()
    }
}
