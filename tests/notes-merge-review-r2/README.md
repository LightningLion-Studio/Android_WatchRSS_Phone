# Merge r2 independent review

`NoteMergeR2ReviewTest` adds acceptance assertions for RVM2-09/10/11/12/14/15.
They intentionally fail against a01af1c. RVM2-13 is a passing phone control.
The original RVM tests and their assertions are unchanged in this review branch.

Normal execution (when no other Gradle is running):

```sh
flock /tmp/wrss-heavy-Android_WatchRSS_Phone.lock capmem 8G env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --tests '*NoteMergeReviewTest' --tests '*NoteMergeReviewV2Test' --tests '*NoteMergeR2ReviewTest' --offline --no-daemon -Dorg.gradle.workers.max=2
```

This review used `run-room.py` instead because other sessions held active Gradle
processes. It recompiles all tested note repository dependencies and the two changed
v2 modules from this checkout, then runs JUnit/Robolectric native SQLite with actual
Room generated classes from an already built a01af1c checkout. It does not generate
an APK or claim a fresh Gradle build. Pass `--compiled-product` pointing to that
checkout, `--classpath` pointing to the cached test dependency classpath, and `--out`
pointing outside the repository. Android SDK artifacts are strictly offline.
Compiler inputs and source hashes are retained in the output directory.

RVM2-12 injects a competing conflict marker after repository validation, immediately
before the real Room commitNote transaction. All reads/writes use real Room; only
the scheduling boundary is controlled. No device, server or product source edits.
