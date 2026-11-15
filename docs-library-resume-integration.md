# library-resume v1 protocol integration (T-PHONE-RESUME-C)

This module is deliberately dormant until the separate A/B/C reconnect controller is integrated.
No existing v14 reader, probe, ACK loop, route selection, UI or product switch calls it yet.
The wire is frozen by `library-resume-v1.md` (RESUMEC-SPEC2); there are eight phases.
`missing` provides status, heartbeat and chunk discovery. There is no network `getChunk` or `status`.

## Ports and ownership

- `ResumeNegotiation.propose` adds `supportedLibraryResumeVersions:[1]` to the v14 probe only
  when local adapters and persistence are ready. `echoed` accepts only an explicit, valid
  `libraryResume` echo. Missing echo selects B BEFORE sending any C frame. Malformed echo is a
  protocol failure, not permission to downgrade. Do not use article-checkpoint support as C support.
- `ResumeClient` owns phone offer/confirm and the phoneToWatch source. It never reconnects or
  silently downgrades. `ResumeReceiver` owns watchToPhone jobs. Both directions push missing
  chunks from the source. Source requests use stable IDs, queries use fresh IDs.
- `ResumeSession` owns one stream reader and one whole-frame writer lane. Start it only after
  v14 probe/Ready/account exchange AND all old application ACKs finish. It routes replies even
  while its inbound worker is executing a request. Install the receiver inside `confirm`'s
  callback BEFORE sending confirm. Only the watch's successfully written confirm response
  permits its reverse begin. Route inbound requests through `client.handleRequest(frame, receiver)`:
  it routes watchToPhone work to the receiver and remote cancellation of phoneToWatch work
  to the source journal. Supply `session::cancelJob` as the client's `onSourceCancelled`
  callback so a cancelled source stops waiting immediately. There are no old ACKs inside this session.
- `ResumeAuthorization` is LOCAL consent, account and stable peer authorization. Construct it
  from the currently verified session, never from a network begin. The controller calls the
  receiver's serialized `revoke(cancelJobs=true)` before account cleanup or consent cancellation.
  Use `revoke()` when handing off an old connection. Call it on physical disconnect too. Authorization must be checked at each
  client request and at business commit. The current predicate must not silently switch to a
  different account. Do not log accountScope or export credentials into descriptors.
- `ResumePersistence` shares the actual `PhoneCompanionDatabase` and a single commit lane per
  DB instance. Before the first C operation it sets and verifies SQLite synchronous=FULL
  outside a transaction; dormant C changes no database setting. Use the application's SINGLE
  DB instance. The controller must serialize account
  teardown on this lane; do not open a second writable DB/process for C. `ResumeRecord` uses
  separate job/object/request/page/chunk/snapshot/preparation/sourceJob/peerAck namespaces and
  composite primary keys. All values are bounded JSON. Migration 19→20 only adds this table.
- `ResumeFiles` must use one private, app-owned sandbox root for all C scopes. Its hashed local
  filenames never use remote paths. It fsyncs file data before atomic rename and fsyncs the
  directory before admitting chunk receipts. Cache adoption rehashes actual bytes. A half frame
  cannot stage. A corrupt local chunk becomes missing; a bad incoming hash is a protocol error.
- `ResumeBusinessPort.apply` is a SUSPENDING transaction adapter, not a notification. Use only
  the supplied Room business DB and existing MERGE/clock/provenance logic. The receiver invokes
  it INSIDE the same transaction that commits APPLIED. A thrown error rolls both back. Publish
  referenced files immutably BEFORE this call; do not reference the disposable staged file.
  Return merged/retainedLocal/alreadyPresent from the actual merge. P6 rollback/inbox protection
  must still reject inputs whose real business transaction cannot finish; inbox alone is not APPLIED.
- `ResumeBusinessPort.complete` commits only the frozen source interval with the receipt. A
  non-zero interval requires `canCommitRange` and a no-gap cursor adapter. The default rejects
  nonzero intervals. `peerAck` records only that frozen upper bound; it never reads latest maxSeq.
- `ResumeSnapshots.freeze` receives an export captured under the domain's read transaction.
  It copies original encoded bytes before begin. Descriptors, inventory bytes, witnesses and
  file hashes persist. Resume never recompresses or reads mutable live article rows. Missing
  frozen bytes cause SOURCE_CHANGED/cancel, not replacement content under the same object ID.

## TODO: separate controller integration

1. Implement real phone domain export/apply adapters around the existing article/RSS, reader
   and note-asset repositories. Validate the FULL frozen legacy business schema there, including
   article gzip/128-KiB inner chunks/32-MiB decoded limit, register provenance and local edits.
   The generic layer validates C descriptor/metadata bindings; it does not replace those parsers.
   Declare ONLY adapters whose business writes and references are in the same Room transaction.
   Reader and note assets must remain absent until their immutable file publication/reference
   semantics are wired. Full note bodies and books stay on their existing path.
2. On EACH new connection: probe with proposal → stable peer check → Ready/account gate →
   durable phone snapshot → offer → exact directional selection → confirm with receiver installed.
   Persist the preparation BEFORE confirm. If confirm reply is lost, keep its witnesses/jobs,
   reconnect and do new offer/confirm with original bindings and intent=resume.
3. Use `ResumePlanner.preflight` BEFORE choosing the C route. Unsupported mode/kind,
   object/metadata/page/manifest limits or journal pressure returns an explicit UseLegacy
   decision. Do not call that decision a successful C operation. Restore original selection when it fits both current capabilities. Otherwise explicitly
   replan BEFORE begin under a new job ID. An accepted plan never changes pagination, object
   IDs, metadata bytes, mode or encoding. Replace modes choose B before planning.
4. Call `ResumeClient.send` only after confirm. Its QUERY/WAIT/BEGIN recovery stays on the
   SAME authenticated connection and plan. The controller preserves logical counts. Never
   treat these state errors as a physical disconnect. On NOT_FOUND/EXPIRED/SOURCE_CHANGED, replan explicitly.
   Preserve the three-failures and inclusive ten-second disconnect window from A/B.
5. Revoke and close the OLD connection before a replacement. Ignore its late ACK/exception
   using the controller's connection generation. Do not let an old STALE_EPOCH stop the new one.
6. Close C normally only when BOTH selected directed jobs are COMPLETE. Run other B domains
   or full note exchange on a NEW authenticated connection. Normal closure is not a disconnect.
7. Add retention-aware GC on the commit lane if storage reuse is required. This first protocol
   module conservatively retains journal/snapshot records; it rejects STORAGE_PRESSURE instead
   of deleting recovery receipts. Never GC live leases, business references or younger receipts.
8. Run the combined route/product matrix separately. This card's host tests do not validate
   RFCOMM, IP, A/B recovery, native Android durability, production or device behavior.

## Host verification

Use the task's `flock ... capmem 16G ./gradlew ... --no-daemon -Dorg.gradle.workers.max=2` wrapper.
`ResumeVectorsTest`, `ResumeDurabilityTest`, and `ResumeSessionTest` are hermetic host tests.
For explicit Kotlin↔PodJS host interop, set `WATCHRSS_RESUME_WATCH` to the watch application
checkout at `83d4f8fef24091aa5546cb18eaac91eb73678bb9` and include `ResumeWatchInteropTest`.
It uses that application's real coordinator, SQLite and article repository with its test native
facade. It uses Bun for TypeScript execution. It does not contact a service or device.
