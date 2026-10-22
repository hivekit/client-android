package io.hivekit.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

/** Unknown JSON fields are ignored, while application attributes remain arbitrary JSON. */
internal val wireJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

/** Omitted, supplied and explicitly cleared fields have distinct wire meanings. */
sealed interface PatchValue<out T> {
    data object Unchanged : PatchValue<Nothing>
    data class Value<T>(val value: T) : PatchValue<T>
    data object Clear : PatchValue<Nothing>
}

@Serializable
data class HivekitLocation(
    @SerialName("lat") val latitude: Double,
    @SerialName("lon") val longitude: Double,
    @SerialName("gcs") val coordinateSystem: String? = null,
    @SerialName("acc") val accuracy: Double? = null,
    @SerialName("spe") val speed: Double? = null,
    @SerialName("hea") val heading: Double? = null,
    @SerialName("alt") val altitude: Double? = null,
    @SerialName("alc") val altitudeAccuracy: Double? = null,
    /** RFC 3339 timestamp. Kept as a string to preserve server precision and support API 23. */
    @SerialName("tim") val time: String? = null,
)

sealed interface Shape {
    data class Circle(val centerX: Double, val centerY: Double, val radius: Double) : Shape {
        init { require(radius > 0 && radius.isFinite()) { "Radius must be positive and finite" } }
    }
    data class Rectangle(val west: Double, val south: Double, val east: Double, val north: Double) : Shape
    data class Polygon(val points: List<Point>) : Shape {
        init { require(points.size >= 3) { "A polygon needs at least three points" } }
    }
}
data class Point(val x: Double, val y: Double)

@Serializable
data class HivekitObject(
    val id: String = "",
    @SerialName("lab") val label: String? = null,
    @SerialName("loc") val location: HivekitLocation? = null,
    @SerialName("dat") val data: JsonObject = JsonObject(emptyMap()),
    @SerialName("sdt") val staticData: JsonObject = JsonObject(emptyMap()),
    @SerialName("cat") val categoryId: String? = null,
    @SerialName("tds") val taskIds: List<String> = emptyList(),
    @SerialName("cst") val connectionStatus: PresenceStatus? = null,
    val lastExternalUpdate: String? = null,
) {
    fun effectiveValue(key: String): JsonElement? = data[key] ?: staticData[key]
}

data class Area(
    val id: String,
    val label: String?,
    val shape: Shape?,
    val data: JsonObject,
    val staticData: JsonObject,
    val shapeType: String? = null,
)

@Serializable
data class Instruction(
    val id: String = "",
    @SerialName("lab") val label: String? = null,
    @SerialName("ins") val instructionString: String = "",
    @SerialName("dat") val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TaskStep(
    @SerialName("lab") val label: String? = null,
    @SerialName("dsc") val description: String? = null,
    @SerialName("sts") val status: String? = null,
    @SerialName("dat") val data: JsonObject = JsonObject(emptyMap()),
)

/** String constants allow newer server statuses to be read without decoding failures. */
object TaskStatus {
    const val NOT_STARTED = "$" + "hkt_not_started"
    const val IN_PROGRESS = "$" + "hkt_in_progress"
    const val COMPLETED = "$" + "hkt_completed"
    const val FAILED = "$" + "hkt_failed"
    const val CANCELED = "$" + "hkt_canceled"
    const val PAUSED = "$" + "hkt_paused"
    const val BLOCKED = "$" + "hkt_blocked"
}

@Serializable
data class HivekitTask(
    val id: String = "",
    @SerialName("lab") val label: String? = null,
    @SerialName("dsc") val description: String? = null,
    @SerialName("sts") val status: String? = null,
    @SerialName("loc") val location: HivekitLocation? = null,
    @SerialName("tid") val targetId: String? = null,
    @SerialName("stp") val steps: List<TaskStep> = emptyList(),
    @SerialName("dat") val data: JsonObject = JsonObject(emptyMap()),
    val lastExternalUpdate: String? = null,
)

data class RealmData(val id: String, val label: String?, val data: JsonObject)
data class RealmEvent(val realmId: String, val action: String)

@Serializable
data class HistoryEntry(
    @SerialName("tim") val time: String? = null,
    @SerialName("loc") val location: HivekitLocation? = null,
    @SerialName("dat") val data: JsonObject = JsonObject(emptyMap()),
    @SerialName("sdt") val staticData: JsonObject = JsonObject(emptyMap()),
    @SerialName("lab") val label: String? = null,
    @SerialName("cat") val categoryId: String? = null,
    @SerialName("typ") val type: String? = null,
)

data class SearchResult(
    val id: String, val type: String?, val label: String?,
    val field: String?, val dataProperty: String?, val value: JsonElement?,
    val startIndex: Int? = null, val endIndex: Int? = null,
)
data class SearchOptions(
    val fields: List<String> = emptyList(),
    val maxObjectResults: Int? = null,
    val maxAreaResults: Int? = null,
)
data class ListOptions(
    /** Full field names, e.g. data, staticData, categoryId, or an application attribute. */
    val fields: List<String> = emptyList(),
    val where: List<String> = emptyList(),
    val shape: Shape? = null,
)
data class SubscriptionTarget(val id: String, val radius: Double) {
    init { require(id.isNotBlank() && radius > 0 && radius.isFinite()) }
}
data class SubscriptionOptions(
    val where: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val shape: Shape? = null,
    val areaId: String? = null,
    val target: SubscriptionTarget? = null,
    /** Task subscriptions can be scoped to tasks assigned to a particular object. */
    val objectId: String? = null,
    val executeImmediately: Boolean = true,
) {
    init {
        require(listOf(shape, areaId, target, objectId).count { it != null } <= 1) {
            "Only one subscription scope may be supplied"
        }
    }
}
typealias ObjectQuery = SubscriptionOptions

data class ObjectPatch(
    val label: String? = null,
    val location: HivekitLocation? = null,
    val data: JsonObject? = null,
    val staticData: JsonObject? = null,
    val categoryId: PatchValue<String> = PatchValue.Unchanged,
    val taskIds: List<String>? = null,
)
data class AreaPatch(
    val label: String? = null, val shape: Shape? = null,
    val data: JsonObject? = null, val staticData: JsonObject? = null,
)
data class InstructionPatch(
    val instructionString: String? = null, val label: String? = null, val data: JsonObject? = null,
)
data class TaskPatch(
    val label: String? = null, val description: String? = null,
    val status: String? = null, val location: HivekitLocation? = null,
    val targetId: String? = null, val steps: List<TaskStep>? = null, val data: JsonObject? = null,
) {
    init { require(location == null || targetId == null) { "Specify location or targetId, not both" } }
}

@Serializable
data class LogEvent(
    @SerialName("lab") val name: String = "",
    @SerialName("lvl") val level: String = "",
    @SerialName("id") val component: String = "",
    @SerialName("typ") val componentType: String = "",
    @SerialName("dat") val data: JsonArray = JsonArray(emptyList()),
)
data class PubSubEvent(val name: String, val idPattern: String, val data: JsonElement)

open class HivekitException(
    message: String,
    val code: Int? = null,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
class ConnectionLostException(val outcomeUnknown: Boolean = false) :
    HivekitException(if (outcomeUnknown) "Connection lost; the request outcome is unknown" else "Connection lost")
class ProtocolException(message: String, cause: Throwable? = null) : HivekitException(message, cause = cause)
class SlowConsumerException : HivekitException("Event buffer exhausted; collect events faster or increase eventBufferCapacity")
class SubscriptionGapException : HivekitException("Subscription sequence gap; requesting a fresh snapshot")

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.withId(id: String): JsonObject = if (text("id").isNullOrEmpty()) {
    JsonObject(this + ("id" to JsonPrimitive(id)))
} else this

/** History metadata describes the current entity, not its historical metadata. */
data class HistoryRange(val start: Instant, val end: Instant) {
    init { require(!end.isBefore(start)) { "History end must not precede start" } }
}


data class RealmSummary(
    val id: String, val label: String?,
    val objects: Int = 0, val areas: Int = 0, val tasks: Int = 0,
    val instructions: Int = 0, val subscriptions: Int = 0,
)
