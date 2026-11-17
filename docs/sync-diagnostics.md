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
Book refusals use pC8's exact fields: `kind`, `bookIdPrefix`, `code`, `title`,
`reason`. Prefixes are eight lowercase hexadecimal characters or `invalid` and
are not unique keys. The nullable title uses the local manifest first, then the
remote manifest, strips control/format characters and URLs, and is capped at
120 UTF-16 units. `reason` is a fixed pC8 manifest subcode or `package_invalid`;
unknown/missing historical subcodes become `unknown`. The pC8 validator and
reason-preserving wrapper retain strict acceptance rules. Legacy stored book
refusals are projected to these fields on read, without fabricated titles or
subcodes. A prefix affects local book status only when it identifies exactly one
registered book. Other refusals and applied items retain `kind`, `id`, `result`,
`reasonCode`; full book IDs remain in inventory and applied items.
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

## r2 bounded diagnostics (supersedes earlier unpaged dump examples)

`Service.dump` never builds a library snapshot on the application thread. The first call returns `{"status":"pending"}`. Repeat the same command to poll one background job. Only one job and one cached page exist. A completed page expires after 10 seconds. `snapshotAt` identifies that collected result; different pages can observe library changes. Callers must not treat separately collected library/history sections as one database transaction.

- `library.books [offset limit]`: default offset 0, limit 8; limit 1–12. Output retains `books`, and adds `offset,nextOffset`. Continue until `nextOffset=null`. Only this page plus one lookahead book is inspected. Metadata fields and pending import grouping are projected/aggregated in SQL.
- `sync.last [n itemOffset itemLimit]`: n 1–12, offset 0–256, limit 1–4. The existing `sync.last n` form remains valid. Output adds `itemOffset,nextItemOffset` inside `sync.last`.
- Default output includes the first bounded book/history pages and summary. It does not imply full inventory acceptance.
- Each book page has an 8-second cooperative deadline and 64 MiB total streaming-hash budget. A file exceeding the remaining budget is `staged/hash_budget_exceeded`; its digest is not validated. Polling cannot turn this into a successful digest check. A deadline failure produces a fixed diagnostic error. No exception text is exported.

`pendingCount` is the number of rejected business-result events observed in this recorded synchronization, including missing acknowledgements. The unit is one reported item outcome, including reader/account warnings and each member of a refused manifest batch. It is not a count of package chunks, future work, or the cloud queue. A later successful session does not rewrite historical counts. `rejectedCount` has the same scope. `omittedCount`, `omittedRejectedCount`, and `omittedAppliedCount` count outcomes excluded by the 256-detail limit. These counters update before truncation, in constant space. Old truncated records without counters report null rather than inventing an exact number. Session `startedAt` identifies the counters' session. Library summary remains a separate inventory count.

A manifest-wide refusal emits one `book_batch_rejected` outcome for each submitted identity. This means the batch was refused, not that every member independently failed validation. A producer without a specific subreason records `unknown`. Body refusals preserve the peer's allowlisted reason and any available sanitized title. Local/remote success inventory duplicates for the same book are emitted once.

Titles contain display text only. URL, control, formatting, and credential assignment patterns (`Cookie`, `Authorization`, token/key variants, `K_id`) are redacted before truncation. Tests use synthetic strings only.

Mnemonic-labelled title tails (`mnemonic:` / `助记词：`, including ASCII and fullwidth colons) are redacted before the final title limit. Tests use synthetic placeholders only.
