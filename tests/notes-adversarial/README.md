# T-NOTES-ADVERSARIAL host reproductions

Tests only. Red assertions express the intended invariant, not the defective output.
Issue IDs refer to the coordinator's local T-NOTES-ADVERSARIAL report and corpus.
No network, Android runtime, emulator, device or HTTP server is needed.

From `/home/wilf/data/OPPO_Develop`:

```sh
flock /tmp/wrss-heavy-Android_WatchRSS_Phone.lock capmem 16G python Android_WatchRSS_Phone/tests/notes-adversarial/run-host.py --product Android_WatchRSS_Phone --classpath .codex-tmp/cloud-v2/evidence/T-NOTES-FORMAT-RCA/classpath.txt --out .codex-tmp/cloud-v2/evidence/T-NOTES-ADVERSARIAL/phone-pc2-final
```

Exit 1 is expected while findings remain. Remove `--product` to use this branch's
pinned integration source. NA-15 skips if that source lacks the concurrent title helper.
The cached classpath contains the actual rich-editor 1.0.0-rc10 JVM dependency and
Compose/Room/JSON/jsoup dependencies from the format RCA. Kotlin 2.2.10 compiler
and dependencies must already be present in the local Gradle cache; no downloads occur.
The runner copies non-UI editor helper slices verbatim, compiles actual product
sources and writes source hashes, compiler logs, results and render trees under `--out`.
It never changes the selected product checkout.

NA-11 compares semantic text/paragraph/character-style trees, including save and
reopen through the actual pC2 `NoteEditorDraft`. It is not a screenshot test. Native
IME event delivery, selection/caret pixels, fonts, wrapping, scrolling and source-mode
geometry are unverified. Source-mode changes are candidate visual differences;
changed visible text/style is a confirmed necessary-condition failure.
Repository tests use an in-memory DAO port; NA-05 inserts a local commit exactly
between the product's read and write. It does not test Android Room scheduling.

Generate the untracked corpus (contains NUL and exact CR/LF bytes):

```sh
python Android_WatchRSS_Phone/tests/notes-adversarial/generate-corpus.py --out .codex-tmp/cloud-v2/evidence/T-NOTES-ADVERSARIAL/corpus --user-sample .codex-tmp/cloud-v2/evidence/T-NOTES-FORMAT-RCA/user-sample-vocab.txt
```

The compiled entry point also supports `HostTestsKt --wire input.json output.json`
for the offline cross-client codec test in the PodJS adversarial branch.

Parallel pC2 work can change while this review runs. To reproduce the exact captured
visual-document implementation, replace `--product ...` with
`--replay-sources .codex-tmp/cloud-v2/evidence/T-NOTES-ADVERSARIAL/phone-pc2-visual-snapshot`
and use a different `--out` directory. The runner freezes its compiler inputs.
The committed pC2 input is also saved under `pc2-committed-source/` in the evidence.
NA-18 is a passing cross-editor fallback control. NA-19 is an unconfirmed malformed
wrapper boundary assertion, not a confirmed pixel or data-loss finding. The visual
snapshot's highlight tree difference also needs actual pixel confirmation.

T-NOTES-MERGE-FIX selects NA-01/02/05/06/07/08/09/10/16 plus the wire control.
Set NOTES_ADVERSARIAL_ALL=1 to run the separate editor/export/title audit as well.
The DAO harness models commitNote as one atomic compare-and-commit operation;
NoteMergeFixTest independently exercises the actual generated Room transaction.
The runner exports merge-matrix.json for cross-client checks: 1728 merge inputs
and Unicode/NUL conflict-ID tuples. Neither this evidence nor reports are committed.
rc6b-contracts expected unions now collapse identical content tuples under NA-16;
the original input records, collision rejection and resolution markers stay covered.

Pixel r2 keeps the CAS-aware 1.4.0 store and all existing merge assertions.
Use `--case NA-03 --case NA-11 --unfocused` for the assigned editor cases.
Without selectors the original merge scope and skip rules remain unchanged.
`--unfocused` applies the product focus-loss projection on both render trees;
it does not replace pixel tests. `--gradle-cache PATH` selects an existing
modules-2/files-2.1 cache. Empty selections fail. No downloads occur.
