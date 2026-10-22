package io.hivekit.client

import kotlinx.serialization.json.*
import kotlin.test.*
import java.time.Instant

class ProtocolTest {
    @Test fun categoryOmissionClearAndValueAreDistinct() {
        assertFalse(ObjectPatch().encode().containsKey("cat"))
        assertEquals(JsonNull, ObjectPatch(categoryId = PatchValue.Clear).encode()["cat"])
        assertEquals(JsonPrimitive("truck"), ObjectPatch(categoryId = PatchValue.Value("truck")).encode()["cat"])
        val data = buildJsonObject { put("battery", JsonNull); put("zero", 0); put("enabled", false) }
        val patch = ObjectPatch(data = data, staticData = JsonObject(emptyMap()), taskIds = emptyList()).encode()
        assertEquals(data, patch["dat"])
        assertEquals(JsonObject(emptyMap()), patch["sdt"])
        assertEquals(JsonArray(emptyList()), patch["tds"])
    }
    @Test fun shapesAndCoordinatesRoundTrip() {
        val shapes = listOf(Shape.Circle(13.4, 52.5, 100.0),
            Shape.Rectangle(1.0, 2.0, 3.0, 4.0),
            Shape.Polygon(listOf(Point(1.0, 2.0), Point(3.0, 4.0), Point(5.0, 2.0))))
        shapes.forEach { assertEquals(it, decodeShape(it.type(), it.encode())) }
        val location = HivekitLocation(52.0, 13.0, "ll", time = "2026-10-22T00:00:00.123456789Z")
        val raw = wireJson.encodeToJsonElement(location).jsonObject
        assertEquals(JsonPrimitive(52.0), raw["lat"])
        assertEquals(location, wireJson.decodeFromJsonElement(raw))
    }
    @Test fun attributesAreNotRenamedAndUnknownServerFieldsAreTolerated() {
        val raw = wireJson.parseToJsonElement("""{"dat":{"typ":"sensor","nested":{"cat":false}},"sdt":{"typ":"static"},"cat":"vehicle","future":123}""")
        val result = wireJson.decodeFromJsonElement<HivekitObject>(raw)
        assertEquals(JsonPrimitive("sensor"), result.effectiveValue("typ"))
        assertEquals("vehicle", result.categoryId)
        assertEquals(JsonPrimitive(false), result.data.obj("nested")["cat"])
    }
    @Test fun fullListSelectorsAndMovingTargetRemainUnambiguous() {
        assertEquals(listOf("data", "staticData", "categoryId"),
            ListOptions(fields = listOf("data", "staticData", "categoryId")).encode()["fie"]!!.jsonArray.map { it.jsonPrimitive.content })
        val target = SubscriptionOptions(target = SubscriptionTarget("truck/12", 500.0)).encode("obj")
        assertEquals("tar", target.text("sty"))
        assertEquals("truck/12", target.obj("sta").text("id"))
        assertFailsWith<IllegalArgumentException> {
            SubscriptionOptions(areaId = "bay", shape = Shape.Circle(0.0, 0.0, 1.0))
        }
        assertFailsWith<IllegalArgumentException> { TaskPatch(location = HivekitLocation(0.0, 0.0), targetId = "truck") }
    }
    @Test fun historyDatesAndNewTaskStatusesArePreserved() {
        val start = Instant.parse("2026-10-22T00:00:00Z")
        assertFailsWith<IllegalArgumentException> { HistoryRange(start, start.minusSeconds(1)) }
        assertEquals("future-status", wireJson.decodeFromJsonElement<HivekitTask>(
            buildJsonObject { put("sts", "future-status") }).status)
    }
    @Test fun secureIdsHaveExpectedAlphabetAndUniqueness() {
        val ids = (1..1000).map { HivekitClient.getId("truck") }
        assertEquals(1000, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("truck-[A-Za-z0-9_-]{21}")) })
    }
}
