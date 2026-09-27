"""Verify that the contracts module stays independent of the application."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "agent-contracts" / "src" / "main" / "java"
FORBIDDEN = re.compile(
    r"^import\s+(?:org\.springframework|jakarta\.persistence|"
    r"com\.example\.ccagent\.(?:controller|service|repository|config))",
    re.MULTILINE,
)

violations = []
for source in CONTRACTS.rglob("*.java"):
    if FORBIDDEN.search(source.read_text(encoding="utf-8")):
        violations.append(str(source.relative_to(ROOT)))
if "implementation project(':agent-contracts')" not in (ROOT / "build.gradle").read_text(encoding="utf-8"):
    violations.append("Root application does not depend on agent-contracts")

if violations:
    print("Contract boundary violations:\n" + "\n".join(violations), file=sys.stderr)
    sys.exit(1)
print("Java contracts dependency boundary: OK")
