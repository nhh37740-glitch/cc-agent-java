"""Check Java module direction and sole source ownership of shared contracts."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "agent-contracts" / "src" / "main" / "java"
APPLICATION = ROOT / "src" / "main" / "java"
FORBIDDEN = re.compile(
    r"^import\s+(?:org\.springframework|jakarta\.persistence|"
    r"com\.example\.ccagent\.(?:controller|service|repository|config))",
    re.MULTILINE,
)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
DECLARATION = re.compile(
    r"^\s*(?:(?:public|protected|private|abstract|static|final|sealed|non-sealed)\s+)*"
    r"(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\b",
    re.MULTILINE,
)
SHARED_TYPES = {
    "com.example.ccagent.model.ChatRequest": Path("com/example/ccagent/model/ChatRequest.java"),
    "com.example.ccagent.model.ChatResponse": Path("com/example/ccagent/model/ChatResponse.java"),
    "com.example.ccagent.model.Compressor": Path("com/example/ccagent/model/Compressor.java"),
    "com.example.ccagent.model.ConversationSummary": Path("com/example/ccagent/model/ConversationSummary.java"),
    "com.example.ccagent.model.Message": Path("com/example/ccagent/model/Message.java"),
    "com.example.ccagent.model.SessionJson": Path("com/example/ccagent/model/SessionJson.java"),
    "com.example.ccagent.model.ToolCall": Path("com/example/ccagent/model/ToolCall.java"),
    "com.example.ccagent.model.ToolResult": Path("com/example/ccagent/model/ToolResult.java"),
    "com.example.ccagent.tool.AgentTool": Path("com/example/ccagent/tool/AgentTool.java"),
}

violations = []
contract_owners = {name: [] for name in SHARED_TYPES}
application_owners = {name: [] for name in SHARED_TYPES}

for source_root, owners in ((CONTRACTS, contract_owners), (APPLICATION, application_owners)):
    if not source_root.is_dir():
        violations.append(f"Missing Java source root: {source_root.relative_to(ROOT)}")
        continue
    for source in source_root.rglob("*.java"):
        content = source.read_text(encoding="utf-8")
        if source_root == CONTRACTS and FORBIDDEN.search(content):
            violations.append(f"Contracts import an application/framework dependency: {source.relative_to(ROOT)}")
        package = PACKAGE.search(content)
        if not package:
            continue
        for declaration in DECLARATION.finditer(content):
            fqcn = f"{package.group(1)}.{declaration.group(1)}"
            if fqcn in owners:
                owners[fqcn].append(source.resolve())

for fqcn, relative_path in SHARED_TYPES.items():
    expected = (CONTRACTS / relative_path).resolve()
    actual_contracts = contract_owners[fqcn]
    actual_application = application_owners[fqcn]
    if actual_contracts != [expected]:
        found = ", ".join(str(path.relative_to(ROOT)) for path in actual_contracts) or "none"
        violations.append(f"Expected sole contracts owner for {fqcn} at {expected.relative_to(ROOT)}; found {found}")
    if actual_application:
        found = ", ".join(str(path.relative_to(ROOT)) for path in actual_application)
        violations.append(f"Shared type {fqcn} is duplicated in the application source set: {found}")

settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
build = (ROOT / "build.gradle").read_text(encoding="utf-8")
if not re.search(r"include\s+['\"]agent-contracts['\"]", settings):
    violations.append("settings.gradle does not include agent-contracts")
if not re.search(r"implementation\s+project\(['\"]:agent-contracts['\"]\)", build):
    violations.append("Root application does not depend on agent-contracts")

if violations:
    print("Java module boundary violations:\n" + "\n".join(violations), file=sys.stderr)
    sys.exit(1)
print("Java contract ownership and dependency boundary: OK")
