"""Offline Room/Robolectric runner; no Gradle daemon and no product substitutions.
Recompiles reviewed note and changed v2 sources. Unchanged generated Room classes
and app dependencies come from an explicitly selected, already built checkout.
"""
from pathlib import Path
import argparse, subprocess, hashlib, json, os
p=argparse.ArgumentParser()
p.add_argument('--compiled-product',type=Path,required=True)
p.add_argument('--classpath',type=Path,required=True)
p.add_argument('--out',type=Path,required=True)
a=p.parse_args();a.out=a.out.resolve();a.out.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[2];base=a.compiled_product.resolve();cache=Path.home()/'.gradle/caches'
classes=base/'app/build/tmp/kotlin-classes/debug'
assert classes.exists()
cp=a.classpath.read_text().strip().split(':')
# Test core was added after the earlier host classpath was captured.
cp += [str(next((cache/'8.13/transforms').glob('*/transformed/jetified-core-1.7.0-runtime.jar')))]
cp=[str(classes),str(base/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),str(base/'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar')]+cp
cp=':'.join(dict.fromkeys(cp))
source=root/'app/src/main/java/com/lightningstudio/watchrss/phone';tests=root/'app/src/test/java/com/lightningstudio/watchrss/phone'
inputs=list((source/'data/note').glob('*.kt'))
# Exclude unrelated Android cloud/network services; all repository dependencies remain real.
inputs=[source/'data/note'/s for s in ['NoteConflictIdentity.kt','NoteEntities.kt','NoteDao.kt','NoteRepository.kt','MarkdownNoteCodec.kt','MarkdownThreeWayMerge.kt','NoteCloudStateCodec.kt','MarkdownNoteArchive.kt']]
inputs += [source/'syncv2'/s for s in ['SyncV2NoteConflicts.kt','SyncV2PhoneApply.kt']]
inputs += [tests/'data/note'/s for s in ['NoteMergeReviewTest.kt','NoteMergeR2ReviewTest.kt']]
inputs += [tests/'syncv2/NoteMergeReviewV2Test.kt']
snapshot=a.out/'sources';snapshot.mkdir(exist_ok=True);frozen=[]
for item in inputs:
 target=snapshot/item.name;target.write_bytes(item.read_bytes());frozen.append(target)
(a.out/'sources.json').write_text(json.dumps({str(x.relative_to(root)):hashlib.sha256(x.read_bytes()).hexdigest() for x in inputs},indent=2))
mods=cache/'modules-2/files-2.1'
compiler=next((mods/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10').rglob('*.jar'))
extra=[next((mods/s).rglob('*.jar')) for s in ['org.jetbrains.kotlin/kotlin-stdlib/2.2.10','org.jetbrains.kotlin/kotlin-reflect/2.2.10','org.jetbrains.intellij.deps/trove4j']]
jar=a.out/'review.jar'
cmd=['java','-cp',':'.join(map(str,[compiler,*extra]))+':'+cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-Xfriend-paths='+str(classes),'-classpath',cp,'-d',str(jar),*map(str,frozen)]
r=subprocess.run(cmd,capture_output=True,text=True);(a.out/'compile.log').write_text(r.stdout+r.stderr)
if r.returncode:print(r.stdout+r.stderr);raise SystemExit(r.returncode)
# Offline dependency map: missing SDK artifacts fail instead of using Maven.
sdks=Path.home()/'.m2/repository/org/robolectric/android-all-instrumented';mapping=a.out/'sdk.properties'
mapping.write_text('\n'.join(r'org.robolectric\:android-all-instrumented\:'+x.parent.name+'='+str(x) for x in sdks.rglob('*.jar'))+'\n')
cmd=['java','-Drobolectric.offline=true','-Drobolectric.dependency.repo.url=file:///nonexistent','-Drobolectric-deps.properties='+str(mapping),'-cp',str(jar)+':'+cp,'org.junit.runner.JUnitCore','com.lightningstudio.watchrss.phone.data.note.NoteMergeReviewTest','com.lightningstudio.watchrss.phone.data.note.NoteMergeR2ReviewTest','com.lightningstudio.watchrss.phone.syncv2.NoteMergeReviewV2Test']
(a.out/'runtime-classpath.txt').write_text(str(jar)+':'+cp)
r=subprocess.run(cmd,capture_output=True,text=True,cwd=root);(a.out/'results.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr);raise SystemExit(r.returncode)
