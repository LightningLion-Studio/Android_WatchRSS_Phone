# Read-only sync diagnostics

The release and debug APKs contain an unexported `SyncDiagnosticService` guarded
by `android.permission.DUMP`, with no Binder API or intent-driven commands.
Normal activity resume starts it. Complete app setup and open the phone app
normally before inspection; diagnostics do not launch or sync the application.

```sh
adb -s PHONE shell dumpsys activity service com.lightningstudio.watchrss.phone/.diagnostics.SyncDiagnosticService
adb -s PHONE shell dumpsys activity service com.lightningstudio.watchrss.phone/.diagnostics.SyncDiagnosticService 3
adb -s PHONE shell dumpsys activity service com.lightningstudio.watchrss.phone/.diagnostics.SyncDiagnosticService sync.last 3
adb -s PHONE shell dumpsys activity service com.lightningstudio.watchrss.phone/.diagnostics.SyncDiagnosticService library.books
adb -s PHONE shell dumpsys activity service com.lightningstudio.watchrss.phone/.diagnostics.SyncDiagnosticService library.summary
```

The application output is one line prefixed `WRSS_SYNC_DIAGNOSTICS `. Default
output contains `books`, `summary`, and `sync.last`. A decimal count 1–12 selects
history length (default 3). Unknown sections, extra arguments and invalid counts
return `invalid_argument`. Read failures return `diagnostics_unavailable` without
exception text. `sync.last` alone does not read the library or hash book files.
Android's dumpsys wrapper may print its own service/process metadata outside this
application JSON line. Extract only the prefixed line.

Books expose `bookId`, `title` (256 characters), `chapterCount`, `packageBytes`,
`packageSha256`, `status`, `reasonCode`, `readable`, and a local `manifest` summary
(`fingerprint`, `chapterCount`, `deleted`). Whole-book tombstones are excluded.
A valid local package and readable chapter 1 yield `ready`; missing packages or
unregistered imports yield `staged`; corrupted packages/body failures yield
`failed`. Unregistered imports have null IDs/hashes, zero package bytes and
`registration_pending`. Inspection opens existing SQLite with `OPEN_READONLY` and never opens Room. A
missing database yields an empty library without creating or migrating it.
Inspection never creates a replacement identity or
triggers registration, recovery, cloud traffic or UI navigation.

Summary counts active channels, all nondeleted article rows, active favorites,
active watch-later rows, nondeleted notes, and visible imported book groups. Metadata-only book records without displayed
chapters remain in `books` but do not increase the visible book summary.
Each saved-list predicate matches its Room UI query. Articles count distinct
local rows across the UI's RSS/imported/independent lists; saved-list membership
is counted separately. A book does not become several books because it has
several chapters. Pending imported groups are included.

History retains the latest 12 logical `syncAll` operations and at most 256
item records each. Fields: `startedAt`, `endedAt` (Unix milliseconds; null while
running), `channel` (`RFCOMM`/`IP`, final selected data transport), `result`, `totalBytes`,
`bytesPerSecond`, `reconnectCount`, `items`, `itemsTruncated`. Bytes include both
directions and four-byte frame prefixes. Throughput is wire bytes divided by
monotonic operation duration. The channel is the last selected data transport, including IP-to-RFCOMM fallback.
Reconnects use the manager's visible retry budget.
Each item has only `kind`, `id`, `result`, `reasonCode`. Full content-hash book
IDs are retained; other identifiers outside the opaque-ID grammar become null.
Unknown reason strings become `unknown`. `itemsTruncated=true` is not complete
item acceptance. Note-batch rejection records each submitted note ID. Asset
rejection uses the content SHA-256 and never exposes its storage key or filename.
Interruption may leave a persisted `running` entry;
never accept it as success. Diagnostics are separate from business data and do
not include account identifiers, URLs, tokens, cookies, paths, bodies or routing.

The framework performs the DUMP authorization before dispatching into the app's
service callback; checking Binder caller identity inside the main-thread dump
would inspect the forwarding call rather than the original shell identity.
No debug-only registration or debuggable gate is used.
