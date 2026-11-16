# Library Binary v2, S2

Wire authority: `library-binary-v2.md` r3, SHA-256
`3d1ed2b857be3d599d52221c02c04b2672f80b5b42e9443eaeab844632cad8cb`.
S2 uses 65536-byte blocks, one DATA credit, one business request per job,
and immediate single-entry durable ACKs. It is separate from cloud Sync v2.

This directory has no Android imports. Its dependencies are Kotlin/JVM,
`kotlinx.coroutines`, and `org.json` (the Android runtime supplies org.json;
JVM consumers supply the library). Raw DATA never enters JSON or Base64.

The product currently installs only the `noteAsset` send/receive adapter.
Other inherited kinds and all books, note records, usage and replace modes
remain on their original independent v1 connections. Do not advertise a kind
just because the codec knows its name.

## Host contracts

* `B2Persistence.exclusive` serializes protocol mutations across connections.
  `transaction` must share the actual business database transaction. Chunk
  bytes, request binding and durable receipts must precede ACK. Journal and
  physical peak budgets must include retained state and frozen source spools.
* `B2BusinessPort` validates the existing business schema and performs its
  merge/reference registration inside that same transaction. It cannot
  manufacture APPLIED from transport completion or an inbox/pending choice.
* Authorization is local, scoped to account, stable devices and confirmed
  bindings. Revoke before closing a connection; callbacks from the old
  dispatcher must never acquire a new connection's grant. The final business
  transaction rechecks authorization and fencing.
* `B2Session` is the unique reader and writer. Install it before `switch`, start
  its reader only after the full switch response, and preserve glued bytes.
  `closeTransport` must interrupt the real blocking socket read/write.
* `B2Files.onProgress` reports completed file/hash work units to the local
  watchdog. Heartbeat replies read a committed lightweight state projection,
  independently of business work, including PLANNING before peak admission.
* `B2Recovery` belongs to the entire user operation. Share it across independent
  v1 tasks, B2 connections and replanning. Planned handoffs preserve its counters
  and semantic progress set. Only actual transport failures enter recovery.

The Android ports live next to this directory: `B2RoomStore`,
`B2NoteAssetBusiness`, and `B2NoteAssetSync`. The Room port creates private
B2 tables only on first enabled use; it does not migrate or delete old v1 data.
Its persistent quota ledger avoids scanning the journal on each DATA block.
The Room port re-establishes FULL before each write transaction and verifies
the pinned writer inside it; a standalone reader PRAGMA cannot prove durability
of a replacement pooled writer.
`B2RequestBindings` keeps immutable requestId/digest bindings in an independent,
bounded native journal, so heartbeats do not wait for business transactions.
The native journal and business journal each reserve half maxJournalBytes;
peak admission also reserves the full journal ceiling. Source spools reserve
journal headroom before the switch. Records are conservatively retained without eager GC; quota exhaustion stops
rather than deleting unexpired receipts.

`-Pb2Enabled=true` opts a local build in. Every build type defaults to false.
Disabling it restores the unchanged legacy probe and Base64 frame path.
A B2 protocol/account/hash/storage/business failure is terminal for that path,
never an instruction to retry v1.

Frozen specification/review vectors and source-derived peer fixtures are under
`src/test/resources/b2`. Host pipe tests exercise the actual frame dispatcher;
Room tests exercise business reference/receipt rollback. They are not device,
released APK classification, filesystem power-cut, PSS or radio throughput tests.

Frozen source inventories use bounded persistent JSON pages. Restoring validates
one page at a time and retains the original whole-inventory digest. The CONTROL
node/byte limits stay unchanged; old local inventory journals migrate by page.
