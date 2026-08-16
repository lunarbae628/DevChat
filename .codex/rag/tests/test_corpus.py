import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


RAG_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RAG_DIR))

from corpus import load_active_documents


def run_git(repo: Path, *args: str) -> None:
    subprocess.run(
        ["git", *args],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


class CorpusTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp_dir.name)
        run_git(self.repo, "init")
        run_git(self.repo, "config", "user.name", "RAG Test")
        run_git(self.repo, "config", "user.email", "rag@example.com")
        (self.repo / "AGENTS.md").write_text("# Rules\n", encoding="utf-8")
        (self.repo / "README.md").write_text("# Root\n", encoding="utf-8")
        (self.repo / "draft.md").write_text("# Draft\n", encoding="utf-8")
        (self.repo / "frontend").mkdir()
        (self.repo / "frontend" / "README.md").write_text("# Frontend\n", encoding="utf-8")
        (self.repo / "ai" / "workflow.md").parent.mkdir(parents=True)
        (self.repo / "ai" / "workflow.md").write_text("# Workflow\n", encoding="utf-8")
        (self.repo / "ai" / "logs").mkdir(parents=True)
        (self.repo / "ai" / "logs" / "session.jsonl").write_text("{}\n", encoding="utf-8")
        (self.repo / "ai" / "summaries").mkdir(parents=True)
        (self.repo / "ai" / "summaries" / "old.md").write_text("# Old\n", encoding="utf-8")
        (self.repo / "docs" / "knowledge" / "changes").mkdir(parents=True)
        (self.repo / "docs" / "knowledge" / "changes" / "record.md").write_text(
            "# Record\n", encoding="utf-8"
        )
        (self.repo / "docs" / "superpowers" / "specs").mkdir(parents=True)
        (self.repo / "docs" / "superpowers" / "specs" / "active.md").write_text(
            "# Active\n", encoding="utf-8"
        )
        (self.repo / "docs" / "superpowers" / "specs" / "superseded.md").write_text(
            "> **상태: superseded.**\n", encoding="utf-8"
        )
        (self.repo / "docs" / "superpowers" / "specs" / "draft.md").write_text(
            "> **상태: draft.**\n", encoding="utf-8"
        )
        run_git(
            self.repo,
            "add",
            "AGENTS.md",
            "README.md",
            "draft.md",
            "frontend/README.md",
            "ai/workflow.md",
            "ai/summaries/old.md",
            "docs",
        )
        run_git(self.repo, "commit", "-m", "baseline")
        self.manifest = self.repo / "corpus.json"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_load_active_documents_filters_untracked_and_excluded_paths(self) -> None:
        self.manifest.write_text(
            json.dumps(
                {
                    "include": ["AGENTS.md", "draft.md", "ai/logs/**", "untracked.md"],
                    "exclude": ["draft.md"],
                }
            ),
            encoding="utf-8",
        )

        documents = load_active_documents(self.repo, self.manifest)

        self.assertEqual([document.path for document in documents], ["AGENTS.md"])

    def test_load_active_documents_expands_ranges_and_excludes_non_current_documents(self) -> None:
        self.manifest.write_text(
            json.dumps(
                {
                    "include": [
                        "AGENTS.md",
                        "ai/*.md",
                        "docs/knowledge/**/*.md",
                        "docs/superpowers/specs/**/*.md",
                    ],
                    "exclude": ["ai/summaries/**"],
                }
            ),
            encoding="utf-8",
        )

        documents = load_active_documents(self.repo, self.manifest)

        self.assertEqual(
            [document.path for document in documents],
            [
                "AGENTS.md",
                "ai/workflow.md",
                "docs/knowledge/changes/record.md",
                "docs/superpowers/specs/active.md",
            ],
        )

    def test_load_active_documents_matches_patterns_from_repository_root(self) -> None:
        self.manifest.write_text(
            json.dumps({"include": ["README.md", "ai/*.md"], "exclude": []}),
            encoding="utf-8",
        )

        documents = load_active_documents(self.repo, self.manifest)

        self.assertEqual(
            [document.path for document in documents],
            ["README.md", "ai/workflow.md"],
        )

    def test_load_active_documents_rejects_excluded_directories_and_tracked_symlinks(self) -> None:
        (self.repo / "docs" / "superpowers" / "plans").mkdir(parents=True)
        (self.repo / "docs" / "local").mkdir(parents=True)
        (self.repo / "docs" / "superpowers" / "plans" / "plan.md").write_text("# Plan\n", encoding="utf-8")
        (self.repo / "docs" / "local" / "notes.md").write_text("# Notes\n", encoding="utf-8")
        (self.repo / "untracked.md").write_text("# Untracked\n", encoding="utf-8")
        (self.repo / "linked.md").symlink_to(self.repo / "untracked.md")
        run_git(self.repo, "add", "docs", "linked.md")
        run_git(self.repo, "commit", "-m", "add documents")
        self.manifest.write_text(
            json.dumps(
                {
                    "include": [
                        "AGENTS.md",
                        "docs/superpowers/plans/**/*.md",
                        "docs/local/**/*.md",
                        "linked.md",
                    ],
                    "exclude": [],
                }
            ),
            encoding="utf-8",
        )

        documents = load_active_documents(self.repo, self.manifest)

        self.assertEqual([document.path for document in documents], ["AGENTS.md"])


if __name__ == "__main__":
    unittest.main()
