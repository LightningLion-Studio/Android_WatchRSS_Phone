# T-REVIEW-NOTES-RENDER-FIX

Test-only review of 1226acc. Run from OPPO_Develop using cached dependencies:

```sh
flock /tmp/wrss-heavy-Android_WatchRSS_Phone.wt-notes-render-review.lock capmem 8G python Android_WatchRSS_Phone.wt-notes-render-review/tests/notes-render-review/run-host.py .codex-tmp/cloud-v2/evidence/T-REVIEW-NOTES-RENDER-FIX/phone-main Android_WatchRSS_Phone.wt-notes-render-review
```

The runner compiles actual product sources and original JUnit tests, plus `NoteRenderReviewTest`. Expected snapshot result: 21 tests, 20 passing and one deliberate RNR-06 failure. A supplementary-plane letter is skipped by the UTF-16 Char word predicate. `title-probe.json` supplies actual JVM outputs for the watch/mini comparison.

RNR-07 records the intentional 80-code-point policy and its grapheme limitation. NA-15's original surrogate-boundary regression passes. This is host JVM coverage, not Android ICU or pixel validation. No Gradle, device, server, dependency download or product edit is required.
