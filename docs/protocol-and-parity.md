# Protocol and parity notes

The reference is the local JavaScript client 1.12.0 and the core-server native protocol, not the older speculative client-api-spec.js.

| JavaScript capability | Kotlin surface |
| --- | --- |
| connect/authenticate/disconnect, HTTP mode | HivekitClient + HivekitConfig |
| connection state, latency, version/build | connectionStatus, ping, serverInfo |
| realm CRUD and lifecycle subscription | client.realms |
| realm label/data getters/setters and events | Realm snapshot/getData/setData/setLabel/observe |
| object CRUD, set, filtered list, subscribe | realm.objects |
| area CRUD/list/subscribe, spatial shapes | realm.areas + Shape |
| instruction CRUD/subscriptions/logs | realm.instructions |
| task CRUD/list/subscribe, steps/status/targets | realm.tasks |
| subscription full/delta state, update/cancel | Subscription.state/updates/update/cancel |
| shared equivalent subscriptions | SubscriptionRegistry, structural JSON keys |
| search with limits and fields | Realm.search + SearchOptions |
| object history/time ranges | History.get + HistoryRange |
| event publish/subscribe/unsubscribe | PubSub.publish/subscribe/observe + cancellation |
| object presence | HivekitObject.connectionStatus + PubSub.observePresence |
| server statistics/admin cookies | client.system |
| secure generated IDs | HivekitClient.getId |

The API deliberately uses suspend calls and Flow rather than JavaScript promises/event emitters. It does not implement unrelated server REST services such as scheduling, reporting, file uploads, or people authentication, which are outside client-js parity.

## Wire contract

Requests and responses are JSON arrays. A WebSocket authenticates with a separate plain-text `Bearer <token>` frame and receives an uncorrelated `sys/aut` response. HTTP sends a bearer header or the configured cookie jar.

`cid` correlates responses; `typ` and `act` select routing. User attributes inside `dat` and `sdt` are never recursively renamed. The context-dependent `sty` is decoded as the area shape, subscription scope, or search data property.

Object/task subscriptions carry create/update/delete maps, including null-valued deletion markers. Updated entities replace their prior entries. Full snapshots reset accumulated state, including when the payload is empty. Area/instruction notifications carry snapshots of their respective maps. Realm metadata, realm lifecycle notifications and log batches have distinct decoders.

A single ordered receive loop reduces raw messages before updating UI StateFlows. Registration/refcount changes use a mutex so parallel observers do not create/cancel each other's server resources; the receiver does not take that mutex while request acknowledgements are pending. Per-group synchronization protects snapshot reduction and observer hydration. Request enrollment and disconnect cleanup synchronize only the pending-request map.

Subscription creation attaches local listeners before sending the request, allowing initial snapshots/logs/pubsub messages to arrive before their acknowledgement. Mutable criteria acquire the replacement subscription before detaching the original, leaving the original usable if replacement fails. Reconnection creates fresh server subscriber IDs to avoid collisions with delayed disconnect cleanup.

## Sequence and delivery limits

A numbered full snapshot establishes a sequence baseline. A one-off full snapshot with sequence -1 clears the baseline; the first subsequent numbered message establishes it, since a newly joined shared subscription need not start at 1. Later gaps mark state stale and recreate the subscription with an initial snapshot. Deltas are ignored during that recovery.

The server does not expose an atomic snapshot watermark or a general event replay protocol. The client does not promise lossless historical replay or exactly-once effects across disconnects. Pub/sub and logs are transient event streams, while entity subscriptions restore current state.

Transport-only HTTP success for set is not a per-object acknowledgement. Failures and cancellation cannot roll back writes that already reached Hivekit.

## Validation boundaries

The ordinary test suite uses a deterministic controllable peer to test protocol and failure paths without a server or Android SDK. Integration tests run real HTTP and WebSocket operations against an independent in-memory Hivekit server, including tenant isolation. Persistent history retrieval requires a separately configured persistence backend; history wire decoding and metadata semantics are covered by protocol/network fixtures.

The Android sample demonstrates lifecycle collection and a minSdk 23 consumer. The release build is minified to exercise serialization/network dependencies under R8. An instrumentation smoke test checks construction and models on API 23; it is not a substitute for fleet-specific network, process-death or background-operation testing.
