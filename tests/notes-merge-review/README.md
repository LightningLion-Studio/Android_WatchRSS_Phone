# T-REVIEW-NOTES-MERGE-FIX

Test-only review of phone 897a1e37. The six red acceptance tests are intentional.

- `NoteMergeReviewTest`: RVM-01 different-base replay; RVM-02 a new delete hidden by replay filtering; RVM-03 stale captured resolution; RVM-04 metadata-only resurrection; RVM-08 ancestor replay after reverting text.
- `NoteMergeReviewV2Test`: RVM-07 first v2 import retains an unresolved alias in the actual Room business tables.
- `compose-compat.py`: compiles frozen Compose v1.3.3-5 sources into the requested output directory. It does not modify Compose. The output records its deletion policy, its existing revert/replay defect, and actual wire fixtures.

Run from this worktree, offline:

```sh
flock /tmp/wrss-heavy-Android_WatchRSS_Phone.wt-notes-merge-review.lock capmem 8G env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --tests '*NoteMergeReview*' --tests '*NoteMergeFixTest' --tests '*NoteConflictAliasTest' --tests '*NoteRepositoryTest' --offline --no-daemon -Dorg.gradle.workers.max=2
```

Expected review-snapshot result: 14 tests, 8 passing, 6 failing. These are behavioral failures in production Room/repository code, not screenshot tests. The report and corpus are outside version control at `.codex-tmp/cloud-v2/{reports,evidence}/T-REVIEW-NOTES-MERGE-FIX*` under OPPO_Develop. No product source is changed.

T-NOTES-MERGE-FIX r2 retains every review assertion. Added coverage rejects an ABA
return to the same text using the captured NoteEntity, preserves both cyclic
ancestry versions, and exercises first import, explicit replacement and direct
SQL apply with a resolved alias. The phone UI captures the note when opening the
conflict dialog and passes it to resolveConflict; a stale operation reports an
error without dismissing the user's draft. A caller without a captured row can
only resolve while the current body still matches the conflict's local body.
