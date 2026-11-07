#!/usr/bin/env python3
"""Serialize the opt-in phone measurement against the pinned isolated backend.

All filesystem inputs are explicit. Only a NEW phone-owned synthetic account is
provisioned. Shared fixture files, the service process and other samplers are not
changed. Raw events are retained, including slow events; P99 is nearest-rank.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time

COMMIT = 'b87fe55aa38fe934dbe46fb61b1f39ab8c3944a9'
BINARY = 'ccca16f102198ef1d7b51a6b2599d00986fa384e3d9c963e72111d952d64b117'


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write(path, data):
    path.write_text(json.dumps(data, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser()
    for name in ('repo', 'environment', 'evidence', 'scratch', 'proc-root'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--samples', type=int, default=1000)
    parser.add_argument('--folders', type=int, default=12500)
    parser.add_argument('--modes', default='local,merge')
    args = parser.parse_args()
    assert 1 <= args.samples <= 1000 and 32 <= args.folders <= 12500
    assert args.samples < 1000 or args.folders == 12500
    os.umask(0o077)
    assert not args.scratch.exists() and not args.evidence.exists()
    args.evidence.mkdir(parents=True)
    proc = args.proc_root
    metadata = json.loads((args.environment / 'backend-process.json').read_text())

    def pin():
        pid = str(metadata['pid'])
        stat = (proc / pid / 'stat').read_text().split(') ', 1)[1].split()
        actual = digest(proc / pid / 'exe')
        assert actual == BINARY and stat[19] == metadata['startTicks']
        socket = subprocess.check_output(['ss', '-ltnp', 'sport = :18972'], text=True)
        assert '127.0.0.1:18972' in socket and ('pid=' + pid + ',') in socket
        return {'sourceCommit': COMMIT, 'binarySha256': actual, 'pid': metadata['pid'],
                'startTicks': stat[19], 'runningExecutableMatches': True, 'listener': socket.strip(), 'utc': utc()}

    shared_names = ('.owner.json', '.server-secrets.json', 'phone.json', 'watch.json', 'mini.json', 'cli.json')

    def shared():
        return {n: digest(args.environment / n) for n in shared_names if (args.environment / n).is_file()}

    before_shared = shared()
    write(args.evidence / 'shared-before.json', before_shared)
    # Required ps check immediately before ANY backend setup/measurement load.
    # Shell/rg command lines are excluded so the check cannot match its own text.
    pattern = re.compile(r'p5[-_].*(?:sampl|measur)|syncV2P5(?:Joint|LargeP99)Test|Gradle Test Executor', re.I)
    while True:
        snapshot = subprocess.check_output(['ps', '-eo', 'pid,ppid,etimes,args'], text=True)
        others = []
        for line in snapshot.splitlines()[1:]:
            fields = line.strip().split(None, 3)
            if len(fields) != 4 or int(fields[0]) == os.getpid():
                continue
            cmd = fields[3]
            if ' -c ' in cmd or re.match(r'(?:\S*/)?(?:rg|ps)\s', cmd):
                continue
            if pattern.search(cmd):
                others.append(line)
        record = {'utc': utc(), 'otherSamplers': others, 'proceed': not others}
        with (args.evidence / 'ps-guard.ndjson').open('a') as out:
            out.write(json.dumps(record) + '\n')
        if not others:
            break
        print('WAIT: another sampler/test process is active: ' + ','.join(x.split()[0] for x in others), flush=True)
        time.sleep(20)
    write(args.evidence / 'backend-pin.json', pin())
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.repo, text=True).strip()
    dirty = subprocess.check_output(['git', 'status', '--porcelain'], cwd=args.repo, text=True)
    write(args.evidence / 'invocation.json', {'utc': utc(), 'sourceCommit': source, 'dirty': dirty,
          'samplesPerMode': args.samples, 'folders': args.folders, 'modes': args.modes,
          'p99Rule': 'nearest rank ceil(0.99*N); no discarded events; each mode separately',
          'host': {'uname': list(os.uname()), 'cpuOnline': os.cpu_count(),
                   'cpuModel': next((x.split(':', 1)[1].strip() for x in (proc / 'cpuinfo').read_text().splitlines() if x.startswith('model name')), None)},
          'jdk': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True)})
    assert not dirty, 'commit the reviewable source before measurement'
    alive = threading.Event()

    def monitor():
        with (args.evidence / 'host-observations.ndjson').open('w') as out:
            while not alive.is_set():
                workers = []
                snapshot = subprocess.check_output(['ps', '-eo', 'pid,args'], text=True)
                for line in snapshot.splitlines()[1:]:
                    fields = line.strip().split(None, 1)
                    if len(fields) == 2 and 'GradleWorkerMain' in fields[1]:
                        try:
                            status = (proc / fields[0] / 'status').read_text().splitlines()
                            workers.append({'pid': int(fields[0]), 'memory': [s for s in status if s.startswith(('VmHWM:', 'VmRSS:', 'VmSize:', 'Threads:'))]})
                        except FileNotFoundError:
                            pass
                out.write(json.dumps({'utc': utc(), 'load': (proc / 'loadavg').read_text().strip(),
                    'meminfo': [s for s in (proc / 'meminfo').read_text().splitlines() if s.startswith(('MemAvailable:', 'SwapFree:'))], 'workers': workers}) + '\n')
                out.flush()
                alive.wait(10)

    thread = threading.Thread(target=monitor, daemon=True)
    env = dict(os.environ, WATCHRSS_P5_PHONE_JOINT=str(args.scratch), WRSS_P5_EVIDENCE=str(args.evidence),
               WRSS_P5_LARGE_SAMPLES=str(args.samples), WRSS_P5_LARGE_MODES=args.modes,
               WRSS_P5_LARGE_FOLDERS=str(args.folders))
    try:
        with (args.evidence / 'provision.log').open('w') as log:
            subprocess.run(['python3', str(args.repo / 'app/src/test/python/syncv2_p5_fixture.py'), 'create',
                            '--environment', str(args.environment), '--scratch', str(args.scratch)],
                           check=True, stdout=log, stderr=subprocess.STDOUT)
        thread.start()
        with (args.evidence / 'gradle.log').open('w') as log:
            result = subprocess.run(['./gradlew', ':app:syncV2P5LargeP99Test', '--max-workers=2', '--console=plain'],
                                    cwd=args.repo, env=env, stdout=log, stderr=subprocess.STDOUT)
        write(args.evidence / 'backend-after.json', pin())
        after_source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.repo, text=True).strip()
        after_dirty = subprocess.check_output(['git', 'status', '--porcelain'], cwd=args.repo, text=True)
        write(args.evidence / 'source-after.json', {'sourceCommit': after_source, 'dirty': after_dirty, 'unchanged': after_source == source and not after_dirty})
        assert after_source == source and not after_dirty
        assert before_shared == shared()
        write(args.evidence / 'shared-after.json', shared())
        assert result.returncode == 0, 'measurement failed; inspect the archived Gradle/JUnit report'
        summary = json.loads((args.evidence / 'large-p99-summary.json').read_text())
        assert summary['status'] == 'passed'
        assert summary['p99Reported'] == (args.samples >= 1000)
        events = [json.loads(line) for line in (args.evidence / 'large-p99-events.ndjson').read_text().splitlines()]
        assert len(events) == args.samples * len(args.modes.split(','))
        audit = {'sourceCommit': source, 'backendCommit': COMMIT, 'backendBinarySha256': BINARY,
                 'events': len(events), 'outliersDiscarded': 0, 'results': {}}

        def stats(values):
            ordered = sorted(values)
            out = {'min': ordered[0], 'p50': ordered[(len(ordered) + 1)//2 - 1], 'max': ordered[-1]}
            if len(ordered) >= 1000:
                out['p99'] = ordered[(len(ordered)*99 + 99)//100 - 1]
            return out

        for mode in args.modes.split(','):
            group = [e for e in events if e['choice'] == mode]
            assert [e['event'] for e in group] == list(range(1, args.samples + 1))
            for e in group:
                assert e['sourceReads']['databaseBytes'] == e['sourceReads']['fileBytes'] == 0
                assert e['blobBytes'] < 4*1024*1024 and e['uploadedObjects'] <= 8
                assert e['postProjectionRows'] == e['postProjectionTail'] == 0 and not e['appliedAckPending']
                marks = e['marksNs']
                e['prepareNs'] = marks['before-action-stage'] - e['previewNs']
                e['stageNs'] = marks['action-staged'] - marks['before-action-stage']
                e['uploadAndPreflightNs'] = marks['publication-attempted'] - marks['action-staged']
                e['publishNs'] = marks['server-confirmed'] - marks['publication-attempted']
                e['applyCommitNs'] = marks['after-account-commit'] - marks['server-confirmed']
                e['finalAckNs'] = e['previewNs'] + e['chooseNs'] - marks['after-account-commit']
            fields = ('previewNs', 'chooseNs', 'previewChooseNs', 'editProjectionNs', 'receiveLegacyNs', 'postProjectionNs', 'fullCycleNs',
                      'prepareNs', 'stageNs', 'uploadAndPreflightNs', 'publishNs', 'applyCommitNs', 'finalAckNs',
                      'blobBytes', 'stringUtf8Bytes', 'sqlQueries', 'fullCycleBlobBytes', 'fullCycleStringUtf8Bytes', 'fullCycleSqlQueries',
                      'uploadedBytes', 'uploadedObjects', 'httpCalls', 'httpHeadersNs')
            measured = {'events': len(group), 'sourceBodyBytes': 0, 'metrics': {f: stats([e[f] for e in group]) for f in fields}}
            for field in summary['results'][mode]:
                if field != 'events':
                    assert measured['metrics'][field] == summary['results'][mode][field]
            audit['results'][mode] = measured
        write(args.evidence / 'independent-summary.json', audit)
    finally:
        alive.set()
        if thread.ident is not None:
            thread.join(15)
        # Only private credential files created by THIS invocation. No service data
        # or shared fixture is deleted. Failed raw measurements remain reviewable.
        owner = args.scratch / '.owner.json'
        if owner.exists() and json.loads(owner.read_text()).get('phoneP5Owned'):
            deleted = []
            for path in args.scratch.glob('*.json'):
                deleted.append(path.name)
                path.unlink()
            write(args.evidence / 'private-fixture-cleanup.json', {'utc': utc(), 'deletedPrivateFiles': deleted,
                  'sharedFixturesDeleted': False, 'backendAccountDeleted': False})


if __name__ == '__main__':
    main()
