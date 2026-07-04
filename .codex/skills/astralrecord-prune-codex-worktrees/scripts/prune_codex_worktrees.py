#!/usr/bin/env python3
"""Audit and optionally prune merged codex worktrees and branches."""

from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


class GitError(RuntimeError):
    def __init__(self, args: list[str], returncode: int, stdout: str, stderr: str) -> None:
        super().__init__(stderr.strip() or stdout.strip() or f"git exited with {returncode}")
        self.args_list = args
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


@dataclass
class WorktreeEntry:
    path: Path
    head: str | None
    branch: str | None
    detached: bool
    prunable: str | None

    @property
    def exists(self) -> bool:
        return self.path.exists()


def git(repo: Path, args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        ["git", *args],
        cwd=repo,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and proc.returncode != 0:
        raise GitError(args, proc.returncode, proc.stdout, proc.stderr)
    return proc


def git_out(repo: Path, args: list[str]) -> str:
    return git(repo, args).stdout.strip()


def git_ok(repo: Path, args: list[str]) -> bool:
    return git(repo, args, check=False).returncode == 0


def ensure_repo(repo: Path) -> Path:
    return Path(git_out(repo, ["rev-parse", "--show-toplevel"]))


def ensure_clean(repo: Path) -> None:
    status = git_out(repo, ["status", "--porcelain=v1", "-uall"])
    if status:
        raise RuntimeError("repository has uncommitted or staged changes")


def current_branch(repo: Path) -> str:
    return git_out(repo, ["branch", "--show-current"])


def local_branches(repo: Path, prefix: str) -> list[str]:
    ref_prefix = f"refs/heads/{prefix}"
    out = git_out(repo, ["for-each-ref", "--format=%(refname:short)", ref_prefix])
    return sorted(line for line in out.splitlines() if line)


def rev(repo: Path, ref: str) -> str:
    return git_out(repo, ["rev-parse", "--verify", ref])


def is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    return git_ok(repo, ["merge-base", "--is-ancestor", ancestor, descendant])


def worktree_clean(path: Path) -> bool:
    proc = subprocess.run(
        ["git", "status", "--porcelain=v1", "-uall"],
        cwd=path,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"git status failed in worktree {path}: {proc.stderr.strip() or proc.stdout.strip()}")
    return not proc.stdout.strip()


def parse_worktrees(repo: Path) -> list[WorktreeEntry]:
    out = git_out(repo, ["worktree", "list", "--porcelain"])
    entries: list[WorktreeEntry] = []
    block: dict[str, object] = {}
    for line in out.splitlines() + [""]:
        if not line:
            if block:
                branch_ref = block.get("branch")
                branch = None
                if isinstance(branch_ref, str) and branch_ref.startswith("refs/heads/"):
                    branch = branch_ref.removeprefix("refs/heads/")
                entries.append(
                    WorktreeEntry(
                        path=Path(block["path"]),
                        head=block.get("head"),
                        branch=branch,
                        detached=bool(block.get("detached")),
                        prunable=block.get("prunable"),
                    )
                )
                block = {}
            continue
        if line.startswith("worktree "):
            block["path"] = line.removeprefix("worktree ")
        elif line.startswith("HEAD "):
            block["head"] = line.removeprefix("HEAD ")
        elif line.startswith("branch "):
            block["branch"] = line.removeprefix("branch ")
        elif line == "detached":
            block["detached"] = True
        elif line.startswith("prunable "):
            block["prunable"] = line.removeprefix("prunable ")
    return entries


def print_values(title: str, values: list[str]) -> None:
    print(f"{title}:")
    if values:
        for value in values:
            print(f"  - {value}")
    else:
        print("  - none")


def scan_unregistered_paths(worktree_root: Path, registered: set[Path]) -> list[Path]:
    if not worktree_root.exists():
        return []
    result: list[Path] = []
    for child in sorted(worktree_root.iterdir()):
        if not child.is_dir():
            continue
        if child.resolve() in registered:
            continue
        if not (child / ".git").exists():
            continue
        result.append(child)
    return result


def audit(
    repo: Path, worktree_root: Path, prefix: str
) -> tuple[dict[str, list[str]], list[WorktreeEntry], list[str], str]:
    develop = rev(repo, "develop")
    entries = parse_worktrees(repo)
    branches = local_branches(repo, prefix)
    attached_branches = {entry.branch for entry in entries if entry.branch}
    detached_heads = {entry.head for entry in entries if entry.detached and entry.head}
    registered_under_root = {entry.path.resolve() for entry in entries if entry.exists}

    removable_worktrees: list[str] = []
    removable_branches: list[str] = []
    stale_metadata: list[str] = []
    unregistered_paths: list[str] = []
    dirty_worktrees: list[str] = []
    unmerged_worktrees: list[str] = []
    detached_worktrees: list[str] = []
    detached_head_branches: list[str] = []
    non_codex_worktrees: list[str] = []
    unmerged_branches: list[str] = []

    for entry in entries:
        if entry.path == repo:
            continue
        if not entry.exists:
            if entry.branch and entry.branch.startswith(prefix):
                stale_metadata.append(f"{entry.branch} | {entry.path}")
            elif entry.prunable:
                stale_metadata.append(f"{entry.path}")
            continue
        if entry.detached:
            if worktree_root in entry.path.parents:
                detached_worktrees.append(str(entry.path))
            continue
        if not entry.branch:
            continue
        if not entry.branch.startswith(prefix):
            if worktree_root in entry.path.parents:
                non_codex_worktrees.append(f"{entry.branch} | {entry.path}")
            continue
        branch_tip = rev(repo, entry.branch)
        if not is_ancestor(repo, branch_tip, develop):
            unmerged_worktrees.append(f"{entry.branch} | {entry.path}")
            continue
        if worktree_clean(entry.path):
            removable_worktrees.append(f"{entry.branch} | {entry.path}")
        else:
            dirty_worktrees.append(f"{entry.branch} | {entry.path}")

    for branch in branches:
        branch_tip = rev(repo, branch)
        if is_ancestor(repo, branch_tip, develop):
            if branch_tip in detached_heads:
                detached_head_branches.append(branch)
                continue
            if branch not in attached_branches:
                removable_branches.append(branch)
        else:
            unmerged_branches.append(branch)

    for path in scan_unregistered_paths(worktree_root, registered_under_root):
        unregistered_paths.append(str(path))

    categories = {
        "REMOVABLE_WORKTREE": removable_worktrees,
        "REMOVABLE_BRANCH": removable_branches,
        "STALE_METADATA": stale_metadata,
        "UNREGISTERED_PATH": unregistered_paths,
        "DIRTY_WORKTREE": dirty_worktrees,
        "UNMERGED_WORKTREE": unmerged_worktrees,
        "UNMERGED_BRANCH": unmerged_branches,
        "DETACHED_WORKTREE": detached_worktrees,
        "DETACHED_HEAD_BRANCH": detached_head_branches,
        "NON_CODEX_WORKTREE": non_codex_worktrees,
    }
    return categories, entries, branches, develop


def remove_worktrees(repo: Path, entries: list[WorktreeEntry], prefix: str, develop: str) -> list[str]:
    removed: list[str] = []
    for entry in entries:
        if entry.path == repo or not entry.exists or entry.detached or not entry.branch:
            continue
        if not entry.branch.startswith(prefix):
            continue
        branch_tip = rev(repo, entry.branch)
        if not is_ancestor(repo, branch_tip, develop):
            continue
        if not worktree_clean(entry.path):
            continue
        git(repo, ["worktree", "remove", str(entry.path)])
        removed.append(f"{entry.branch} | {entry.path}")
    return removed


def delete_branches(repo: Path, prefix: str, develop: str) -> list[str]:
    deleted: list[str] = []
    entries = parse_worktrees(repo)
    attached_branches = {entry.branch for entry in entries if entry.branch}
    detached_heads = {entry.head for entry in entries if entry.detached and entry.head}
    for branch in local_branches(repo, prefix):
        branch_tip = rev(repo, branch)
        if not is_ancestor(repo, branch_tip, develop):
            continue
        if branch_tip in detached_heads:
            continue
        if branch in attached_branches:
            continue
        git(repo, ["branch", "-d", branch])
        deleted.append(branch)
    return deleted


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Audit and optionally prune merged codex worktrees and branches."
    )
    parser.add_argument("--repo", default=".", help="Path inside the target repository.")
    parser.add_argument("--worktree-root", default="E:\\AstralRecord-Worktrees", help="Task worktree root to scan.")
    parser.add_argument("--prefix", default="codex/", help="Local branch prefix to include.")
    parser.add_argument("--execute", action="store_true", help="Actually prune metadata, remove worktrees, and delete branches.")
    args = parser.parse_args()

    try:
        repo = ensure_repo(Path(args.repo).resolve())
        worktree_root = Path(args.worktree_root).resolve()
        categories, entries, _, develop = audit(repo, worktree_root, args.prefix)
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: failed to inspect repository: {exc}", file=sys.stderr)
        return 2

    print(f"repo: {repo}")
    print(f"mode: {'execute' if args.execute else 'dry-run'}")
    print(f"develop: {develop}")
    print(f"main_branch: {current_branch(repo)}")
    print(f"worktree_root: {worktree_root}")
    for title in [
        "REMOVABLE_WORKTREE",
        "REMOVABLE_BRANCH",
        "STALE_METADATA",
        "UNREGISTERED_PATH",
        "DIRTY_WORKTREE",
        "UNMERGED_WORKTREE",
        "UNMERGED_BRANCH",
        "DETACHED_WORKTREE",
        "DETACHED_HEAD_BRANCH",
        "NON_CODEX_WORKTREE",
    ]:
        print_values(title, categories[title])

    if not args.execute:
        print("result: dry-run successful")
        return 0

    try:
        ensure_clean(repo)
        if current_branch(repo) != "develop":
            raise RuntimeError("main workspace current branch must be develop for execute mode")

        pruned_metadata = "none"
        if categories["STALE_METADATA"]:
            proc = git(repo, ["worktree", "prune", "--verbose"])
            pruned_metadata = proc.stdout.strip() or "done"

        removed = remove_worktrees(repo, parse_worktrees(repo), args.prefix, develop)
        deleted = delete_branches(repo, args.prefix, develop)
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: cleanup stopped: {exc}", file=sys.stderr)
        return 2

    print_values("PRUNED_METADATA", [pruned_metadata] if pruned_metadata != "none" else [])
    print_values("REMOVED_WORKTREE", removed)
    print_values("DELETED_BRANCH", deleted)
    print("result: execute successful")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
