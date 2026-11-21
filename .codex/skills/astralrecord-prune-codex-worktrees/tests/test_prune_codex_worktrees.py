import itertools
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "prune_codex_worktrees.py"
ACTIVITY_FILES = ("HEAD", "index", "logs/HEAD", "ORIG_HEAD", "FETCH_HEAD")


class WorktreeCleanupTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="worktree-cleanup-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.repo = self.root / "AstralRecord Workspace"
        self.owned = self.root / "AstralRecord-Worktrees"
        self.claude = self.repo / ".claude" / "worktrees"
        self.codex_app = self.root / "Codex app worktrees"
        for directory in (self.repo, self.owned, self.claude, self.codex_app):
            directory.mkdir(parents=True, exist_ok=True)

        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update(
            GIT_CONFIG_NOSYSTEM="1",
            GIT_CONFIG_GLOBAL=os.devnull,
            GIT_AUTHOR_NAME="Worktree Test",
            GIT_AUTHOR_EMAIL="worktree-test@example.invalid",
            GIT_COMMITTER_NAME="Worktree Test",
            GIT_COMMITTER_EMAIL="worktree-test@example.invalid",
            PYTHONIOENCODING="utf-8",
        )
        self.git("init", "--initial-branch=develop")
        (self.repo / ".gitignore").write_text(
            ".claude/worktrees/\n.codex/skills/\n*.ignored\n", encoding="utf-8"
        )
        (self.repo / "tracked").mkdir()
        (self.repo / "tracked" / "sample.txt").write_text("initial\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-m", "Initial")
        self.base = self.git("rev-parse", "HEAD").strip()
        self.git("commit", "--allow-empty", "-m", "Integrated work")
        self.tip = self.git("rev-parse", "HEAD").strip()

    def git(self, *args, cwd=None):
        proc = subprocess.run(
            ["git", *args], cwd=cwd or self.repo, env=self.env,
            encoding="utf-8", capture_output=True,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc.stdout

    def run_cleanup(self, *args, cwd=None, script=SCRIPT, explicit_roots=True):
        command = [sys.executable, script]
        if explicit_roots:
            command += [
                "--repo", self.repo,
                "--worktree-root", self.owned,
                "--claude-root", self.claude,
            ]
        command += ["--codex-app-root", self.codex_app, *args]
        return subprocess.run(
            command, cwd=cwd or self.root, env=self.env,
            encoding="utf-8", capture_output=True,
        )

    def add_worktree(self, path, branch=None, commit=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        checkout = ["-b", branch] if branch else ["--detach"]
        self.git("worktree", "add", *checkout, path, commit or self.tip)
        return path

    def gitdir(self, path):
        marker = (path / ".git").read_text(encoding="utf-8")
        return path / marker.removeprefix("gitdir: ").rstrip("\r\n")

    def set_idle_days(self, path, days):
        timestamp = time.time() - days * 86400
        gitdir = self.gitdir(path)
        for file in (path, *(gitdir / name for name in ACTIVITY_FILES)):
            if file.exists():
                os.utime(file, (timestamp, timestamp))

    def branches(self):
        return set(self.git("for-each-ref", "--format=%(refname:short)", "refs/heads/").splitlines())

    def values(self, proc, category):
        section = proc.stdout.split(f"\n{category}:\n", 1)[1]
        lines = itertools.takewhile(lambda line: line.startswith("  - "), section.splitlines())
        return {line[4:] for line in lines if line[4:] != "none"}

    def assert_exit(self, proc, expected=0):
        self.assertEqual(proc.returncode, expected, proc.stdout + proc.stderr)

    def test_audit_and_cleanup_across_all_roots(self):
        extra = self.root / "Extra owned"
        codex_old = self.add_worktree(self.owned / "旧 Codex", "codex/merged-old")
        claude_old = self.add_worktree(self.claude / "claude-old", "claude/merged-old")
        claude_recent = self.add_worktree(self.claude / "recent", "claude/merged-recent")
        dirty = self.add_worktree(self.claude / "dirty", "claude/dirty-old", commit=self.base)
        self.git("commit", "--allow-empty", "-m", "Unmerged dirty branch", cwd=dirty)
        (dirty / "untracked.txt").touch()
        unmerged = self.add_worktree(self.owned / "unmerged", "codex/unmerged-old")
        self.git("commit", "--allow-empty", "-m", "Unmerged", cwd=unmerged)
        detached = self.add_worktree(self.codex_app / "0bad" / self.repo.name)
        detached_with_neighbor = self.add_worktree(self.codex_app / "bEEF" / self.repo.name)
        neighbor = detached_with_neighbor.parent / "keep.txt"
        neighbor.touch()
        owned_detached = self.add_worktree(extra / "owned-detached")
        pool = self.add_worktree(self.claude / "pool", commit=self.base)
        locked_pool = self.add_worktree(self.claude / "locked-pool", commit=self.base)
        locked = self.add_worktree(self.owned / "locked", "codex/locked")
        self.git("worktree", "lock", locked)
        self.git("worktree", "lock", "--reason", "App pool", locked_pool)
        other = self.add_worktree(self.owned / "other", "feature/other")
        self.git("branch", "claude/pool-tip", self.base)
        (codex_old / "cache.ignored").touch()

        unregistered = extra / "unregistered"
        unregistered.mkdir()
        (unregistered / ".git").touch()
        non_git = self.claude / "notes"
        non_git.mkdir()
        ignored_scan_path = self.codex_app / "unregistered app path"
        ignored_scan_path.mkdir()
        (ignored_scan_path / ".git").touch()

        removed = (codex_old, claude_old, detached, detached_with_neighbor, owned_detached)
        retained = (claude_recent, dirty, unmerged, pool, locked_pool, locked, other)
        fetch_head = self.gitdir(claude_recent) / "FETCH_HEAD"
        fetch_head.touch()
        for path in (*removed, *retained):
            self.set_idle_days(path, 10)
        recent_mtime = time.time() - 86400
        os.utime(fetch_head, (recent_mtime, recent_mtime))
        # Force Git to compare a changed file timestamp without changing the contents.
        changed_mtime = time.time() - 2 * 86400
        os.utime(codex_old / "tracked" / "sample.txt", (changed_mtime, changed_mtime))
        index = self.gitdir(codex_old) / "index"
        index_before = index.stat().st_mtime_ns

        options = ("--worktree-root", extra, "--worktree-root", self.claude, "--min-idle-days", "7.5")
        audit = self.run_cleanup(*options)
        self.assert_exit(audit)
        expected_categories = {
            "REMOVABLE_WORKTREE": {f"codex/merged-old | {codex_old}", f"claude/merged-old | {claude_old}"},
            "REMOVABLE_DETACHED_WORKTREE": {str(detached), str(detached_with_neighbor), str(owned_detached)},
            "RECENT_WORKTREE": {f"claude/merged-recent | {claude_recent}"},
            "DIRTY_WORKTREE": {f"claude/dirty-old | {dirty}"},
            "UNMERGED_WORKTREE": {f"codex/unmerged-old | {unmerged}"},
            "APP_POOL_WORKTREE": {str(pool)},
            "LOCKED_WORKTREE": {f"codex/locked | {locked}", str(locked_pool)},
            "NON_MANAGED_WORKTREE": {f"feature/other | {other}"},
            "UNREGISTERED_PATH": {str(unregistered)},
            "NON_GIT_DIRECTORY": {str(non_git)},
        }
        for category, entries in expected_categories.items():
            with self.subTest(category=category):
                self.assertEqual(self.values(audit, category), entries)
        self.assertTrue(all(path.is_dir() for path in (*removed, *retained)))
        self.assertEqual(index.stat().st_mtime_ns, index_before)
        self.assertIn("codex/merged-old", self.branches())
        self.assertIn("claude/merged-old", self.branches())
        self.assertFalse((self.owned / "WORKTREE_MANAGEMENT.md").exists())

        management = self.owned / "WORKTREE_MANAGEMENT.md"
        management.write_text("# 既存\n\n## 手動メモ\n\n- 保持予定を確認する。\n", encoding="utf-8")
        execution = self.run_cleanup(*options, "--execute", "--write-management")
        self.assert_exit(execution)
        self.assertTrue(all(not path.exists() for path in removed))
        self.assertTrue(all(path.exists() for path in (*retained, unregistered, non_git)))
        self.assertFalse(detached.parent.exists())
        self.assertTrue(neighbor.is_file())
        self.assertEqual(self.values(execution, "DELETED_BRANCH"), {"codex/merged-old", "claude/merged-old"})
        self.assertFalse({"codex/merged-old", "claude/merged-old"} & self.branches())
        self.assertIn("claude/pool-tip", self.branches())

        report = management.read_text(encoding="utf-8")
        for value in (self.owned, extra, self.claude, self.codex_app, codex_old, claude_old):
            self.assertIn(str(value), report)
        self.assertIn("min-idle-days: 7.5", report)
        self.assertIn("最終活動", report)
        self.assertRegex(report, r"RECENT_WORKTREE.*\d{4}-\d{2}-\d{2} \d{2}:\d{2}")
        self.assertIn("## 手動メモ\n\n- 保持予定を確認する。", report)

    def test_linked_script_uses_main_defaults_and_protects_working_directory(self):
        script_worktree = self.add_worktree(self.claude / "tool-source", "claude/tool-source")
        copy = script_worktree / ".codex" / "skills" / SCRIPT.parent.parent.name / "scripts" / SCRIPT.name
        copy.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT, copy)
        active = self.add_worktree(self.owned / "active", "codex/active")
        removable = self.add_worktree(self.owned / "removable", "codex/removable")
        pool = self.add_worktree(self.claude / "pool", commit=self.base)
        for path in (script_worktree, active, removable, pool):
            self.set_idle_days(path, 10)
        self.git("switch", "-c", "feature/main")
        (self.repo / "uncommitted.txt").touch()

        result = self.run_cleanup(
            "--execute", "--write-management",
            cwd=active / "tracked", script=copy, explicit_roots=False,
        )
        self.assert_exit(result)
        self.assertEqual(self.values(result, "REMOVABLE_WORKTREE"), {f"codex/removable | {removable}"})
        self.assertEqual(self.values(result, "APP_POOL_WORKTREE"), {str(pool)})
        self.assertFalse(removable.exists())
        self.assertTrue(active.is_dir())
        self.assertTrue(script_worktree.is_dir())
        self.assertTrue(pool.is_dir())
        self.assertIn("feature/main", result.stderr)
        self.assertIn("未コミット", result.stderr)
        self.assertTrue((self.owned / "WORKTREE_MANAGEMENT.md").is_file())

    def test_failed_removals_do_not_stop_other_deletions(self):
        dependency = self.root / "dependency"
        dependency.mkdir()
        self.git("init", "--initial-branch=main", cwd=dependency)
        self.git("commit", "--allow-empty", "-m", "Dependency", cwd=dependency)
        self.git("-c", "protocol.file.allow=always", "submodule", "add", dependency, "dep")
        self.git("commit", "-m", "Add dependency")
        with_submodule = self.git("rev-parse", "HEAD").strip()

        blocked = self.add_worktree(self.owned / "a-blocked", "codex/blocked", commit=with_submodule)
        self.git("-c", "protocol.file.allow=always", "submodule", "update", "--init", cwd=blocked)
        other = self.add_worktree(self.owned / "z-removable", "codex/removable")
        for path in (blocked, other):
            self.set_idle_days(path, 10)
        self.git("branch", "claude/blocked-upstream", with_submodule)
        self.git("branch", "feature/behind", self.tip)
        self.git("branch", "--set-upstream-to=feature/behind", "claude/blocked-upstream")

        proc = self.run_cleanup(
            "--prefix", "codex/", "--prefix", "claude/blocked",
            "--execute", "--write-management",
        )
        self.assert_exit(proc, 1)
        failures = self.values(proc, "FAILED")
        self.assertTrue(any(str(blocked) in failure for failure in failures), failures)
        self.assertTrue(any("claude/blocked-upstream" in failure for failure in failures), failures)
        self.assertEqual(self.values(proc, "DELETED_BRANCH"), {"codex/removable"})
        self.assertTrue(blocked.is_dir())
        self.assertFalse(other.exists())
        self.assertIn("claude/blocked-upstream", self.branches())
        self.assertIn("codex/blocked", self.branches())
        self.assertNotIn("codex/removable", self.branches())
        report = (self.owned / "WORKTREE_MANAGEMENT.md").read_text(encoding="utf-8")
        self.assertIn("execute 一部失敗", report)
        for failure in failures:
            self.assertIn(failure, report)

    def test_missing_local_develop_stops(self):
        self.git("switch", "-c", "feature/main")
        self.git("branch", "-d", "develop")
        proc = self.run_cleanup("--execute", "--write-management")
        self.assert_exit(proc, 2)
        self.assertIn("refs/heads/develop", proc.stderr)
        self.assertFalse((self.owned / "WORKTREE_MANAGEMENT.md").exists())


if __name__ == "__main__":
    unittest.main()
