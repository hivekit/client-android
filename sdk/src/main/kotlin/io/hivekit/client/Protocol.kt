package io.hivekit.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal fun message(type: String, action: String, id: String? = null, realm: String? = null, data: JsonElement? = null) =
    buildJsonObject {
        put("typ", type); put("act", action)
        id?.let { put("id", it) }; realm?.let { put("rea", it) }; data?.let { put("dat", it) }
    }

internal fun Shape.type(): String = when (this) {
    is Shape.Circle -> "cir"; is Shape.Rectangle -> "rec"; is Shape.Polygon -> "pol"
}
internal fun Shape.encode(): JsonObject = buildJsonObject {
    when (val shape = this@encode) {
        is Shape.Circle -> { put("cx", shape.centerX); put("cy", shape.centerY); put("r", shape.radius) }
        is Shape.Rectangle -> { put("x1", shape.west); put("y1", shape.south); put("x2", shape.east); put("y2", shape.north) }
        is Shape.Polygon -> put("points", JsonArray(shape.points.map {
            buildJsonObject { put("x", it.x); put("y", it.y) }
        }))
    }
}
internal fun decodeShape(type: String?, value: JsonElement?): Shape? {
    val shape = value as? JsonObject ?: return null
    fun number(name: String) = shape.getValue(name).jsonPrimitive.double
    return when (type) {
        "cir" -> Shape.Circle(number("cx"), number("cy"), number("r"))
        "rec" -> Shape.Rectangle(number("x1"), number("y1"), number("x2"), number("y2"))
        "pol" -> Shape.Polygon(shape.getValue("points").jsonArray.map {
            Point(it.jsonObject.getValue("x").jsonPrimitive.double, it.jsonObject.getValue("y").jsonPrimitive.double)
        })
        else -> null
    }
}
internal fun ListOptions.encode(): JsonObject = buildJsonObject {
    if (fields.isNotEmpty()) put("fie", JsonArray(fields.map(::JsonPrimitive)))
    if (where.isNotEmpty()) put("atr", JsonArray(where.map(::JsonPrimitive)))
    shape?.let { put("sha", it.encode()) }
}
internal fun SubscriptionOptions.encode(kind: String): JsonObject = buildJsonObject {
    put("typ", kind); put("sty", "rea"); put("exe", executeImmediately)
    // Empty arrays matter when replacing criteria (e.g. clearing an attribute filter).
    put("atr", JsonArray(where.map(::JsonPrimitive)))
    put("act", JsonArray(actions.map { JsonPrimitive(when(it) {
        "create" -> "cre"; "update" -> "upd"; "delete" -> "del"; else -> it
    }) }))
    shape?.let { put("sty", it.type()); put("sha", it.encode()) }
    areaId?.let { put("sty", "are"); put("sid", it) }
    objectId?.let { put("sty", "obj"); put("sid", it) }
    target?.let { put("sty", "tar"); put("sta", buildJsonObject { put("id", it.id); put("r", it.radius) }) }
}
internal fun ObjectPatch.encode() = buildJsonObject {
    label?.let { put("lab", it) }; location?.let { put("loc", wireJson.encodeToJsonElement(it)) }
    data?.let { put("dat", it) }; staticData?.let { put("sdt", it) }
    when (val category = categoryId) {
        PatchValue.Unchanged -> Unit
        PatchValue.Clear -> put("cat", JsonNull)
        is PatchValue.Value -> put("cat", category.value)
    }
    taskIds?.let { put("tds", JsonArray(it.map(::JsonPrimitive))) }
}
internal fun AreaPatch.encode() = buildJsonObject {
    label?.let { put("lab", it) }; shape?.let { put("sty", it.type()); put("sha", it.encode()) }
    data?.let { put("dat", it) }; staticData?.let { put("sdt", it) }
}
internal fun InstructionPatch.encode() = buildJsonObject {
    instructionString?.let { put("ins", it) }; label?.let { put("lab", it) }; data?.let { put("dat", it) }
}
internal fun TaskPatch.encode() = buildJsonObject {
    label?.let { put("lab", it) }; description?.let { put("dsc", it) }; status?.let { put("sts", it) }
    location?.let { put("loc", wireJson.encodeToJsonElement(it)) }; targetId?.let { put("tid", it) }
    steps?.let { put("stp", wireJson.encodeToJsonElement(it)) }; data?.let { put("dat", it) }
}
internal fun JsonObject.checkResponse(httpStatus: Int? = null): JsonObject {
    if (text("res") == "err" || this["err"] != null) throw HivekitException(
        text("err") ?: "Hivekit request failed", this["erc"]?.jsonPrimitive?.intOrNull, httpStatus
    )
    return this
}
internal fun decodeArea(id: String, raw: JsonObject) = Area(
    raw.text("id") ?: id, raw.text("lab"), decodeShape(raw.text("sty"), raw["sha"]), raw.obj("dat"), raw.obj("sdt"), raw.text("sty")
)
internal fun decodeRealm(id: String, raw: JsonObject) = RealmData(raw.text("id") ?: id, raw.text("lab"), raw.obj("dat"))
