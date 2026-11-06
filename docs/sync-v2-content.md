# Sync v2 P3A content ports

The content channel is opt-in (`syncObjectsV2Content=false` by default). It uses only the
loopback backend accepted by `SyncV2CloudClient`. This implementation does not activate a
production account, change the UI, replace business files, enable local_objects, or evict data.

## Host integration

- Keep the P2 control client/executor independent. Construct a fresh content HTTP client and
  `SyncV2ContentDispatcher` for content windows. One store has one worker owner.
- Obtain identity, fixed signing key, content key/version and C3 service pin through the existing
  approved P2 bootstrap. Use a separate directory per scope and its protected local encryption key.
- `SyncV2ContentSettings.allowChargingCellular` defaults to true and commits synchronously.
  Sample Android power/network evidence with `SyncV2AndroidTransport.sample`. Only the current
  application-authenticated Bluetooth session can set the Bluetooth argument; pairing/ACL is insufficient.
- Enqueue authenticated rows with local classification in `SyncV2ContentQueue`. Supply the global
  recent import universe, including already complete imports, so the third file never replaces a slot.
- Supply immutable original bytes through `SyncV2ContentExport.Source`. `version` is a body witness,
  independent of reading/favorite/title changes. File size and mtime alone are not a body witness.
  `FileSource` reads bounded ranges of an immutable original package; it must not point to a
  mutable reconstruction. Cache bindings include scope, entity, body revision, source version,
  ranges, descriptor and content key version.
- Submit one `uploadPage` or `downloadPage` to the dedicated dispatcher per granted host window.
  Defaults are 300 seconds, 10 seconds cleanup reserve and 32 MiB content bytes. Nested request
  deadlines shorten the HTTP timeout. No method automatically schedules another window.
  Honor `Result.retryAfterMillis` for no-progress retries; an identity/condition change may wake the host.
- Upload returns `objects-complete` after exact signed receipts and immutable recipe refs. Its attach
  callback calls `SyncV2Projection.attachContentRecipe` for a short rowSequence/bodyRevision CAS.
  C10 freezes the order: complete recipe/blob objects and register their refs, then publish the
  recipe-bound IndexRow with the P2 double CAS, then request availability/check. A CAS conflict
  reuses already confirmed envelopes. Availability requires the current account tree and never
  authorizes a P3A deletion. Transport completion is not account publication or applied ACK.
- Download uses current authenticated control rows and verifies signed public recipe refs,
  SignedAvailability, all WRS2 envelopes, per-block fingerprints, total length, full SHA-256 and
  whole-body opaque identity. `Verified.bind` checks the current entity/body revision/row sequence
  and deletion state. Its callback records cache readiness only. No business original is overwritten.
- The application startup/WorkManager and UI activation remain outside these opt-in ports.
  Claude owns the UI and the approval to enable a tested account. Live backend joint tests require
  the task's separate handoff; synthetic unit tests do not establish backend settlement correctness.

## Recovery and limits

Fine frames are at most 65,536 bytes. A frame is fsynced before its durable offset checkpoint.
A crash between those writes replays at most one fine frame. On upload uncertainty, the next
window queries exact have/want before sending bytes. Under C8, a complete durable prefix is
settled by the backend and returned as present; the client does not retransmit it.

A recoverable completion journal commits fairness and object receipt together. Eight completed
rank0 blobs permit one ordinary blob; ordinary turns rotate 1–4. Current demand is read at window
start, at most 30 seconds apart at frame/work boundaries, and after successful POST. Only approved
requesters affect rank0. Slow network requests remain bounded by their request and host deadlines.

Immutable exported envelopes and authenticated block checkpoints survive windows and restarts.
Confirmed bodies reuse descriptors without source export/compression/encryption/full-body hashing.
Metadata pages and have/want batches hold at most 64 entries; metadata pages also have a 64 KiB
byte bound. Native file operations are counted directly. Kotlin has no cross-bridge calls here.

Recipes enforce 2,048 blocks, 100,000 ranges and actual uncompressed canonical JSON <=262,144
bytes simultaneously. Oversize content retains its original entity and bytes. C7 keeps
note_segments/note-segments.v1 disabled; state-refs still register empty segmentOids. P3A has
no deletion, custody-release, GC or eviction code.

The host must retain the queue and report space pressure if content storage cannot grow.
Staged ciphertext is retained in P3A; no cleanup is authorized by a receipt or a short lease.
