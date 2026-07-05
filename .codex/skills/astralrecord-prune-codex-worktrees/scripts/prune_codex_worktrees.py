#!/usr/bin/env python3
"""Audit and optionally prune merged codex worktrees and branches."""

from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
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


def scan_worktree_root_paths(worktree_root: Path, registered: set[Path]) -> tuple[list[Path], list[Path]]:
    if not worktree_root.exists():
        return [], []
    git_dirs: list[Path] = []
    non_git_dirs: list[Path] = []
    for child in sorted(worktree_root.iterdir()):
        if not child.is_dir():
            continue
        if child.resolve() in registered:
            continue
        if not (child / ".git").exists():
            non_git_dirs.append(child)
            continue
        git_dirs.append(child)
    return git_dirs, non_git_dirs


def management_action(category: str) -> str:
    actions = {
        "REMOVABLE_WORKTREE": "develop merged and clean. Remove in execute cleanup unless intentionally retained.",
        "REMOVABLE_BRANCH": "develop merged and not attached. Delete in execute cleanup unless intentionally retained.",
        "STALE_METADATA": "Path is missing. Run git worktree prune in execute cleanup.",
        "UNREGISTERED_PATH": "Git-looking directory is not registered. Inspect manually before deletion.",
        "NON_GIT_DIRECTORY": "Directory is not a registered git worktree. Inspect manually before deletion.",
        "DIRTY_WORKTREE": "Branch is already in develop, but local uncommitted changes remain. Inspect, commit elsewhere, or discard manually.",
        "UNMERGED_WORKTREE": "Branch tip is not in develop. Finalize/rebase individually or decide to abandon.",
        "UNMERGED_BRANCH": "Branch tip is not in develop and no registered worktree is attached. Finalize/rebase individually or decide to abandon.",
        "DETACHED_WORKTREE": "Detached worktree. Inspect conflicts or unfinished rebase/cherry-pick state manually.",
        "DETACHED_HEAD_BRANCH": "Merged branch tip is held by a detached worktree. Inspect detached worktree before deleting branch.",
        "NON_CODEX_WORKTREE": "Registered worktree outside codex/* scope. Leave it to its owning workflow.",
    }
    return actions.get(category, "Inspect manually.")


def split_entry(value: str) -> tuple[str, str]:
    if " | " not in value:
        return value, ""
    left, right = value.split(" | ", 1)
    return left, right


def preserved_manual_notes(path: Path) -> str:
    if not path.exists():
        return "- Add manual decisions here when a worktree is intentionally kept.\n"
    text = path.read_text(encoding="utf-8")
    marker = "\n## Manual Notes\n"
    if marker not in text:
        return "- Add manual decisions here when a worktree is intentionally kept.\n"
    notes = text.split(marker, 1)[1].strip()
    return f"{notes}\n" if notes else "- Add manual decisions here when a worktree is intentionally kept.\n"


def write_management_report(
    path: Path,
    repo: Path,
    mode: str,
    develop: str,
    main_branch: str,
    worktree_root: Path,
    categories: dict[str, list[str]],
    result: str,
) -> None:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines = [
        "# AstralRecord Worktree Management",
        "",
        "This file is a local management snapshot generated by `$astralrecord-prune-codex-worktrees`.",
        "Use it to distinguish completed cleanup leftovers from active, blocked, dirty, or detached worktrees.",
        "",
        "## Snapshot",
        "",
        f"- generated_at: {now}",
        f"- repo: `{repo}`",
        f"- mode: `{mode}`",
        f"- develop: `{develop}`",
        f"- main_branch: `{main_branch}`",
        f"- worktree_root: `{worktree_root}`",
        f"- result: {result}",
        "",
        "## Status Table",
        "",
        "| category | branch/path | worktree/path | action |",
        "|:--|:--|:--|:--|",
    ]

    ordered_categories = [
        "REMOVABLE_WORKTREE",
        "REMOVABLE_BRANCH",
        "STALE_METADATA",
        "UNREGISTERED_PATH",
        "NON_GIT_DIRECTORY",
        "DIRTY_WORKTREE",
        "UNMERGED_WORKTREE",
        "UNMERGED_BRANCH",
        "DETACHED_WORKTREE",
        "DETACHED_HEAD_BRANCH",
        "NON_CODEX_WORKTREE",
    ]
    wrote_row = False
    for category in ordered_categories:
        values = categories.get(category, [])
        if not values:
            continue
        action = management_action(category)
        for value in values:
            first, second = split_entry(value)
            lines.append(f"| `{category}` | `{first}` | `{second}` | {action} |")
            wrote_row = True
    if not wrote_row:
        lines.append("| `CLEAN` | `none` | `none` | No codex worktree cleanup or follow-up item was found. |")

    lines.extend(
        [
            "",
            "## Manual Notes",
            "",
            preserved_manual_notes(path).rstrip(),
            "",
        ]
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


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
    non_git_directories: list[str] = []
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

    unregistered_git_paths, non_git_paths = scan_worktree_root_paths(worktree_root, registered_under_root)
    for path in unregistered_git_paths:
        unregistered_paths.append(str(path))
    for path in non_git_paths:
        non_git_directories.append(str(path))

    categories = {
        "REMOVABLE_WORKTREE": removable_worktrees,
        "REMOVABLE_BRANCH": removable_branches,
        "STALE_METADATA": stale_metadata,
        "UNREGISTERED_PATH": unregistered_paths,
        "NON_GIT_DIRECTORY": non_git_directories,
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
    parser.add_argument(
        "--write-management",
        action="store_true",
        help="Write a local Markdown management snapshot for worktree follow-up.",
    )
    parser.add_argument(
        "--management-file",
        default="E:\\AstralRecord-Worktrees\\WORKTREE_MANAGEMENT.md",
        help="Path for --write-management output.",
    )
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
        "NON_GIT_DIRECTORY",
        "DIRTY_WORKTREE",
        "UNMERGED_WORKTREE",
        "UNMERGED_BRANCH",
        "DETACHED_WORKTREE",
        "DETACHED_HEAD_BRANCH",
        "NON_CODEX_WORKTREE",
    ]:
        print_values(title, categories[title])

    if not args.execute:
        if args.write_management:
            management_file = Path(args.management_file).resolve()
            write_management_report(
                management_file,
                repo,
                "dry-run",
                develop,
                current_branch(repo),
                worktree_root,
                categories,
                "dry-run successful",
            )
            print(f"management_file: {management_file}")
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
        refreshed_categories, _, _, refreshed_develop = audit(repo, worktree_root, args.prefix)
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: cleanup stopped: {exc}", file=sys.stderr)
        return 2

    if args.write_management:
        management_file = Path(args.management_file).resolve()
        write_management_report(
            management_file,
            repo,
            "execute",
            refreshed_develop,
            current_branch(repo),
            worktree_root,
            refreshed_categories,
            "execute successful",
        )
        print(f"management_file: {management_file}")
    print_values("PRUNED_METADATA", [pruned_metadata] if pruned_metadata != "none" else [])
    print_values("REMOVED_WORKTREE", removed)
    print_values("DELETED_BRANCH", deleted)
    print("result: execute successful")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
