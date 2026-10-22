package io.hivekit.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RealtimeTest {
    @Test fun correlatesConcurrentResponsesInReverseOrder(): Unit = runBlocking {
        TestServer().use { peer ->
            peer.handler = { _, _ -> true }
            peer.client().use { client ->
                peer.ready(client)
                val first = async { client.realms.list() }
                val second = async { client.system.getServerStats() }
                val requests = listOf(peer.next(), peer.next())
                requests.reversed().forEach {
                    peer.reply(peer.sockets.last(), it, if (it.text("typ") == "sys")
                        buildJsonObject { put("connections", 2) } else buildJsonObject { put("fleet", buildJsonObject { put("lab", "Fleet") }) })
                }
                assertEquals("Fleet", first.await()["fleet"]?.label)
                assertEquals(JsonPrimitive(2), second.await()["connections"])
                assertEquals("test", client.serverInfo.value?.version)
            }
        }
    }
    @Test fun fullDeltaRemovalAndMetadataClearing(): Unit = runBlocking {
        TestServer().use { peer -> peer.client().use { client ->
            peer.ready(client)
            val realm = Realm(client, RealmData("fleet", null, JsonObject(emptyMap())))
            val subscription = realm.objects.subscribe()
            subscription.fresh()
            fun update(type: String, sequence: Int, body: String) = peer.send(msg = buildJsonObject {
                put("typ", "sub"); put("id", subscription.id); put("uty", type); put("seq", sequence)
                put("obj", wireJson.parseToJsonElement(body))
            })
            update("dta", 27, """{"cre":{"truck":{"dat":{"speed":3},"sdt":{"model":"x"},"cat":"pickup"}}}""")
            withTimeout(3.seconds) { subscription.state.first { it.data?.get("truck")?.categoryId == "pickup" } }
            update("dta", 28, """{"upd":{"truck":{"dat":{},"sdt":{},"cat":""}}}""")
            val cleared = withTimeout(3.seconds) { subscription.state.first { it.data?.get("truck")?.categoryId == "" } }
            assertEquals(JsonObject(emptyMap()), cleared.data!!["truck"]!!.staticData)
            update("dta", 29, """{"del":{"truck":null}}""")
            withTimeout(3.seconds) { subscription.state.first { it.data?.isEmpty() == true } }
            subscription.cancel()
            peer.next { it.text("act") == "del" }
        } }
    }
    @Test fun sharingAndUpdatingOneObserverDoNotChangeTheOther(): Unit = runBlocking {
        TestServer().use { peer -> peer.client().use { client ->
            peer.ready(client)
            val objects = Objects(client, "fleet")
            val a = objects.subscribe()
            val b = objects.subscribe()
            assertEquals(a.id, b.id)
            b.fresh()
            val originalId = a.id
            a.update(SubscriptionOptions(where = listOf("charge < 0.5")))
            assertNotEquals(originalId, a.id)
            assertEquals(originalId, b.id)
            a.cancel()
            assertFalse(b.state.value.stale)
            b.cancel()
            val deletes = listOf(peer.next { it.text("act") == "del" }, peer.next { it.text("act") == "del" })
            assertEquals(2, deletes.map { it.text("id") }.toSet().size)
        } }
    }
    @Test fun reconnectRestoresLatestCriteriaAndFreshSnapshot(): Unit = runBlocking {
        TestServer().use { peer ->
            peer.enqueueSocket()
            peer.client().use { client ->
                peer.ready(client)
                val sub = Objects(client, "fleet").subscribe()
                sub.update(SubscriptionOptions(where = listOf("charge < 0.5")))
                val oldId = sub.id
                peer.next { it.text("id") == oldId && it.text("act") == "cre" }
                peer.sockets.last().close(1000, "test disconnect")
                val restored = peer.next {
                    it.text("typ") == "sub" && it.text("act") == "cre" &&
                        it.text("id") != oldId && peer.sockets.size >= 2
                }
                assertEquals(JsonArray(listOf(JsonPrimitive("charge < 0.5"))), restored.obj("dat")["atr"])
                withTimeout(3.seconds) { sub.state.first { !it.stale && sub.id != oldId } }
                assertEquals(ConnectionStatus.AUTHENTICATED, client.connectionStatus.value)
                sub.cancel()
            }
        }
    }
    @Test fun sequenceGapRequestsFreshSnapshotWithoutApplyingIncompleteDelta(): Unit = runBlocking {
        TestServer().use { peer -> peer.client().use { client ->
            peer.ready(client)
            val sub = Objects(client, "fleet").subscribe()
            sub.fresh()
            val id = sub.id
            fun send(seq: Int) = peer.send(msg = buildJsonObject {
                put("typ", "sub"); put("id", id); put("uty", "dta"); put("seq", seq)
                put("obj", buildJsonObject { put("cre", buildJsonObject { put("truck", buildJsonObject { put("lab", seq.toString()) }) }) })
            })
            send(10)
            withTimeout(3.seconds) { sub.state.first { it.data?.containsKey("truck") == true } }
            send(12)
            peer.next { it.text("act") == "cre" && it.text("id") != id }
            withTimeout(3.seconds) { sub.state.first { !it.stale && sub.id != id && it.data?.isEmpty() == true } }
            sub.cancel()
        } }
    }
    @Test fun pubsubSharesSubscriptionsAndCancelsOnlyLastObserver(): Unit = runBlocking {
        TestServer().use { peer -> peer.client().use { client ->
            peer.ready(client)
            val pubsub = PubSub(client, "fleet")
            val a = pubsub.subscribe("alert", "truck/*")
            val b = pubsub.subscribe("alert", "truck/*")
            peer.send(msg = buildJsonObject {
                put("typ", "rea"); put("act", "pub"); put("rea", "fleet"); put("sid", "truck/*")
                put("dat", buildJsonObject { put("eve", "alert"); put("dat", buildJsonObject { put("message", "hi") }) })
            })
            assertEquals("hi", withTimeout(3.seconds) { a.events.first() }.data.jsonObject.text("message"))
            assertEquals("hi", withTimeout(3.seconds) { b.events.first() }.data.jsonObject.text("message"))
            a.cancel()
            b.cancel()
            assertEquals("uns", peer.next { it.text("act") == "uns" }.text("act"))
        } }
    }
    @Test fun requestCancellationAndLostWritesDoNotReplay(): Unit = runBlocking {
        TestServer().use { peer ->
            peer.enqueueSocket()
            peer.handler = { _, _ -> true }
            peer.client().use { client ->
                peer.ready(client)
                val pending = async { runCatching { client.realms.create("new-realm") } }
                peer.next()
                peer.sockets.last().close(1000, "test disconnect")
                val failure = pending.await().exceptionOrNull()
                assertIs<ConnectionLostException>(failure)
                assertTrue(failure.outcomeUnknown)
                withTimeout(3.seconds) { client.connectionStatus.first { it == ConnectionStatus.AUTHENTICATED && peer.sockets.size == 2 } }
                val cancellable = launch { client.realms.list() }
                peer.next(); cancellable.cancelAndJoin()
                assertTrue(cancellable.isCancelled)
                assertTrue(peer.requests.tryReceive().isFailure)
            }
        }
    }
    @Test fun heartbeatReportsLatencyAndShutdownDoesNotReconnect(): Unit = runBlocking {
        TestServer().use { peer -> peer.client(heartbeat = 25.milliseconds).use { client ->
            peer.ready(client)
            withTimeout(3.seconds) { client.ping.first { it != null } }
            client.disconnect()
            assertEquals(ConnectionStatus.DISCONNECTED, client.connectionStatus.value)
            delay(100)
            assertEquals(1, peer.sockets.size)
        } }
    }
}
