"""验证项目技能接入的可发现性、更新检查和源文件保护。"""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "sync-jeecg-skills.py"


class SkillAdapterTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.root = Path(self.workspace.name)
        self.source = self.root / ".claude" / "skills"
        self.add_skill("jeecg-aiflow", "jeecg-aiflow")
        self.add_skill("jeecg-dev", "jeecg-dev")
        self.add_skill("jimubi-dashboard", "jimubi-dashboard", "skill.md")
        self.add_skill("jeecg-codegen/uniapp", "jeecg-uniapp-codegen")
        resource = self.source / "jeecg-aiflow/scripts/gen_node_options_index.py"
        resource.parent.mkdir()
        resource.write_text("print('节点选项索引')\n", encoding="utf-8")

    def add_skill(self, directory, name, filename="SKILL.md"):
        folder = self.source / directory
        folder.mkdir(parents=True, exist_ok=True)
        (folder / filename).write_text(
            f"---\nname: {name}\ndescription: Use when testing {name}.\n---\n\n# 原文\n",
            encoding="utf-8",
        )

    def run_sync(self, *arguments):
        self.assertTrue(SCRIPT.is_file(), "缺少项目技能同步脚本")
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--repo-root", str(self.root), *arguments],
            capture_output=True, text=True, encoding="utf-8",
        )

    def snapshot(self, folder):
        return {
            p.relative_to(folder).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in folder.rglob("*") if p.is_file()
        }

    def test_install_exposes_lowercase_and_nested_entrypoints(self):
        result = self.run_sync()
        self.assertEqual(0, result.returncode, result.stderr)
        adapters = self.root / ".agents" / "skills"
        self.assertEqual(
            {"jeecg-aiflow", "jeecg-dev", "jimubi-dashboard", "jeecg-uniapp-codegen"},
            {p.parent.name for p in adapters.glob("*/SKILL.md")},
        )
        manifest = json.loads((self.root / ".agents/jeecg-skills.json").read_text(encoding="utf-8"))
        for skill in manifest["skills"]:
            source_path = self.root / skill["source"]
            self.assertTrue(source_path.is_file())
            adapter = adapters / skill["name"] / "SKILL.md"
            self.assertIn(skill["source"], adapter.read_text(encoding="utf-8"))

    def test_install_keeps_every_source_file_unchanged(self):
        before = self.snapshot(self.source)
        result = self.run_sync()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, self.snapshot(self.source))

    def test_repeat_install_is_idempotent_and_check_is_read_only(self):
        self.assertEqual(0, self.run_sync().returncode)
        before = self.snapshot(self.root / ".agents")
        self.assertEqual(0, self.run_sync().returncode)
        self.assertEqual(0, self.run_sync("--check").returncode)
        self.assertEqual(before, self.snapshot(self.root / ".agents"))

    def test_source_update_requires_sync_without_changing_sources(self):
        self.assertEqual(0, self.run_sync().returncode)
        source_file = self.source / "jeecg-aiflow/SKILL.md"
        source_file.write_text(source_file.read_text(encoding="utf-8") + "新增原文\n", encoding="utf-8")
        before = self.snapshot(self.source)
        self.assertNotEqual(0, self.run_sync("--check").returncode)
        self.assertEqual(0, self.run_sync().returncode)
        self.assertEqual(0, self.run_sync("--check").returncode)
        self.assertEqual(before, self.snapshot(self.source))

    def test_manually_changed_adapter_is_not_overwritten(self):
        self.assertEqual(0, self.run_sync().returncode)
        adapter = self.root / ".agents/skills/jeecg-dev/SKILL.md"
        adapter.write_text("用户修改的适配入口\n", encoding="utf-8")
        self.assertNotEqual(0, self.run_sync("--check").returncode)
        self.assertNotEqual(0, self.run_sync().returncode)
        self.assertEqual("用户修改的适配入口\n", adapter.read_text(encoding="utf-8"))

    def test_duplicate_skill_names_fail_before_install(self):
        self.add_skill("another-aiflow", "jeecg-aiflow")
        result = self.run_sync()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("重复", result.stderr)
        self.assertFalse((self.root / ".agents").exists())

    def test_development_skill_retains_explicit_only_policy(self):
        self.assertEqual(0, self.run_sync().returncode)
        policy = self.root / ".agents/skills/jeecg-dev/agents/openai.yaml"
        self.assertIn("allow_implicit_invocation: false", policy.read_text(encoding="utf-8"))

    def test_git_checkout_keeps_generated_files_in_sync_with_autocrlf(self):
        self.assertEqual(0, self.run_sync().returncode)
        git_commands = [
            ["init", "-q"],
            ["config", "core.autocrlf", "true"],
            ["config", "core.hooksPath", str(self.root / "empty-hooks")],
            ["add", "--", ".agents"],
            ["-c", "user.name=Skill Adapter Test", "-c", "user.email=skill-test@example.invalid",
             "-c", "commit.gpgsign=false", "commit", "-qm", "test fixture"],
        ]
        for command in git_commands:
            result = subprocess.run(
                ["git", "-C", str(self.root), *command], capture_output=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
        adapter = self.root / ".agents/skills/jeecg-dev/SKILL.md"
        adapter.unlink()
        result = subprocess.run(
            ["git", "-C", str(self.root), "restore", "--", ".agents/skills/jeecg-dev/SKILL.md"],
            capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn(b"\r\n", adapter.read_bytes())
        result = self.run_sync("--check")
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
