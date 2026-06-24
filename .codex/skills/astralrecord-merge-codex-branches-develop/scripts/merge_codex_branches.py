#!/usr/bin/env python3
"""Audit or fast-forward merge local codex/* branches into develop."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


class GitError(RuntimeError):
    def __init__(self, args: list[str], returncode: int, stdout: str, stderr: str) -> None:
        super().__init__(stderr.strip() or stdout.strip() or f"git exited with {returncode}")
        self.args_list = args
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


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
    root = Path(git_out(repo, ["rev-parse", "--show-toplevel"]))
    return root


def ensure_clean(repo: Path) -> None:
    status = git_out(repo, ["status", "--porcelain=v1", "-uall"])
    if status:
        raise RuntimeError("repository has uncommitted or staged changes")


def local_branches(repo: Path, prefix: str) -> list[str]:
    ref_prefix = f"refs/heads/{prefix}"
    out = git_out(repo, ["for-each-ref", "--format=%(refname:short)", ref_prefix])
    return sorted(line for line in out.splitlines() if line)


def rev(repo: Path, ref: str) -> str:
    return git_out(repo, ["rev-parse", "--verify", ref])


def is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    return git_ok(repo, ["merge-base", "--is-ancestor", ancestor, descendant])


def classify(repo: Path, branch: str, simulated_head: str) -> tuple[str, str]:
    branch_tip = rev(repo, branch)
    if is_ancestor(repo, branch_tip, simulated_head):
        return "ALREADY_MERGED", simulated_head
    if is_ancestor(repo, simulated_head, branch_tip):
        return "MERGEABLE", branch_tip
    return "NON_FAST_FORWARD", simulated_head


def print_list(title: str, values: list[str]) -> None:
    print(f"{title}:")
    if values:
        for value in values:
            print(f"  - {value}")
    else:
        print("  - none")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Audit or fast-forward merge local codex/* branches into develop."
    )
    parser.add_argument("--repo", default=".", help="Path inside the target repository.")
    parser.add_argument("--prefix", default="codex/", help="Local branch prefix to include.")
    parser.add_argument("--execute", action="store_true", help="Actually checkout develop and merge.")
    parser.add_argument(
        "--skip-non-ff",
        action="store_true",
        help="Continue past branches that are not fast-forwardable.",
    )
    parser.add_argument(
        "--delete-merged",
        action="store_true",
        help="Delete successfully merged local codex branches after execute.",
    )
    args = parser.parse_args()

    try:
        repo = ensure_repo(Path(args.repo).resolve())
        develop = rev(repo, "develop")
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: failed to inspect repository: {exc}", file=sys.stderr)
        return 2

    branches = local_branches(repo, args.prefix)
    mergeable: list[str] = []
    already_merged: list[str] = []
    non_ff: list[str] = []
    merged: list[str] = []
    deleted: list[str] = []

    simulated_head = develop
    for branch in branches:
        state, next_head = classify(repo, branch, simulated_head)
        if state == "ALREADY_MERGED":
            already_merged.append(branch)
        elif state == "MERGEABLE":
            mergeable.append(branch)
            simulated_head = next_head
        else:
            non_ff.append(branch)
            if not args.skip_non_ff:
                break

    print(f"repo: {repo}")
    print(f"mode: {'execute' if args.execute else 'dry-run'}")
    print(f"develop: {develop}")
    print_list("MERGEABLE", mergeable)
    print_list("ALREADY_MERGED", already_merged)
    print_list("NON_FAST_FORWARD", non_ff)

    if not args.execute:
        if non_ff:
            print("result: dry-run found non-fast-forward branches")
            return 1
        print("result: dry-run successful")
        return 0

    if non_ff and not args.skip_non_ff:
        print("ERROR: non-fast-forward branch found; rerun with --skip-non-ff or rebase individually", file=sys.stderr)
        return 2

    try:
        ensure_clean(repo)
        current = git_out(repo, ["branch", "--show-current"])
        if current != "develop":
            git(repo, ["checkout", "develop"])
        ensure_clean(repo)

        for branch in mergeable:
            git(repo, ["merge", "--ff-only", branch])
            merged.append(branch)

        if args.delete_merged:
            for branch in merged:
                git(repo, ["branch", "-d", branch])
                deleted.append(branch)
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: merge stopped: {exc}", file=sys.stderr)
        print_list("MERGED", merged)
        print_list("DELETED", deleted)
        return 2

    print_list("MERGED", merged)
    print_list("DELETED", deleted)
    print("result: execute successful")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
