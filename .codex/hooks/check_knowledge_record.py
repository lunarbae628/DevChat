"""Ask once whether the current session needs a knowledge record."""

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, Optional, Set

from hook_common import find_repo_root, git_snapshot, stable_json
from log_ai_event import safe_session_filename


STATE_SUFFIX = ".knowledge-record-check.json"


def _baseline(repo_root: Path, session_id: str) -> Optional[Dict[str, Any]]:
    path = repo_root / "ai" / "logs" / safe_session_filename(session_id)
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(record, dict) and record.get("event") == "SessionBaseline":
            return record
    return None


def _committed_paths(repo_root: Path, base_commit: Any) -> Set[str]:
    if not isinstance(base_commit, str) or not base_commit:
        return set()
    completed = subprocess.run(
        ["git", "diff", "--name-only", base_commit, "HEAD"],
        cwd=repo_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        return set()
    return {path for path in completed.stdout.splitlines() if path}


def _session_changed_paths(repo_root: Path, baseline: Dict[str, Any]) -> Set[str]:
    initial_dirty = baseline.get("initial_dirty_files", [])
    initial_paths = set(initial_dirty) if isinstance(initial_dirty, list) else set()
    current_paths = set(git_snapshot(repo_root)["dirty_files"])
    return (current_paths - initial_paths) | _committed_paths(repo_root, baseline.get("base_commit"))


def _is_code_change(path: str) -> bool:
    return not (
        path.endswith(".DS_Store")
        or path.startswith("docs/")
        or path.startswith("ai/logs/")
        or path.startswith("ai/summaries/")
        or path.startswith("_workspace/")
    )


def _has_new_knowledge_record(paths: Set[str]) -> bool:
    return any(
        path.startswith("docs/knowledge/changes/") and path.endswith(".md") for path in paths
    )


def _state_path(repo_root: Path, session_id: str) -> Path:
    return repo_root / "ai" / "logs" / f"{safe_session_filename(session_id)}{STATE_SUFFIX}"


def _mark_prompted(path: Path) -> bool:
    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        return False
    with os.fdopen(descriptor, "w", encoding="utf-8") as state_file:
        state_file.write(stable_json({"knowledge_record_check": "prompted"}) + "\n")
    return True


def run(payload: Dict[str, Any]) -> Optional[Dict[str, str]]:
    if bool(payload.get("stop_hook_active")):
        return None
    session_id = str(payload.get("session_id", "unknown"))
    repo_root = find_repo_root(str(payload.get("cwd") or Path.cwd()))
    baseline = _baseline(repo_root, session_id)
    if baseline is None:
        return None
    changed_paths = _session_changed_paths(repo_root, baseline)
    if not any(_is_code_change(path) for path in changed_paths):
        return None
    if _has_new_knowledge_record(changed_paths):
        return None
    if not _mark_prompted(_state_path(repo_root, session_id)):
        return None
    return {
        "decision": "block",
        "reason": (
            "이번 작업에서 코드 변경이 있습니다. 사용자에게 "
            "docs/knowledge/changes 지식 기록을 만들지, 작은 작업으로 면제할지 물어본 뒤 "
            "응답을 기다리세요. 자동으로 문서를 만들지 마세요."
        ),
    }


def main() -> int:
    try:
        payload = json.load(sys.stdin)
        if not isinstance(payload, dict):
            raise ValueError("payload must be an object")
        output = run(payload)
        if output is not None:
            print(stable_json(output))
        return 0
    except (OSError, ValueError, json.JSONDecodeError, subprocess.SubprocessError) as error:
        print(f"knowledge record check failed: {type(error).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
