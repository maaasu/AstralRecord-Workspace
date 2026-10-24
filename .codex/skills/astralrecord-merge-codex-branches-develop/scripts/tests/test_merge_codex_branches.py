"""Exercise merge preservation and collision checks against real Git repositories."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "merge_codex_branches.py"


class MergePreservationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-b", "develop")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "core.autocrlf", "false")
        for path in ("target.txt", "other.txt", "old.txt"):
            self.write(path, "base\n")
        self.git("add", "--", "target.txt", "other.txt", "old.txt")
        self.git("commit", "-m", "base")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *args: str) -> bytes:
        return subprocess.run(
            ["git", *args], cwd=self.repo, check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        ).stdout

    def write(self, path: str, content: str) -> None:
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8", newline="\n")

    def feature(self, path: str = "target.txt") -> bytes:
        self.git("checkout", "-b", "codex/one")
        self.write(path, "incoming\n")
        self.git("add", "--", path)
        self.git("commit", "-m", "feature")
        tip = self.git("rev-parse", "HEAD")
        self.git("checkout", "develop")
        return tip

    def execute(self, success: bool) -> subprocess.CompletedProcess[bytes]:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--repo", str(self.repo), "--execute"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        self.assertEqual(result.returncode, 0 if success else 2, result.stderr)
        return result

    def snapshot(self) -> tuple[bytes, bytes, bytes]:
        return (
            self.git("status", "--porcelain=v1", "-z", "-uall"),
            self.git("diff", "--binary"),
            self.git("diff", "--cached", "--binary"),
        )

    def test_preserves_unrelated_staged_unstaged_and_untracked(self) -> None:
        tip = self.feature()
        self.write("other.txt", "staged\n")
        self.git("add", "--", "other.txt")
        self.write("other.txt", "staged and unstaged\n")
        self.write("日本語 空白.txt", "untracked\n")
        before = self.snapshot()
        self.execute(True)
        self.assertEqual(self.git("rev-parse", "HEAD"), tip)
        self.assertEqual(self.snapshot(), before)
        self.assertEqual((self.repo / "日本語 空白.txt").read_bytes(), b"untracked\n")

    def test_rejects_tracked_overlap_without_mutation(self) -> None:
        self.feature()
        self.write("target.txt", "local\n")
        before = self.snapshot()
        self.execute(False)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual(self.snapshot(), before)

    def test_rejects_untracked_overlap_without_mutation(self) -> None:
        self.feature("new.txt")
        self.write("new.txt", "local untracked\n")
        self.execute(False)
        self.assertEqual((self.repo / "new.txt").read_bytes(), b"local untracked\n")
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_rejects_untracked_parent_file(self) -> None:
        self.feature("new/child.txt")
        self.write("new", "local file\n")
        self.execute(False)
        self.assertEqual((self.repo / "new").read_bytes(), b"local file\n")

    def test_rejects_untracked_child_when_incoming_is_file(self) -> None:
        self.feature("new")
        self.write("new/child.txt", "local child\n")
        self.execute(False)
        self.assertEqual((self.repo / "new/child.txt").read_bytes(), b"local child\n")

    def test_rejects_incoming_rename_source_change(self) -> None:
        self.git("checkout", "-b", "codex/one")
        self.git("mv", "old.txt", "renamed.txt")
        self.git("commit", "-m", "rename")
        self.git("checkout", "develop")
        self.write("old.txt", "local\n")
        self.execute(False)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_rejects_local_staged_rename_source_collision(self) -> None:
        self.feature("old.txt")
        self.git("mv", "old.txt", "local.txt")
        before = self.snapshot()
        self.execute(False)
        self.assertEqual(self.snapshot(), before)

    def test_preflights_later_branch_before_first_merge(self) -> None:
        self.feature()
        self.git("checkout", "-b", "codex/two", "codex/one")
        self.write("other.txt", "later incoming\n")
        self.git("add", "--", "other.txt")
        self.git("commit", "-m", "second")
        self.git("checkout", "develop")
        self.write("other.txt", "local\n")
        self.execute(False)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_unrelated_paths_with_common_prefix_are_allowed(self) -> None:
        tip = self.feature("new")
        self.write("new-other.txt", "local\n")
        self.execute(True)
        self.assertEqual(self.git("rev-parse", "HEAD"), tip)
        self.assertEqual((self.repo / "new-other.txt").read_bytes(), b"local\n")

    def test_checks_checkout_before_mutation(self) -> None:
        self.feature()
        self.git("checkout", "-b", "other", "codex/one")
        self.write("target.txt", "local\n")
        self.execute(False)
        self.assertEqual(self.git("branch", "--show-current"), b"other\n")

    def ignore(self, pattern: str) -> None:
        self.write(".gitignore", pattern + "\n")
        self.git("add", "--", ".gitignore")
        self.git("commit", "-m", "ignore local files")
        self.base = self.git("rev-parse", "HEAD")

    def ignored_feature(self, path: str) -> bytes:
        self.git("checkout", "-b", "codex/one")
        self.write(path, "incoming\n")
        self.git("add", "-f", "--", path)
        self.git("commit", "-m", "track previously ignored file")
        tip = self.git("rev-parse", "HEAD")
        self.git("checkout", "develop")
        return tip

    def test_rejects_ignored_file_overlap_without_overwrite(self) -> None:
        self.ignore("cache.txt")
        self.ignored_feature("cache.txt")
        self.write("cache.txt", "local ignored\n")
        self.execute(False)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual((self.repo / "cache.txt").read_bytes(), b"local ignored\n")

    def test_rejects_ignored_directory_child_overlap(self) -> None:
        self.ignore("cache/")
        self.ignored_feature("cache/one.txt")
        self.write("cache/one.txt", "local ignored\n")
        self.execute(False)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual((self.repo / "cache/one.txt").read_bytes(), b"local ignored\n")

    def test_preserves_unrelated_ignored_sibling(self) -> None:
        self.ignore("cache/")
        tip = self.ignored_feature("cache/one.txt")
        self.write("cache/two.txt", "local ignored\n")
        self.execute(True)
        self.assertEqual(self.git("rev-parse", "HEAD"), tip)
        self.assertEqual((self.repo / "cache/two.txt").read_bytes(), b"local ignored\n")


if __name__ == "__main__":
    unittest.main()
