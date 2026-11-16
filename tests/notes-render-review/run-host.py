from pathlib import Path
import subprocess,sys,json
root=Path(__file__).resolve().parents[3];ev=Path(sys.argv[1]);ev.mkdir(parents=True,exist_ok=True)
phone=Path(sys.argv[2])
cache=Path('/home/wilf/.gradle/caches/modules-2/files-2.1')
def jar(rel):return next((cache/rel).rglob('*.jar'))
cp=(root/'.codex-tmp/cloud-v2/evidence/T-NOTES-FORMAT-RCA/classpath.txt').read_text().strip()+':'+str(jar('junit/junit/4.13.2'))+':'+str(jar('org.hamcrest/hamcrest-core/1.3'))
compiler_cp=':'.join(map(str,[jar('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'),jar('org.jetbrains.kotlin/kotlin-stdlib/2.2.10'),jar('org.jetbrains.kotlin/kotlin-reflect/2.2.10'),jar('org.jetbrains.intellij.deps/trove4j')]))+':'+cp
rel=Path('com/lightningstudio/watchrss/phone/data/note')
sources=[phone/'app/src/main/java'/rel/n for n in ['NoteEntities.kt','NoteDao.kt','NoteRepository.kt','MarkdownNoteCodec.kt','MarkdownThreeWayMerge.kt','NoteTitle.kt']]+[phone/'app/src/test/java'/rel/n for n in ['NoteTitleTest.kt','NoteRepositoryTest.kt','MarkdownNoteCodecTest.kt','NoteRenderReviewTest.kt']]
out=ev/'phone-tests.jar'
probe=ev/'TitleProbe.kt'
probe.write_text('''package com.lightningstudio.watchrss.phone.data.note
import org.json.JSONArray
import org.json.JSONObject
fun main() {
 val inputs=listOf("𐐀 alpha beta gamma","école cafe noir suite","𠀀𠀀𠀀𠀀𠀀𠀀𠀀𠀀","\\ufeff\\none two three four",".".repeat(78)+"👨‍👩‍👧‍👦","a".repeat(79)+"é")
 println(JSONArray(inputs.map { JSONObject().put("input",it).put("title",deriveNoteTitle(it)) }))
}
''')
sources.append(probe)
args=['java','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out),*map(str,sources)]
subprocess.run(args,check=True)
args=['java','-ea','-cp',str(out)+':'+str(phone/'app/src/test/resources')+':'+cp,'org.junit.runner.JUnitCore',*['com.lightningstudio.watchrss.phone.data.note.'+n for n in ['NoteTitleTest','NoteRepositoryTest','MarkdownNoteCodecTest','NoteRenderReviewTest']]]
with (ev/'title-probe.json').open('w') as output:
 subprocess.run(['java','-cp',str(out)+':'+cp,'com.lightningstudio.watchrss.phone.data.note.TitleProbeKt'],stdout=output,check=True)
sys.exit(subprocess.run(args).returncode)
