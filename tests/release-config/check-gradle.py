"""Run the release production settings gate using only synthetic Gradle inputs."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile


PROJECT = Path(__file__).resolve().parents[2]
URL = "WATCHRSS_BACKEND_BASE_URL"
KEY = "WATCHRSS_SUPABASE_ANON_KEY"
VALID_URL = "https://example.invalid/test"
VALID_KEY = "synthetic-test-key"


def check(project, label, local, gradle, environment, invalid, task="preReleaseBuild"):
    (project / "local.properties").write_text(
        "".join(f"{name}={value}\n" for name, value in local.items()), encoding="ascii"
    )
    env = os.environ.copy()
    for name in (URL, KEY):
        env.pop(name, None)
        env.pop(f"ORG_GRADLE_PROJECT_{name}", None)
    env.update(environment)
    result = subprocess.run(
        [
            str(PROJECT / "gradlew"), "--project-dir", str(project), "--offline",
            "--console=plain", "--max-workers=2",
            *(f"-P{name}={gradle.get(name, '')}" for name in (URL, KEY)),
            f":app:{task}",
        ],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    if invalid:
        expected = "> " + ", ".join(invalid)
        passed = (
            result.returncode != 0
            and "> Task :app:validateReleaseProductionSettings FAILED" in result.stdout
            and expected in result.stdout.splitlines()
        )
    else:
        release_gate_ran = "> Task :app:validateReleaseProductionSettings" in result.stdout
        passed = result.returncode == 0 and release_gate_ran == (task == "preReleaseBuild")
    if not passed:
        raise RuntimeError(f"{label}: unexpected Gradle result\n{result.stdout}")
    print(f"{label}: PASS", flush=True)


def main():
    with tempfile.TemporaryDirectory(prefix="watchrss-release-config-") as temp:
        project = Path(temp)
        (project / "app").mkdir()
        for relative in (
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
            "app/build.gradle.kts",
        ):
            shutil.copyfile(PROJECT / relative, project / relative)
        (project / "gradle").mkdir()
        shutil.copyfile(PROJECT / "gradle/libs.versions.toml", project / "gradle/libs.versions.toml")

        check(project, "missing settings", {}, {}, {}, [URL, KEY])
        check(project, "blank settings", {URL: " ", KEY: " "}, {}, {}, [URL, KEY])
        for malformed in ("http://example.invalid", "https:///path", "https://bad host", "https://example.invalid:99999"):
            check(project, f"invalid URL ({malformed})", {URL: malformed, KEY: VALID_KEY}, {}, {}, [URL])
        check(project, "missing auth setting", {URL: VALID_URL}, {}, {}, [KEY])
        check(
            project, "blank local falls back to Gradle, blank Gradle falls back to env",
            {URL: " ", KEY: " "}, {URL: VALID_URL, KEY: " "},
            {URL: "invalid", KEY: VALID_KEY}, [],
        )
        check(
            project, "nonblank local has priority", {URL: VALID_URL, KEY: VALID_KEY},
            {URL: "invalid"}, {URL: "invalid"}, [],
        )
        check(project, "debug accepts missing settings", {}, {}, {}, [], "preDebugBuild")


if __name__ == "__main__":
    main()
