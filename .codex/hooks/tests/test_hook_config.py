import json
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]


def load_config():
    return json.loads((REPO_ROOT / ".codex" / "hooks.json").read_text(encoding="utf-8"))


class HookConfigTest(unittest.TestCase):
    def test_registers_required_events(self):
        config = load_config()

        self.assertEqual(
            set(config["hooks"]),
            {
                "UserPromptSubmit",
                "SubagentStart",
                "SubagentStop",
                "PermissionRequest",
                "PreToolUse",
                "PostToolUse",
                "Stop",
            },
        )

    def test_tool_hooks_keep_logging_and_register_separate_guard_handler(self):
        config = load_config()

        self.assertEqual(
            config["hooks"]["PreToolUse"][0]["matcher"],
            "^(Bash|apply_patch|Edit|Write)$",
        )
        self.assertEqual(
            config["hooks"]["PreToolUse"][1]["matcher"],
            "^(Bash|apply_patch|Edit|Write)$",
        )
        commands = [
            handler["command"]
            for group in config["hooks"]["PreToolUse"]
            for handler in group["hooks"]
        ]
        self.assertTrue(any("log_ai_event.py" in command for command in commands))
        self.assertTrue(any("guard_pre_tool_use.py" in command for command in commands))
        self.assertEqual(
            config["hooks"]["PostToolUse"][0]["matcher"],
            "^(Bash|apply_patch|Edit|Write)$",
        )
        self.assertEqual(
            config["hooks"]["PermissionRequest"][0]["matcher"],
            "^(Bash|apply_patch|Edit|Write)$",
        )

    def test_user_prompt_submit_keeps_logging_and_uses_optional_rag_runtime_wrapper(self):
        groups = load_config()["hooks"]["UserPromptSubmit"]
        commands = [
            handler["command"]
            for group in groups
            for handler in group["hooks"]
        ]

        self.assertTrue(any("log_ai_event.py" in command for command in commands))
        self.assertTrue(any("/usr/bin/python3" in command for command in commands))
        self.assertTrue(any("run_user_prompt_rag.py" in command for command in commands))

    def test_does_not_add_model_context(self):
        serialized = json.dumps(load_config())

        self.assertNotIn("additionalContext", serialized)
        self.assertNotIn("additionalContextLimit", serialized)

    def test_stop_hook_uses_knowledge_record_check_without_summary_generation(self):
        commands = [
            handler["command"]
            for group in load_config()["hooks"]["Stop"]
            for handler in group["hooks"]
        ]

        self.assertTrue(any("check_knowledge_record.py" in command for command in commands))
        self.assertFalse(any("summarize" in command for command in commands))


if __name__ == "__main__":
    unittest.main()
