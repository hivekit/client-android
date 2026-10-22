package io.hivekit.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LifecycleTest {
    @Test fun cancellingObservationReleasesItsServerSubscription(): Unit = runBlocking {
        TestServer().use { peer -> peer.client().use { client ->
            peer.ready(client)
            val state = Objects(client, "fleet").observe().first { it.initialized }
            assertTrue(state.complete)
            peer.next { it.text("act") == "del" }
        } }
    }
    @Test fun firstEventCanArriveBeforeSubscribeAcknowledgement(): Unit = runBlocking {
        TestServer().use { peer ->
            peer.handler = { request, socket ->
                if (request.text("act") == "sub") {
                    peer.send(socket, buildJsonObject {
                        put("typ", "rea"); put("act", "pub"); put("rea", "fleet"); put("sid", "*")
                        put("dat", buildJsonObject { put("eve", "alert"); put("dat", 42) })
                    })
                    peer.reply(socket, request)
                    true
                } else false
            }
            peer.client().use { client ->
                peer.ready(client)
                val subscription = PubSub(client, "fleet").subscribe("alert")
                assertEquals(JsonPrimitive(42), withTimeout(3.seconds) { subscription.events.first() }.data)
                subscription.cancel()
            }
        }
    }
    @Test fun logStreamsDoNotLoseEarlyEventsOrReplayOldEventsToNewObservers(): Unit = runBlocking {
        TestServer().use { peer ->
            peer.handler = { request, socket ->
                if (request.text("typ") == "sub" && request.text("act") == "cre") {
                    repeat(2) { index -> peer.send(socket, buildJsonObject {
                        put("typ", "sub"); put("id", request.text("id")); put("uty", "dta"); put("seq", index + 1)
                        put("log", buildJsonArray { add(buildJsonObject { put("lab", "event-" + index); put("dat", JsonArray(emptyList())) }) })
                    }) }
                    peer.reply(socket, request)
                    true
                } else false
            }
            peer.client().use { client ->
                peer.ready(client)
                val first = Instructions(client, "fleet").subscribeToLogs()
                val events = withTimeout(3.seconds) { first.updates.take(2).toList() }
                assertEquals(listOf("event-0", "event-1"), events.map { it.data.single().name })
                val second = Instructions(client, "fleet").subscribeToLogs()
                assertNull(withTimeoutOrNull(100.milliseconds) { second.updates.first() })
                first.cancel(); second.cancel()
            }
        }
    }
    @Test fun slowEventConsumerFailsInsteadOfSilentlyDroppingEvents(): Unit = runBlocking {
        TestServer().use { peer -> peer.client(capacity = 1).use { client ->
            peer.ready(client)
            val sub = PubSub(client, "fleet").subscribe("alert")
            repeat(2) { index -> peer.send(msg = buildJsonObject {
                put("typ", "rea"); put("act", "pub"); put("rea", "fleet"); put("sid", "*")
                put("dat", buildJsonObject { put("eve", "alert"); put("dat", index) })
            }) }
            peer.next { it.text("act") == "uns" }
            assertFailsWith<SlowConsumerException> { withTimeout(3.seconds) { sub.events.toList() } }
        } }
    }
    @Test fun failedAuthenticationStopsReconnectingAndCloseRemainsClosed(): Unit = runBlocking {
        TestServer().use { peer ->
            peer.authenticationError = true
            val client = peer.client()
            client.connect()
            assertEquals(401, assertFailsWith<HivekitException> { client.authenticate("bad") }.code)
            withTimeout(3.seconds) { client.connectionStatus.first { it == ConnectionStatus.DISCONNECTED } }
            delay(100)
            assertEquals(1, peer.sockets.size)
            client.close()
            delay(30)
            assertEquals(ConnectionStatus.CLOSED, client.connectionStatus.value)
        }
    }
    @Test fun cancellationBeforeBatchFlushDoesNotTransmitTheRequest(): Unit = runBlocking {
        TestServer().use { peer ->
            val client = HivekitClient(HivekitConfig(
                webSocketUrl = peer.server.url("/ws").toString().replace("http", "ws"),
                outgoingMessageBufferTime = 100.milliseconds, heartbeatInterval = kotlin.time.Duration.ZERO))
            client.use {
                peer.ready(client)
                val work = launch { client.realms.create("cancelled") }
                delay(20)
                work.cancelAndJoin()
                delay(150)
                assertTrue(peer.requests.tryReceive().isFailure)
            }
        }
    }
}
