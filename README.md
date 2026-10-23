# Hivekit client for Android and the JVM

A Kotlin-first client for Hivekit's native HTTP and WebSocket APIs, covering the functionality of JavaScript client 1.12.0.

- Suspending CRUD, search, history and system operations.
- Typed entities, locations, shapes and patch options; arbitrary JSON attributes.
- Flow observations and explicit, independently cancellable subscriptions.
- Shared subscriptions, reconnect/authentication, fresh snapshots, presence, pub/sub and instruction logs.
- Android API 23+, with a JVM library that does not require an Android SDK to build.

This repository contains a single library in `sdk` and an optional Android example in `sample-android`. It does not collect device location or run a background Android service.

## Install

Use Kotlin 2.2 or later and JDK 17 for your Android build. The SDK supports
Android API 23 and later.

Add JitPack to your application's `settings.gradle.kts`, alongside Google and
Maven Central. Restrict it to the Hivekit group:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeGroup("com.github.hivekit") }
        }
    }
}
```

Then add the SDK to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.hivekit:client-android:v0.1.1")
}
```

Sync Gradle in Android Studio. Public installation needs no JitPack account,
access token or manual download. Pin a release version; the `v` prefix is part
of the JitPack version.

Add the internet permission to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Enable core library desugaring for Java time APIs below API 26, including
`HistoryRange`:

```kotlin
android {
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

Kotlin is the primary API. The same dependency works in JVM projects; this
initial release does not add a separate Java callback facade.

## Build from source

Use JDK 17. The wrapper pins Gradle and verifies its distribution checksum.

```shell
# Windows
.\gradlew.bat :sdk:check :sdk:assemble

# macOS / Linux
bash gradlew :sdk:check :sdk:assemble
```

For local development, `:sdk:publishAllPublicationsToBuildRepository` writes a
Maven repository under `sdk/build/repository`. The default development coordinate
is `com.github.hivekit:client-android:0.1.1-SNAPSHOT`.

See [the publishing guide](docs/publishing.md) for JitPack releases and the
independent Android app that verifies installation from the public repository.

## Connect and use entities

```kotlin
import io.hivekit.client.*
import kotlinx.serialization.json.*

val client = HivekitClient(HivekitConfig(webSocketUrl = "wss://api.hivekit.io/v1/ws"))
client.connect()
client.authenticate(accessToken)

val realm = client.realms.get("fleet")
realm.objects.create(
    "truck/12",
    ObjectPatch(
        label = "Delivery truck",
        location = HivekitLocation(latitude = 52.52, longitude = 13.40),
        data = buildJsonObject { put("charge", 0.6) },
        staticData = buildJsonObject { put("model", "Hilux") },
        categoryId = PatchValue.Value("pickup")
    )
)

val truck = realm.objects.get("truck/12")
val vehicles = realm.objects.list(
    ListOptions(fields = listOf("data", "staticData", "categoryId"), where = listOf("charge<0.8"))
)
realm.objects.update("truck/12", ObjectPatch(categoryId = PatchValue.Clear))
```

For refreshed credentials, configure `tokenProvider = { tokenStore.currentToken() }` on the client. Then `connect()` also authenticates, and the provider is called again on reconnect. Do not call `authenticate()` a second time on an authenticated connection. Create a new client when changing identity so cached state and cookies remain scoped to one account.

`connectionStatus`, `serverInfo`, and `ping` are read-only StateFlows. Individual operations throw `HivekitException` with the server code and, where available, HTTP status. Cancellation propagates normally. `errors` carries bounded, best-effort diagnostic events.

The client owns its coroutine scope and network resources. Close it when its application/ViewModel owner is disposed; `disconnect()` permits later reconnection, while `close()` is terminal.

## Observe data with Android lifecycle ownership

```kotlin
// From a Fragment, with a client owned by a ViewModel or the application:
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        realm.objects.observe(
            ObjectQuery(where = listOf("charge<0.5"))
        ).collect { state ->
            // state.data is the current map, indexed by ID.
            // Render loading until initialized; indicate stale data during reconnect.
            render(state.data.orEmpty(), state.stale, state.error)
        }
    }
}
```

Each collection acquires a subscription; cancellation releases it. Equivalent criteria share one server subscription. State is immutable by convention and conflated for UI collection, after every incoming delta has been applied.

For a moving geofence or explicit ownership:

```kotlin
val subscription = realm.objects.subscribe(
    SubscriptionOptions(shape = Shape.Circle(13.4, 52.5, 500.0))
)
subscription.update(
    SubscriptionOptions(target = SubscriptionTarget("truck/12", radius = 500.0))
)
subscription.cancel()
```

Updating one observer's criteria does not change other observers. Updates replace the criteria, so an empty `where` clears filters. Initial snapshots are enabled by default; `executeImmediately = false` opts out. A state built only from deltas has `complete = false` until it receives a full snapshot.

`subscription.state` is multicast. `subscription.updates` is a single-consumer stream with the full/delta flag and added/updated/removed ID sets. Its bounded buffer fails with `SlowConsumerException` if exhausted; state still updates. Logs and pub/sub event streams also use bounded buffers and do not silently drop events.

## Other APIs

- `client.realms`: create, get, list (including member counts), delete, and observe realm lifecycle events.
- `realm`: get/set metadata, set label, observe metadata, and search.
- `realm.areas`: CRUD/list/observe with circle, rectangle and polygon shapes. Metadata-only updates can omit shape; creation requires one.
- `realm.instructions`: CRUD/list/observe, `subscribeToLogs()` and `observeLogs()`. HiveScript is sent to the server for validation/execution.
- `realm.tasks`: CRUD/list/observe. Creation requires a location or target object; steps and `TaskStatus` constants are supported.
- `realm.history.get(id, HistoryRange(startInstant, endInstant))`.
- `realm.pubsub.publish(name, json, idPattern)`, `subscribe()`, `observe()`, and typed `observePresence()`.
- `client.system.getServerStats()`, `authenticateAdmin(password)`, and explicit `httpRoot`.
- `HivekitClient.getId(prefix)`: secure IDs.

Read results may be summaries: list field selection controls which object attributes are returned, and area lists can identify a shape without including its geometry. Search results include field names, property names and highlight offsets. Unknown task status strings are preserved; unknown presence values decode as `UNKNOWN`.

## HTTP mode

```kotlin
val client = HivekitClient(
    HivekitConfig(mode = TransportMode.HTTP, httpUrl = "https://example.com/api/v1/")
)
client.authenticate(accessToken) // HTTP validates the token on the next request.
val realms = client.realms.list()
client.close()
```

HTTP uses the same native message protocol, not the server's separate REST API. It supports request/response operations and publishing. Realtime subscriptions require WebSocket mode and fail immediately in HTTP mode.

Admin authentication uses an in-memory cookie jar shared by HTTP and WebSocket requests. Set `httpRoot` and, if needed, `adminDashboardPath` explicitly. After admin authentication, a cookie-authenticated WebSocket can become authenticated without a token frame.

## Data and delivery contracts

- `data` and `staticData` remain separate. Updates patch supplied keys; a `JsonNull` attribute removes that key. Dynamic attributes take precedence in `effectiveValue()`.
- Category omission leaves it unchanged; `PatchValue.Clear` sends explicit null. Empty task ID lists are preserved.
- `objects.set()` enqueues an upsert without a per-object acknowledgement. WebSocket completion means local enqueue; HTTP completion means the HTTP request completed, not individual write confirmation.
- Requests have deadlines and cancellation. Queues are bounded, and requests cancelled before a buffered flush are removed from that flush.
- Unexpected disconnects fail pending requests. `ConnectionLostException.outcomeUnknown` means an already-sent write may have been applied. Writes are never automatically replayed.
- Reconnect restores the latest active criteria and obtains fresh snapshots. Cached state is marked stale while disconnected. Sequence gaps trigger a fresh subscription; initial `seq = -1` snapshots and shared streams' arbitrary first sequence are handled separately.
- There is no durable offline queue, exactly-once delivery, event replay, or guaranteed background connectivity.
- History dynamic data is historical; its static data, label and category describe the current entity. History availability depends on server persistence.

## Android example and tests

Configure an Android SDK (API 36/build tools) with `ANDROID_HOME`, then:

```shell
bash gradlew -PincludeAndroidSample=true :sample-android:assembleDebug :sample-android:assembleRelease :sample-android:lintDebug
bash gradlew -PincludeAndroidSample=true :sample-android:connectedDebugAndroidTest
```

The sample accepts a WebSocket URL, access token and existing realm ID. Its emulator default URL is `ws://10.0.2.2:8090/ws`. Cleartext is enabled only in debug builds. The ViewModel owns the connection; the UI collects its state with `repeatOnLifecycle`.

The SDK tests include protocol fixtures, a controllable HTTP/WebSocket peer, cancellation, startup event ordering, shared observers, reconnect, sequence recovery and overflow. To run integration tests against a sibling `core-server` checkout on Windows (Go and JDK 17 on PATH):

```powershell
.\tools\Test-Server.ps1
# With an API 23 emulator already running:
.\tools\Test-Server.ps1 -AndroidDevice
```

The runner starts an independent in-memory Hivekit server bound to localhost, uses a test-only signing key, runs tests, and stops that server in a finally block. It does not use your existing server configuration or database. Integration tests are skipped by ordinary `sdk:test` runs unless `HIVEKIT_TEST_HTTP_URL` is set.

CI builds the library, creates a local Maven publication, builds/lints debug and optimized Android release variants, and runs an API 23 device smoke test.
