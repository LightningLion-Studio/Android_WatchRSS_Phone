"""Compare actual Kotlin titles, editor modes and source deletion merge outcomes."""
import argparse
import base64
import json
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--repo", type=Path, default=Path.cwd())
p.add_argument("--parent", default="HEAD^")
p.add_argument("--candidate", default="HEAD")
p.add_argument("--title-reference", help="Revision before native editor storage")
p.add_argument("--merge-reference", help="Revision before the source deletion merge regression")
p.add_argument("--legacy-probe", help="r4 revision containing the original adjacent-merge probe (defaults to --parent^)")
p.add_argument("--adjacent-only", action="store_true", help="Run the 121 source deletion merge cases only")
p.add_argument("--classpath", type=Path, required=True)
p.add_argument("--gradle-cache", type=Path, required=True)
p.add_argument("--out", type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True)
repo = a.repo.resolve()
cache = a.gradle_cache.resolve()
shared_cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
project_root = "app/src/main/java/com/lightningstudio/watchrss/phone/"

def git(*args):
    return subprocess.check_output(["git", "-C", str(repo), *args])

def commit(ref):
    return git("rev-parse", ref).decode().strip()

def jar(rel):
    jars = sorted((cache / rel).rglob("*.jar")) or sorted((shared_cache / rel).rglob("*.jar"))
    return next(j for j in jars if not j.name.endswith(("-sources.jar", "-javadoc.jar")))

entries = []
for raw in a.classpath.read_text().strip().split(":"):
    path = Path(raw)
    if path.is_relative_to(shared_cache):
        alternate = cache / path.relative_to(shared_cache)
        if alternate.is_file():
            path = alternate
    entries.append(str(path))
cp = ":".join(entries)
compiler_paths = [jar(rel) for rel in [
    "org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10",
    "org.jetbrains.kotlin/kotlin-stdlib/2.2.10",
    "org.jetbrains.kotlin/kotlin-reflect/2.2.10",
    "org.jetbrains.intellij.deps/trove4j",
]]
compiler_cp = ":".join([*map(str, compiler_paths), cp])

def probe_source(name, historical):
    source = Path(__file__).with_name(f"{name}.kt")
    if not historical:
        return source.read_text()
    revision = (a.legacy_probe or a.parent + "^") if name == "AdjacentMergeProbe" else a.parent
    return git("show", f"{revision}:tests/notes-editor-r3/{source.name}").decode()


def compile_and_run(label, revision, main, extra_args=(), historical=False):
    out = a.out / label
    out.mkdir()
    editor_probe = label != "upstream"
    files = ["data/note/" + name for name in ["MarkdownNoteCodec.kt", "NoteEntities.kt", "NoteTitle.kt"]]
    if editor_probe:
        files += ["data/note/NoteEditorStorage.kt", "data/note/MarkdownThreeWayMerge.kt",
                  "NoteEditorDraft.kt", "NoteVisualDocument.kt"]
    for name in files:
        (out / Path(name).name).write_bytes(git("show", revision + ":" + project_root + name))
    if editor_probe:
        editor = git("show", revision + ":" + project_root + "NoteRichTextEditor.kt").decode()
        # Compile the exact plain helper slices from each revision without Android UI code.
        slices = [
            editor[editor.index("internal fun RichTextState.insertImagePlaceholder("):
                   editor.index("@Composable\nprivate fun TableInsertDialog")],
            editor[editor.index("internal fun RichTextState.toNoteStorageMarkup("):
                   editor.index("internal fun parseHexColor(")],
        ]
        (out / "EditorHelpers.kt").write_text("""package com.lightningstudio.watchrss.phone
import com.mohamedrejeb.richeditor.model.RichTextState
import com.lightningstudio.watchrss.phone.data.note.NoteEditorStorage
import androidx.compose.ui.text.TextRange
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.roundToInt
""" + "\n".join(slices).removesuffix("@Composable\n"))
        probe = probe_source(main, historical)
        if label == "candidate" and main != "AdjacentMergeProbe":
            (out / "AdjacentMergeProbe.kt").write_text(probe_source("AdjacentMergeProbe", historical))
    else:
        probe = """package com.lightningstudio.watchrss.phone
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import com.lightningstudio.watchrss.phone.data.note.deriveNoteTitle
import java.io.File
import java.util.Base64
fun main(args: Array<String>) {
    val encoder = Base64.getEncoder()
    val decoder = Base64.getDecoder()
    fun utf8(s: String) = encoder.encodeToString(s.toByteArray(Charsets.UTF_8))
    for (row in File(args.single()).readLines()) {
        val (id, encodedMarkup) = row.split('\t')
        val markup = String(decoder.decode(encodedMarkup), Charsets.UTF_8)
        val projection = MarkdownNoteCodec.titlePlainText(markup)
        println(listOf(id, utf8(projection), utf8(deriveNoteTitle(projection))).joinToString("\t"))
    }
}
"""
    (out / f"{main}.kt").write_text(probe)
    if label == "merge-reference":
        (out / "MergeReplayProbe.kt").write_text(probe_source("MergeReplayProbe", historical=False))
    target = out / "probe.jar"
    compile_cmd = ["java", "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", "-classpath", cp, "-d", str(target),
                   *map(str, sorted(out.glob("*.kt")))]
    with (out / "compile.log").open("w") as log:
        subprocess.run(compile_cmd, stdout=log, stderr=subprocess.STDOUT, check=True)
    return run_probe(out, main, "out.tsv", extra_args)


def run_probe(out, main, filename, extra_args=()):
    cmd = ["java", "-cp", str(out / "probe.jar") + ":" + cp,
           "com.lightningstudio.watchrss.phone." + main + "Kt", *map(str, extra_args)]
    results = out / filename
    with results.open("wb") as output, (out / f"{filename}.log").open("w") as log:
        subprocess.run(cmd, stdout=output, stderr=log, check=True)
    return results


def verify_adjacent(old_ref, new_ref, merge_ref):
    def records(path):
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        keyed = {row["case"]: row for row in rows}
        assert len(rows) == len(keyed) == 121
        return keyed

    reference_path = compile_and_run("merge-reference", merge_ref, "AdjacentMergeProbe", historical=True)
    fixtures_path = run_probe(a.out / "merge-reference", "MergeReplayProbe", "source-fixtures.jsonl",
                              (reference_path, "source-fixtures"))
    reference = records(fixtures_path)
    folder = a.out / "candidate"
    after = records(run_probe(folder, "AdjacentMergeProbe", "adjacent.jsonl", (fixtures_path,))
                    if (folder / "probe.jar").exists()
                    else compile_and_run("candidate", new_ref, "AdjacentMergeProbe", (fixtures_path,)))
    assert reference.keys() == after.keys()

    replay = a.out / "r2-replay-inputs.jsonl"
    with replay.open("w") as stream:
        for key, row in reference.items():
            stream.write(json.dumps(dict(case=key, base=row["baseDecoded"], local=after[key]["localSaved"],
                                         remote=after[key]["remoteSaved"]), ensure_ascii=False) + "\n")
    live_reference = records(run_probe(a.out / "merge-reference", "MergeReplayProbe", "live-reference.jsonl", (replay,)))

    errors = []
    live_changes = []
    for key, expected in reference.items():
        fixed = after[key]
        for field in ("outcome", "mergedMarkdown"):
            if fixed[field] != expected[field]:
                errors.append(f"{key}: {field} differs for the exact r2 merge inputs")
        oracle = live_reference[key]
        if (fixed["liveOutcome"], fixed["liveMergedMarkdown"]) != (oracle["outcome"], oracle["mergedMarkdown"]):
            errors.append(f"{key}: r2 and candidate differ on the same newly saved body bytes")
        if fixed["liveOutcome"] != expected["outcome"]:
            live_changes.append(key)
            if fixed["liveOutcome"] != "merged":
                errors.append(f"{key}: live source edit lost an automatic merge")
        if fixed["localSaved"] != expected["localDecoded"] or fixed["remoteSaved"] != expected["remoteDecoded"]:
            errors.append(f"{key}: editor changed bytes in the user-edited text")

    result = dict(cases=len(reference), mergeReference=merge_ref, parent=old_ref, candidate=new_ref,
                  referenceMerged=sum(r["outcome"] == "merged" for r in reference.values()),
                  candidateMerged=sum(r["outcome"] == "merged" for r in after.values()),
                  liveMerged=sum(r["liveOutcome"] == "merged" for r in after.values()),
                  liveOutcomeChanges=live_changes, errors=errors)
    (a.out / "merge-summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({**result, "errors": len(errors)}, ensure_ascii=False))
    assert not errors


def read_rows(path, fields):
    records = {}
    for line in path.read_bytes().splitlines():
        key, *row = line.split(b"\t")
        assert len(row) == fields and key not in records, line
        records[key.decode()] = row
    return records

old_ref = commit(a.parent)
new_ref = commit(a.candidate)
if a.adjacent_only:
    if a.merge_reference is None:
        p.error("--adjacent-only requires --merge-reference")
    verify_adjacent(old_ref, new_ref, commit(a.merge_reference))
    raise SystemExit(0)

upstream_ref = commit(a.title_reference or old_ref + "^")
old = read_rows(compile_and_run("parent", old_ref, "DifferentialProbe", historical=True), 5)
new = read_rows(compile_and_run("candidate", new_ref, "DifferentialProbe"), 5)
assert old.keys() == new.keys()
markup = a.out / "upstream-input.tsv"
with markup.open("wb") as f:
    for key, row in old.items():
        f.write(key.encode() + b"\t" + row[0] + b"\n")
expected = read_rows(compile_and_run("upstream", upstream_ref, "TitleReference", (markup,)), 2)
assert expected.keys() == old.keys()

changed_titles = []
changed_modes = []
errors = []
for key in old:
    raw, projected, title, mode, wanted = new[key]
    before = old[key]
    if raw != before[0] or wanted != before[4]:
        errors.append(key + ": generated inputs changed")
    # Compare UTF-8 bytes, not normalized strings or display glyphs.
    actual = tuple(map(base64.b64decode, (projected, title)))
    reference = tuple(map(base64.b64decode, expected[key]))
    if actual != reference:
        errors.append(key + ": title projection does not match upstream")
    if (projected, title) != tuple(before[1:3]):
        changed_titles.append(key)
        if (before[1], before[2]) == tuple(expected[key]):
            errors.append(key + ": previously correct title changed")
    if mode != before[3]:
        changed_modes.append(key)
        errors.append(key + ": unexpected mode change")
    if wanted != b"-" and mode != wanted:
        errors.append(key + ": mode did not survive reopening")

def count(prefix):
    return sum(k.startswith(prefix) for k in new)

result = dict(parent=old_ref, candidate=new_ref, upstreamTitleOracle=upstream_ref,
              cases=len(new), reopenChecks=count("edit/"), mergeChecks=count("merge/"),
              changedTitles=changed_titles, changedModes=changed_modes, errors=errors)
(a.out / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))
assert not errors

if a.merge_reference:
    verify_adjacent(old_ref, new_ref, commit(a.merge_reference))
