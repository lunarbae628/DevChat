"""Validated allowlist loading for the DevChat RAG corpus."""

import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, List, Sequence, Tuple


@dataclass(frozen=True)
class CorpusDocument:
    path: str
    status: str


def _is_tracked(repo_root: Path, path: str) -> bool:
    completed = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "--", path],
        cwd=repo_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.returncode == 0


NON_CURRENT_STATUS_PATTERN = re.compile(
    r"(?:^|\n)\s*(?:>\s*)?(?:\*\*)?상태\s*:\s*(?:draft|superseded)\b",
    re.IGNORECASE,
)


def _safe_tracked_markdown(repo_root: Path, path: Path) -> bool:
    if path.is_absolute() or ".." in path.parts or path.suffix != ".md":
        return False
    if path.parts[:2] in (("ai", "logs"), ("docs", "local")) or path.parts[:3] == (
        "docs",
        "superpowers",
        "plans",
    ):
        return False

    candidate = repo_root / path
    if candidate.is_symlink():
        return False

    try:
        resolved = candidate.resolve()
        resolved.relative_to(repo_root.resolve())
    except ValueError:
        return False

    normalized = path.as_posix()
    if not resolved.is_file() or not _is_tracked(repo_root, normalized):
        return False
    return not NON_CURRENT_STATUS_PATTERN.search(candidate.read_text(encoding="utf-8"))


def _patterns(payload: Any) -> Tuple[Sequence[str], Sequence[str]]:
    if not isinstance(payload, dict):
        raise ValueError("invalid corpus manifest")
    include = payload.get("include")
    exclude = payload.get("exclude", [])
    if (
        not isinstance(include, list)
        or not include
        or not isinstance(exclude, list)
        or any(not isinstance(pattern, str) or not pattern for pattern in include + exclude)
    ):
        raise ValueError("invalid corpus manifest")
    return include, exclude


def _tracked_markdown_paths(repo_root: Path) -> List[str]:
    completed = subprocess.run(
        ["git", "ls-files", "-z", "--", "*.md"],
        cwd=repo_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        return []
    return sorted(path for path in completed.stdout.split("\0") if path)


def _matches(path: str, patterns: Sequence[str]) -> bool:
    return any(re.fullmatch(_glob_pattern(pattern), path) for pattern in patterns)


def _glob_pattern(pattern: str) -> str:
    expression = []
    index = 0
    while index < len(pattern):
        character = pattern[index]
        if character == "*" and pattern[index : index + 2] == "**":
            if pattern[index + 2 : index + 3] == "/":
                expression.append("(?:.*/)?")
                index += 3
            else:
                expression.append(".*")
                index += 2
        elif character == "*":
            expression.append("[^/]*")
            index += 1
        elif character == "?":
            expression.append("[^/]")
            index += 1
        else:
            expression.append(re.escape(character))
            index += 1
    return "".join(expression)


def load_active_documents(repo_root: Path, manifest_path: Path) -> List[CorpusDocument]:
    """Return safe, tracked Markdown documents selected by manifest ranges."""
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    include, exclude = _patterns(payload)
    return [
        CorpusDocument(path=path, status="active")
        for path in _tracked_markdown_paths(repo_root)
        if _matches(path, include)
        and not _matches(path, exclude)
        and _safe_tracked_markdown(repo_root, Path(path))
    ]
