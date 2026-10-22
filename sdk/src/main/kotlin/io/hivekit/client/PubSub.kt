package io.hivekit.client

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

internal data class PubSubKey(val realm: String, val event: String, val pattern: String)
internal class PubSubGroup(val key: PubSubKey) {
    var generation = -1L
    val listeners = ConcurrentHashMap<String, PubSubSubscription>()
}
class PubSubSubscription internal constructor(
    private val registry: PubSubRegistry, internal val group: PubSubGroup, capacity: Int,
) {
    internal val id = HivekitClient.getId("pubsub-observer")
    internal val channel = Channel<PubSubEvent>(capacity)
    val events: Flow<PubSubEvent> = channel.receiveAsFlow()
    suspend fun cancel() = registry.remove(this)
}
class PubSub internal constructor(private val client: HivekitClient, private val realm: String) {
    suspend fun publish(event: String, data: JsonElement, idPattern: String = "*") {
        require(event.isNotBlank())
        client.request(pubsubMessage(PubSubKey(realm, event, idPattern), "pub", data))
    }
    suspend fun subscribe(event: String, idPattern: String = "*"): PubSubSubscription =
        client.publications.subscribe(PubSubKey(realm, event, idPattern))
    /** Event streams do not replay messages missed during a disconnection. */
    fun observe(event: String, idPattern: String = "*"): Flow<PubSubEvent> = flow {
        val subscription = subscribe(event, idPattern)
        try { emitAll(subscription.events) }
        finally { withContext(NonCancellable) { subscription.cancel() } }
    }
    fun observePresence(idPattern: String = "*"): Flow<PresenceEvent> = observe("connectionStatusChanged", idPattern).map {
        val status = when (it.data.jsonObject.text("cst")) {
            "con" -> PresenceStatus.CONNECTED
            "dis" -> PresenceStatus.DISCONNECTED
            else -> PresenceStatus.UNKNOWN
        }
        PresenceEvent(it.idPattern, status)
    }
}
internal fun pubsubMessage(key: PubSubKey, action: String, payload: JsonElement? = null) =
    message("rea", action, key.realm, data = buildJsonObject {
        put("eve", key.event); put("idp", key.pattern); payload?.let { put("dat", it) }
    })

internal class PubSubRegistry(private val client: HivekitClient) {
    private val changes = Mutex()
    private val groups = ConcurrentHashMap<PubSubKey, PubSubGroup>()
    suspend fun subscribe(key: PubSubKey): PubSubSubscription {
        client.requireRealtime()
        require(key.event.isNotBlank())
        return changes.withLock {
            val existing = groups[key]
            val group = existing ?: PubSubGroup(key).also { groups[key] = it }
            val subscription = PubSubSubscription(this, group, client.config.eventBufferCapacity)
            group.listeners[subscription.id] = subscription
            if (existing == null) try {
                client.request(pubsubMessage(key, "sub"))
                group.generation = client.generation
            } catch (e: Exception) {
                groups.remove(key, group)
                group.listeners.remove(subscription.id)
                subscription.channel.close()
                withContext(NonCancellable) {
                    if (client.connectionStatus.value == ConnectionStatus.AUTHENTICATED)
                        runCatching { client.request(pubsubMessage(key, "uns")) }
                }
                throw e
            }
            subscription
        }
    }
    suspend fun remove(subscription: PubSubSubscription) = withContext(NonCancellable) {
        changes.withLock {
            val group = subscription.group
            group.listeners.remove(subscription.id)
            subscription.channel.close()
            if (group.listeners.isEmpty() && groups.remove(group.key, group) &&
                client.connectionStatus.value == ConnectionStatus.AUTHENTICATED) {
                runCatching { client.request(pubsubMessage(group.key, "uns")) }.onFailure(client::report)
            }
        }
    }
    suspend fun restore() = changes.withLock {
        for (group in groups.values) {
            if (group.generation == client.generation) continue
            try { client.request(pubsubMessage(group.key, "sub")); group.generation = client.generation }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { client.report(e) }
        }
    }
    fun receive(msg: JsonObject) {
        val data = msg.obj("dat")
        val key = PubSubKey(msg.text("rea") ?: "", data.text("eve") ?: "", msg.text("sid") ?: "*")
        val group = groups[key] ?: return
        val event = PubSubEvent(key.event, key.pattern, data["dat"] ?: JsonNull)
        group.listeners.values.forEach {
            if (!it.channel.trySend(event).isSuccess) {
                it.channel.close(SlowConsumerException())
                client.scope.launch { remove(it) }
            }
        }
    }
    fun close() {
        groups.values.forEach { group -> group.listeners.values.forEach { it.channel.close() } }
        groups.clear()
    }
}
