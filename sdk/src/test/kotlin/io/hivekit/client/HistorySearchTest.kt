package io.hivekit.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.time.Instant
import kotlin.test.*

class HistorySearchTest {
    @Test fun historyUsesUtcRangesAndKeepsCurrentMetadataSeparate(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"res":"suc","dat":[{"tim":"2026-10-22T00:00:00.123456789Z","loc":{"lat":52,"lon":13},"dat":{"speed":5},"sdt":{"model":"Hilux"},"lab":"Current label","cat":"current-category"}]}]"""))
            server.start()
            HivekitClient(HivekitConfig(mode = TransportMode.HTTP, httpUrl = server.url("/").toString())).use { client ->
                val entry = History(client, "fleet").get("truck", HistoryRange(
                    Instant.parse("2026-10-22T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z"))).single()
                assertEquals(JsonPrimitive(5), entry.data["speed"])
                assertEquals(JsonPrimitive("Hilux"), entry.staticData["model"])
                assertEquals("current-category", entry.categoryId)
                assertEquals("2026-10-22T00:00:00.123456789Z", entry.time)
                val request = wireJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonArray.single().jsonObject
                assertEquals("2026-10-22T00:00:00Z", request.obj("dat").text("sta"))
                assertEquals("2026-10-23T00:00:00Z", request.obj("dat").text("end"))
            }
        }
    }
    @Test fun searchExposesFullFieldNamesAndOffsets(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"res":"suc","dat":[{"id":"truck","typ":"obj","lab":"Truck","fie":"dat","sty":"model","val":"Hilux","sta":1,"end":4}]}]"""))
            server.start()
            HivekitClient(HivekitConfig(mode = TransportMode.HTTP, httpUrl = server.url("/").toString())).use { client ->
                val result = Realm(client, RealmData("fleet", null, JsonObject(emptyMap()))).search("ilu",
                    SearchOptions(fields = listOf("data"), maxObjectResults = 20)).single()
                assertEquals("data", result.field)
                assertEquals("model", result.dataProperty)
                assertEquals(1, result.startIndex)
                assertEquals(4, result.endIndex)
            }
        }
    }
}
