"""Build the runnable Spring Boot JAR and a source/artifact checksum manifest."""

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
WRAPPER = [os.environ["GRADLE_EXECUTABLE"]] if os.environ.get("GRADLE_EXECUTABLE") else [
    "java", "-cp", "gradle/wrapper/gradle-wrapper.jar",
    "org.gradle.wrapper.GradleWrapperMain",
]


def run(*args, check=True):
    return subprocess.run(
        args, cwd=ROOT, check=check, text=True, encoding="utf-8",
        errors="replace", capture_output=True,
    )


def git_value(*args):
    if not (ROOT / ".git").exists():
        return None
    result = run("git", *args, check=False)
    return result.stdout.strip() if result.returncode == 0 else None


def source_digest():
    tracked = run("git", "ls-files", "-co", "--exclude-standard", "-z", check=False) if (ROOT / ".git").exists() else None
    if tracked is not None and tracked.returncode == 0:
        names = [Path(name) for name in tracked.stdout.split("\0") if name]
    else:
        excluded = {
            ".git", ".gradle", "build", "dist", "data", "logs", "workspace",
            "gradle-project-cache", "gradle-run-home", "jdk17", "__pycache__",
        }
        names = [
            path.relative_to(ROOT) for path in ROOT.rglob("*")
            if path.is_file() and not any(part in excluded for part in path.relative_to(ROOT).parts)
        ]
    digest = hashlib.sha256()
    for name in sorted(names, key=lambda path: path.as_posix()):
        path = ROOT / name
        if path.is_file():
            digest.update(name.as_posix().encode("utf-8") + b"\0")
            digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def main():
    commit = git_value("rev-parse", "HEAD") or os.environ.get("SOURCE_COMMIT")
    commit_tree = git_value("rev-parse", "HEAD^{tree}") or os.environ.get("SOURCE_TREE")
    status = git_value("status", "--porcelain", "--untracked-files=normal")
    dirty = bool(status) if status is not None else (
        os.environ.get("SOURCE_DIRTY", "").lower() == "true" if commit else None
    )
    tree_digest = source_digest()

    boundary = run(sys.executable, "scripts/check_boundaries.py", check=False)
    print(boundary.stdout, end="")
    print(boundary.stderr, end="", file=sys.stderr)
    if boundary.returncode:
        return boundary.returncode

    command = [*WRAPPER, "--no-daemon", "--console=plain"]
    if os.environ.get("GRADLE_OFFLINE") == "true":
        command.append("--offline")
    command.append("build")
    print("+ Gradle build", flush=True)
    result = run(*command, check=False)
    if result.returncode:
        print(result.stdout[-20000:], file=sys.stderr)
        print(result.stderr[-20000:], file=sys.stderr)
        return result.returncode
    print("Gradle build successful")

    jars = [path for path in (ROOT / "build" / "libs").glob("*.jar") if not path.name.endswith("-plain.jar")]
    if len(jars) != 1:
        print(f"Expected one runnable Spring Boot JAR, found {jars}", file=sys.stderr)
        return 1
    DIST.mkdir(exist_ok=True)
    target = DIST / jars[0].name
    shutil.copy2(jars[0], target)

    java_version = run("java", "-version").stderr.splitlines()[0]
    wrapper_properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    gradle_match = re.search(r"gradle-([0-9.]+)-bin\.zip", wrapper_properties)
    manifest = {
        "schemaVersion": 1,
        "project": "cc-agent-java",
        "builtAtUtc": datetime.now(timezone.utc).isoformat(),
        "source": {
            "commit": commit,
            "commitTree": commit_tree,
            "dirty": dirty,
            "workingTreeSha256": tree_digest,
        },
        "toolchain": {"java": java_version, "gradle": gradle_match.group(1) if gradle_match else None},
        "artifacts": [{
            "file": target.name,
            "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "sizeBytes": target.stat().st_size,
        }],
    }
    (DIST / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Created {target} and manifest.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
