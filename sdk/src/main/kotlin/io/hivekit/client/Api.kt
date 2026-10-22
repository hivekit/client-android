package io.hivekit.client

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Server operations share the transport and error contract; list results retain their IDs. */
open class EntityApi<T> internal constructor(
    internal val client: HivekitClient,
    internal val realmId: String,
    internal val kind: String,
    private val decoder: (String, JsonObject) -> T,
) {
    suspend fun get(id: String): T = decoder(id, client.request(message(kind, "rea", id, realmId)).obj("dat"))
    suspend fun list(options: ListOptions = ListOptions()): Map<String, T> =
        decodeMap(client.request(message(kind, "lis", realm = realmId, data = options.encode()))["dat"])
    suspend fun delete(id: String) { client.request(message(kind, "del", id, realmId)) }
    suspend fun subscribe(options: SubscriptionOptions = SubscriptionOptions()): Subscription<Map<String, T>> =
        client.subscriptions.subscribe(realmId, kind, options, ::decodeMap)
    fun observe(options: SubscriptionOptions = SubscriptionOptions()): Flow<SubscriptionState<Map<String, T>>> = flow {
        val subscription = subscribe(options)
        try { emitAll(subscription.state) }
        finally { withContext(NonCancellable) { subscription.cancel() } }
    }
    private fun decodeMap(element: JsonElement?): Map<String, T> =
        (element as? JsonObject)?.mapValues { (id, raw) -> decoder(id, raw.jsonObject) } ?: emptyMap()
    internal suspend fun write(action: String, id: String, patch: JsonObject) {
        require(id.isNotBlank()) { "Entity ID must not be blank" }
        client.request(JsonObject(message(kind, action, id, realmId) + patch))
    }
}

class Objects internal constructor(client: HivekitClient, realmId: String) :
    EntityApi<HivekitObject>(client, realmId, "obj", { id, raw -> wireJson.decodeFromJsonElement(raw.withId(id)) }) {
    suspend fun create(id: String, patch: ObjectPatch = ObjectPatch()) = write("cre", id, patch.encode())
    suspend fun update(id: String, patch: ObjectPatch) = write("upd", id, patch.encode())
    /** Enqueues an upsert. Its completion does not confirm an individual server write. */
    suspend fun set(id: String, patch: ObjectPatch) {
        require(id.isNotBlank())
        client.sendUnacknowledged(JsonObject(message(kind, "set", id, realmId) + patch.encode()))
    }
}
class Areas internal constructor(client: HivekitClient, realmId: String) :
    EntityApi<Area>(client, realmId, "are", ::decodeArea) {
    suspend fun create(id: String, patch: AreaPatch) {
        require(patch.shape != null) { "Creating an area requires a shape" }
        write("cre", id, patch.encode())
    }
    suspend fun update(id: String, patch: AreaPatch) = write("upd", id, patch.encode())
}
class Instructions internal constructor(client: HivekitClient, realmId: String) :
    EntityApi<Instruction>(client, realmId, "ins", { id, raw -> wireJson.decodeFromJsonElement(raw.withId(id)) }) {
    suspend fun create(id: String, patch: InstructionPatch) {
        require(!patch.instructionString.isNullOrBlank()) { "An instruction requires HiveScript" }
        write("cre", id, patch.encode())
    }
    suspend fun update(id: String, patch: InstructionPatch) = write("upd", id, patch.encode())
    suspend fun subscribeToLogs(options: SubscriptionOptions = SubscriptionOptions(executeImmediately = false)):
        Subscription<List<LogEvent>> = client.subscriptions.subscribe(realmId, "log", options) {
            (it as? JsonArray)?.map { entry -> wireJson.decodeFromJsonElement<LogEvent>(entry) } ?: emptyList()
        }
    fun observeLogs(): Flow<LogEvent> = flow {
        val subscription = subscribeToLogs()
        try { subscription.updates.collect { update -> update.data.forEach { emit(it) } } }
        finally { withContext(NonCancellable) { subscription.cancel() } }
    }
}
class Tasks internal constructor(client: HivekitClient, realmId: String) :
    EntityApi<HivekitTask>(client, realmId, "tsk", { id, raw -> wireJson.decodeFromJsonElement(raw.withId(id)) }) {
    suspend fun create(id: String, patch: TaskPatch) {
        require(patch.location != null || !patch.targetId.isNullOrBlank()) { "A task requires a location or targetId" }
        write("cre", id, patch.encode())
    }
    suspend fun update(id: String, patch: TaskPatch) = write("upd", id, patch.encode())
}

class Realms internal constructor(private val client: HivekitClient) {
    suspend fun create(id: String, label: String? = null, data: JsonObject? = null) {
        require(id.isNotBlank())
        client.request(buildJsonObject {
            put("typ", "rea"); put("act", "cre"); put("id", id)
            label?.let { put("lab", it) }; data?.let { put("dat", it) }
        })
    }
    suspend fun get(id: String): Realm = Realm(client, decodeRealm(id, client.request(message("rea", "rea", id)).obj("dat")))
    suspend fun list(): Map<String, RealmSummary> =
        client.request(message("rea", "lis")).obj("dat").mapValues { (id, value) -> value.jsonObject.let { raw -> RealmSummary(id, raw.text("lab"), raw["objects"]?.jsonPrimitive?.intOrNull ?: 0, raw["areas"]?.jsonPrimitive?.intOrNull ?: 0, raw["tasks"]?.jsonPrimitive?.intOrNull ?: 0, raw["instructions"]?.jsonPrimitive?.intOrNull ?: 0, raw["subscriptions"]?.jsonPrimitive?.intOrNull ?: 0) } }
    suspend fun delete(id: String) { client.request(message("rea", "del", id)) }
    suspend fun subscribe(): Subscription<RealmEvent> =
        client.subscriptions.subscribe("sys", "sys", SubscriptionOptions(executeImmediately = false)) {
            val raw = it.jsonObject
            RealmEvent(raw.text("id") ?: "", when (raw.text("act")) {
                "cre" -> "create"; "upd" -> "update"; "del" -> "delete"; else -> raw.text("act") ?: ""
            })
        }
    fun observe(): Flow<RealmEvent> = flow {
        val subscription = subscribe()
        try { subscription.updates.collect { emit(it.data) } }
        finally { withContext(NonCancellable) { subscription.cancel() } }
    }
}

class Realm internal constructor(private val client: HivekitClient, val snapshot: RealmData) {
    val id: String get() = snapshot.id
    val objects = Objects(client, id)
    val areas = Areas(client, id)
    val instructions = Instructions(client, id)
    val tasks = Tasks(client, id)
    val pubsub = PubSub(client, id)
    val history = History(client, id)
    suspend fun getData(): JsonObject = client.realms.get(id).snapshot.data
    suspend fun getData(key: String): JsonElement? = getData()[key]
    /** Patches only the supplied attribute to avoid overwriting concurrent metadata changes. */
    suspend fun setData(key: String, value: JsonElement) {
        client.request(message("rea", "upd", id, data = buildJsonObject { put(key, value) }))
    }
    suspend fun setLabel(label: String) {
        client.request(JsonObject(message("rea", "upd", id) + ("lab" to JsonPrimitive(label))))
    }
    suspend fun subscribe(): Subscription<RealmData> =
        client.subscriptions.subscribe(id, "rea", SubscriptionOptions()) { decodeRealm(id, it.jsonObject) }
    fun observe(): Flow<SubscriptionState<RealmData>> = flow {
        val subscription = subscribe()
        try { emitAll(subscription.state) }
        finally { withContext(NonCancellable) { subscription.cancel() } }
    }
    suspend fun search(value: String, options: SearchOptions = SearchOptions()): List<SearchResult> {
        val data = buildJsonObject {
            put("val", value)
            if (options.fields.isNotEmpty()) put("fie", JsonArray(options.fields.map(::JsonPrimitive)))
            options.maxObjectResults?.let { require(it >= 0); put("maxObjectResults", it) }
            options.maxAreaResults?.let { require(it >= 0); put("maxAreaResults", it) }
        }
        return (client.request(message("rea", "sea", realm = id, data = data))["dat"] as? JsonArray)?.map {
            val raw = it.jsonObject
            SearchResult(raw.text("id") ?: "", raw.text("typ"), raw.text("lab"),
                when(raw.text("fie")) { "lab" -> "label"; "dat" -> "data"; else -> raw.text("fie") }, raw.text("sty"), raw["val"], raw["sta"]?.jsonPrimitive?.intOrNull, raw["end"]?.jsonPrimitive?.intOrNull)
        } ?: emptyList()
    }
}
class History internal constructor(private val client: HivekitClient, private val realmId: String) {
    /** Dynamic data is historical; staticData, label and categoryId are current metadata. */
    suspend fun get(id: String, range: HistoryRange): List<HistoryEntry> =
        (client.request(message("his", "rea", id, realmId, buildJsonObject {
            put("sta", range.start.toString()); put("end", range.end.toString())
        }))["dat"] as? JsonArray)?.map { wireJson.decodeFromJsonElement<HistoryEntry>(it) } ?: emptyList()
}
class SystemApi internal constructor(private val client: HivekitClient) {
    suspend fun getServerStats(): JsonObject = client.request(message("sys", "sta")).obj("dat")
    suspend fun authenticateAdmin(password: String) = client.authenticateAdmin(password)
    val httpRoot: String? get() = client.config.httpRoot
}
