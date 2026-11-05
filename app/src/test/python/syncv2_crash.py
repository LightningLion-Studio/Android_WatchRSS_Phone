#!/usr/bin/env python3
"""Run the actual Kotlin product adapter in killed Robolectric native SQLite JVMs.
Only synthetic temporary directories; expected unsuccessful Gradle runs are retained as evidence.
"""
import argparse
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--evidence', type=Path, required=True)
p.add_argument('--boundaries', nargs='*', default=['before-map','after-map','before-log','after-log','before-checkpoint','after-checkpoint','before-commit','after-transaction','after-tail-entry'])
p.add_argument('--journals', nargs='*', default=['DELETE','WAL'])
args = p.parse_args()
args.evidence.mkdir(parents=True, exist_ok=True)
results = []
for journal in args.journals:
    for boundary in args.boundaries:
        with tempfile.TemporaryDirectory(prefix='wrss-p1-crash-') as temporary:
            root = Path(temporary)
            env = os.environ | {'WRSS_P1_CRASH_AT':boundary,'WRSS_P1_CRASH_DIR':str(root),'WRSS_P1_CRASH_JOURNAL':journal}
            log = args.evidence / f'crash-{journal}-{boundary}.log'
            with log.open('w') as out:
                proc = subprocess.run(['./gradlew',':app:testDebugUnitTest','--offline','--tests','com.lightningstudio.watchrss.phone.syncv2.SyncV2CrashChildTest'], env=env, stdout=out, stderr=subprocess.STDOUT)
            if not (root/'ready.json').exists():
                raise RuntimeError(f'child did not reach prepared prefix: {log}')
            content = log.read_text()
            if proc.returncode == 0 or '73' not in content:
                raise RuntimeError(f'worker did not halt at requested boundary: {log}')
            db = sqlite3.connect(root/'phone.db')
            counts = {name:db.execute(f'SELECT COUNT(*) FROM {name}').fetchone()[0] for name in ['syncv2_identity_map','syncv2_candidates','syncv2_change_log']}
            cursor = db.execute("SELECT lastRemoteSeqApplied FROM sync_peer_state WHERE peerDeviceId='peer'").fetchone()[0]
            page,complete = db.execute("SELECT page_key,complete FROM syncv2_scan WHERE domain='phone_articles'").fetchone()
            expected = 2 if boundary=='after-transaction' else 1
            assert all(n==expected for n in counts.values()), (journal,boundary,counts)
            assert cursor==7
            assert page==('b-suffix' if expected==2 else 'a-prefix')
            assert complete==(1 if expected==2 else 0)
            row = {'journal':journal,'boundary':boundary,'workerExitCode':73,'counts':counts,'pageKey':page,'complete':bool(complete),'oldCursor':cursor,'persistentPrefixRetained':True,'scope':'Actual Kotlin product SQL in Robolectric native SQLite; host process halt, not device power loss'}
            results.append(row)
            (args.evidence/'process-crash.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
            print(json.dumps(row), flush=True)
