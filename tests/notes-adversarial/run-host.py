"""Offline host runner. Compile actual product sources with verbatim editor helpers.

Use --product for a checkout, or --replay-sources for an earlier output directory.
Run under flock/capmem. Dependencies must already be cached; no downloads occur.
"""
from pathlib import Path
import argparse
import hashlib
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--product', type=Path, default=Path(__file__).resolve().parents[2])
p.add_argument('--replay-sources', type=Path)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--classpath', type=Path, required=True)
p.add_argument('--case', action='append', default=[], help='Run only case IDs with this prefix (repeatable)')
p.add_argument('--gradle-cache', type=Path, default=Path.home() / '.gradle/caches/modules-2/files-2.1')
p.add_argument('--unfocused', action='store_true', help='Use the product focus-loss projection on both render trees')
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)
cache = a.gradle_cache
shared = str(Path.home() / '.gradle/caches/modules-2/files-2.1')
cp = ':'.join(str(cache / x[len(shared)+1:]) if x.startswith(shared + '/') and
              (cache / x[len(shared)+1:]).exists() else x
              for x in a.classpath.read_text().strip().split(':'))
compiler = next((cache / 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10').rglob('*.jar'))
extra = [next((cache / s).rglob('*.jar')) for s in [
    'org.jetbrains.kotlin/kotlin-stdlib/2.2.10',
    'org.jetbrains.kotlin/kotlin-reflect/2.2.10',
    'org.jetbrains.intellij.deps/trove4j',
]]
helper = a.out / 'EditorHelpers.kt'
snapshot = a.out / 'sources'
snapshot.mkdir(exist_ok=True)
if a.replay_sources:
    helper.write_bytes((a.replay_sources / 'EditorHelpers.kt').read_bytes())
    inputs = list((a.replay_sources / 'sources').glob('*.kt'))
else:
    root = a.product / 'app/src/main/java/com/lightningstudio/watchrss/phone'
    editor = (root / 'NoteRichTextEditor.kt').read_text()
    # Exact source slices; imports only, no parser or serializer reimplementation.
    slices = [
        editor[editor.index('internal fun RichTextState.insertImagePlaceholder('):
               editor.index('@Composable\nprivate fun TableInsertDialog')],
        editor[editor.index('internal fun RichTextState.toNoteStorageMarkup('):
               editor.index('internal fun parseHexColor(')],
    ]
    helper.write_text('''package com.lightningstudio.watchrss.phone
import com.mohamedrejeb.richeditor.model.RichTextState
import com.lightningstudio.watchrss.phone.data.note.NoteEditorStorage
import androidx.compose.ui.text.TextRange
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.roundToInt
''' + '\n'.join(slices).removesuffix('@Composable\n'))
    inputs = [root / 'data/note' / s for s in [
        'NoteEntities.kt', 'NoteDao.kt', 'NoteRepository.kt', 'MarkdownNoteCodec.kt',
        'MarkdownThreeWayMerge.kt', 'NoteCloudStateCodec.kt', 'MarkdownNoteArchive.kt',
    ]]
    inputs += [root / name for name in [
        'data/note/NoteConflictIdentity.kt', 'data/note/NoteTitle.kt', 'data/note/NoteEditorStorage.kt', 'NoteEditorDraft.kt', 'NoteVisualDocument.kt',
    ] if (root / name).exists()]
# Freeze all compiler inputs so a concurrent product edit cannot change them mid-compile.
sources = []
for source in inputs:
    target = snapshot / source.name
    target.write_bytes(source.read_bytes())
    sources.append(target)
tests = a.out / 'HostTests.kt'
tests.write_bytes(Path(__file__).with_name('HostTests.kt').read_bytes())
sources += [helper, tests]
(a.out / 'source-sha256.txt').write_text('\n'.join(
    hashlib.sha256(s.read_bytes()).hexdigest() + '  ' + str(s) for s in sources) + '\n')
command = ['java', '-cp', ':'.join(map(str, [compiler, *extra])) + ':' + cp,
           'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
           '-classpath', cp, '-d', str(a.out / 'host.jar'), *map(str, sources)]
r = subprocess.run(command, capture_output=True, text=True)
(a.out / 'compile.log').write_text(r.stdout + r.stderr)
if r.returncode:
    print(r.stdout + r.stderr)
    raise SystemExit(r.returncode)
r = subprocess.run(['java', '-cp', str(a.out / 'host.jar') + ':' + cp,
                    'com.lightningstudio.watchrss.phone.HostTestsKt', str(a.out), *a.case,
                    *(['--unfocused'] if a.unfocused else [])],
                   capture_output=True, text=True)
(a.out / 'results.log').write_text(r.stdout + r.stderr)
print(r.stdout + r.stderr)
raise SystemExit(r.returncode)
