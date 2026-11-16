"""Compile frozen Compose sources offline, without changing its checkout."""
from pathlib import Path
import argparse
import hashlib
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--compose', type=Path, required=True)
p.add_argument('--classpath', type=Path, required=True)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)
cp = a.classpath.read_text().strip()
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
compiler = next((cache / 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10').rglob('*.jar'))
extra = [next((cache / name).rglob('*.jar')) for name in [
    'org.jetbrains.kotlin/kotlin-stdlib/2.2.10', 'org.jetbrains.kotlin/kotlin-reflect/2.2.10',
    'org.jetbrains.intellij.deps/trove4j']]
sources = []
for name in ['WatchNoteEntities.kt', 'WatchNoteDao.kt', 'WatchNoteRepository.kt',
             'NoteMergePolicy.kt', 'MarkdownThreeWayMerge.kt', 'NoteCloudStateCodec.kt',
             'NoteAssetReferences.kt']:
    target = a.out / name
    target.write_bytes(subprocess.check_output(['git', '-C', str(a.compose), 'show',
        'v1.3.3-5:app/src/main/java/com/lightningstudio/watchrss/data/note/' + name]))
    sources.append(target)
host = a.out / 'ComposeProbe.kt'
host.write_text('''package com.lightningstudio.watchrss.data.note
import java.io.File
import org.json.JSONObject
fun main(args:Array<String>) {
 val a=WatchNoteEntity("n",null,"T","a","a",watchNoteHash("a"),watchNoteHash("a"),"a",false,1,100,"compose")
 val dead=a.copy(deleted=true,deletedAt=101,updatedAt=101)
 val live=a.copy(pinned=true,updatedAt=102)
 check(mergeNoteVersions(dead,live,"receiver")!!.deleted)
 File(args[0],"compose-live.json").writeBytes(NoteCloudStateCodec.encode(listOf(live)))
 File(args[0],"compose-dead.json").writeBytes(NoteCloudStateCodec.encode(listOf(dead)))
 val b=a.copy(markdown="b",contentHash=watchNoteHash("b"),updatedAt=101)
 val reverted=a.copy(baseMarkdown="b",baseContentHash=watchNoteHash("b"),updatedAt=300)
 val replay=mergeNoteVersions(reverted,b,"receiver")!!
 val unicode=a.copy(markdown="👨‍👩‍👧‍👦\\r\\n\\u0000éאבג",contentHash=watchNoteHash("👨‍👩‍👧‍👦\\r\\n\\u0000éאבג"))
 check(NoteCloudStateCodec.decode(NoteCloudStateCodec.encode(listOf(unicode))).single().markdown==unicode.markdown)
 println(JSONObject().put("RVM-04-frozen-compose-deleted",true).put("RVM-08-frozen-compose-replay-body",replay.markdown).put("wire-unicode","PASS"))
}
''')
sources.append(host)
(a.out / 'source-sha256.txt').write_text('\n'.join(hashlib.sha256(s.read_bytes()).hexdigest()+'  '+s.name for s in sources)+'\n')
jar = a.out / 'compose-probe.jar'
r = subprocess.run(['java', '-cp', ':'.join(map(str,[compiler,*extra]))+':'+cp,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-classpath', cp, '-d', str(jar), *map(str,sources)], capture_output=True, text=True)
(a.out / 'compile.log').write_text(r.stdout+r.stderr)
if r.returncode:
    print(r.stdout+r.stderr)
    raise SystemExit(r.returncode)
subprocess.run(['java', '-cp', str(jar)+':'+cp,
    'com.lightningstudio.watchrss.data.note.ComposeProbeKt', str(a.out)], check=True)
