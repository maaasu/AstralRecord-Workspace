#!/usr/bin/env python3
"""Audit and optionally prune merged Codex and Claude worktrees and branches."""

from __future__ import annotations

import argparse
import errno
import math
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from tempfile import NamedTemporaryFile


LOCAL_BRANCH_REF = "refs/heads/"
ACTIVITY_FILES = ("HEAD", "index", "logs/HEAD", "ORIG_HEAD", "FETCH_HEAD")
REMOVABLE_WORKTREES = ("REMOVABLE_WORKTREE", "REMOVABLE_DETACHED_WORKTREE")
ACTIONS = {
    "REMOVABLE_WORKTREE": "develop に取り込み済みで変更がなく、最終活動から指定日数が経過。実行モードで削除する。",
    "REMOVABLE_DETACHED_WORKTREE": "所有ルートまたは Codex アプリの取り込み済み detached HEAD。指定日数が経過したため実行モードで削除する。",
    "REMOVABLE_BRANCH": "develop に取り込み済みで、作業ツリーから参照されていない。実行モードで削除する。",
    "STALE_METADATA": "作業ツリーが見つからないメタデータ。実行モードで git worktree prune を行う。",
    "UNREGISTERED_PATH": "Git チェックアウトらしい未登録ディレクトリ。手動で確認する。",
    "NON_GIT_DIRECTORY": "Git 作業ツリー以外のディレクトリ。手動で確認する。",
    "DIRTY_WORKTREE": "未コミットの変更がある。内容を確認して保存・破棄・コミットを判断する。",
    "UNMERGED_WORKTREE": "ブランチの先端が develop に未取り込み。個別の完了処理・リベース・破棄を判断する。",
    "UNMERGED_BRANCH": "develop に未取り込みのブランチ。取り込み状況を確認する。",
    "DETACHED_WORKTREE": "変更あり・未取り込み・削除対象外の配置先のいずれかに当たる detached HEAD。手動で確認する。",
    "DETACHED_HEAD_BRANCH": "ブランチの先端を detached HEAD の作業ツリーが参照している。保持する。",
    "NON_MANAGED_WORKTREE": "対象の接頭辞に一致しないブランチの作業ツリー。保持する。",
    "RECENT_WORKTREE": "最終活動から指定日数が経過していない。保持する。",
    "LOCKED_WORKTREE": "Git でロックされた作業ツリー。保持する。",
    "APP_POOL_WORKTREE": "Claude アプリの予備作業ツリー。アプリが管理する。",
}


class GitError(RuntimeError):
    pass


@dataclass(frozen=True)
class WorktreeEntry:
    path: Path
    head: str | None
    branch: str | None
    detached: bool
    locked: bool
    prunable: bool


@dataclass(frozen=True)
class AuditRow:
    path: Path | None = None
    branch: str | None = None
    activity: float | None = None

    def label(self) -> str:
        return " | ".join(str(value) for value in (self.branch, self.path) if value is not None)


@dataclass(frozen=True)
class Settings:
    repo: Path
    selected_repo: Path
    cwd: Path
    worktree_roots: tuple[Path, ...]
    claude_root: Path
    codex_app_root: Path
    prefixes: tuple[str, ...]
    min_idle_days: float


@dataclass
class CleanupResult:
    pruned: list[str] = field(default_factory=list)
    removed: list[str] = field(default_factory=list)
    deleted: list[str] = field(default_factory=list)
    failures: list[str] = field(default_factory=list)


def git(repo: Path, args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        ["git", *args],
        cwd=repo,
        encoding="utf-8",
        errors="surrogateescape",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and proc.returncode != 0:
        detail = proc.stderr.strip() or proc.stdout.strip()
        raise GitError(" / ".join(detail.splitlines()) or f"git exited with {proc.returncode}")
    return proc


def git_out(repo: Path, args: list[str]) -> str:
    return git(repo, args).stdout.strip()


def develop_tip(repo: Path) -> str:
    return git_out(repo, ["show-ref", "--hash", "--verify", "refs/heads/develop"])


def is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    return git(repo, ["merge-base", "--is-ancestor", ancestor, descendant], check=False).returncode == 0


def worktree_clean(path: Path) -> bool:
    return not git_out(path, ["--no-optional-locks", "status", "--porcelain=v1", "-uall"])


def parse_worktrees(repo: Path) -> list[WorktreeEntry]:
    out = git_out(repo, ["worktree", "list", "--porcelain", "-z"])
    entries: list[WorktreeEntry] = []
    for block in out.split("\0\0"):
        if not block:
            continue
        fields = {}
        for line in block.split("\0"):
            key, _, value = line.partition(" ")
            fields[key] = value
        entries.append(
            WorktreeEntry(
                path=Path(fields["worktree"]),
                head=fields.get("HEAD"),
                branch=fields["branch"].removeprefix(LOCAL_BRANCH_REF) if "branch" in fields else None,
                detached="detached" in fields,
                locked="locked" in fields,
                prunable="prunable" in fields,
            )
        )
    return entries


def last_activity(path: Path) -> float:
    newest = path.stat().st_mtime
    marker = (path / ".git").read_text(encoding="utf-8").rstrip("\r\n")
    if not marker.startswith("gitdir: "):
        raise RuntimeError(f"gitdir を確認できません: {path / '.git'}")
    gitdir = path / marker.removeprefix("gitdir: ")
    for name in ACTIVITY_FILES:
        try:
            newest = max(newest, (gitdir / name).stat().st_mtime)
        except FileNotFoundError:
            pass
    return newest


def print_values(title: str, values: list[str]) -> None:
    print(f"{title}:")
    for value in values or ["none"]:
        print(f"  - {value}")


def scan_worktree_root_paths(root: Path, registered: set[Path]) -> dict[Path, str]:
    if not root.exists():
        return {}
    return {
        child: "UNREGISTERED_PATH" if (child / ".git").exists() else "NON_GIT_DIRECTORY"
        for child in sorted(root.iterdir())
        if child.is_dir() and child.resolve() not in registered
    }


def protected_worktrees(settings: Settings, entries: list[WorktreeEntry]) -> set[Path]:
    protected = {settings.repo, settings.selected_repo, settings.cwd}
    for path in (settings.repo, settings.selected_repo, settings.cwd):
        protected.update(path.parents)
    # Removing a parent would also remove a nested worktree, even if it is ignored by Git.
    for entry in entries:
        protected.update(entry.path.resolve().parents)
    return protected


def worktree_category(
    settings: Settings,
    entry: WorktreeEntry,
    develop: str,
    activity: float | None,
    now: float,
) -> str | None:
    path = entry.path.resolve()
    managed_branch = bool(entry.branch and entry.branch.startswith(settings.prefixes))
    if entry.locked:
        return "LOCKED_WORKTREE"
    if entry.detached and (
        settings.claude_root in entry.path.parents or settings.claude_root.resolve() in path.parents
    ):
        return "APP_POOL_WORKTREE"
    if activity is None:
        return "STALE_METADATA" if managed_branch or entry.prunable else None
    if entry.detached:
        roots = (*settings.worktree_roots, settings.codex_app_root)
        if not any(root.resolve() in path.parents for root in roots):
            return "DETACHED_WORKTREE"
    elif not managed_branch:
        return "NON_MANAGED_WORKTREE"

    if not worktree_clean(entry.path):
        return "DETACHED_WORKTREE" if entry.detached else "DIRTY_WORKTREE"
    if not entry.head or not is_ancestor(settings.repo, entry.head, develop):
        return "DETACHED_WORKTREE" if entry.detached else "UNMERGED_WORKTREE"
    if (now - activity) / 86400 < settings.min_idle_days:
        return "RECENT_WORKTREE"
    return "REMOVABLE_DETACHED_WORKTREE" if entry.detached else "REMOVABLE_WORKTREE"


def inspect_worktree(
    settings: Settings,
    entry: WorktreeEntry,
    protected: set[Path],
    develop: str,
    now: float,
) -> tuple[str | None, AuditRow]:
    if entry.path.resolve() in protected:
        return None, AuditRow()
    activity = None
    if entry.path.is_dir() and not entry.prunable:
        try:
            activity = last_activity(entry.path)
        except (RuntimeError, OSError):
            # Unreadable gitdir marker: treat as active so the worktree is kept.
            activity = now
    try:
        category = worktree_category(settings, entry, develop, activity, now)
    except GitError:
        # A worktree that git cannot inspect is kept for manual review instead of aborting the audit.
        category = "DETACHED_WORKTREE" if entry.detached else "DIRTY_WORKTREE"
    return category, AuditRow(entry.path, entry.branch, activity)


def inspect_branches(
    settings: Settings, entries: list[WorktreeEntry], develop: str
) -> dict[str, list[AuditRow]]:
    categories: dict[str, list[AuditRow]] = {
        "REMOVABLE_BRANCH": [],
        "UNMERGED_BRANCH": [],
        "DETACHED_HEAD_BRANCH": [],
    }
    attached_branches = {entry.branch for entry in entries if entry.branch}
    detached_heads = {entry.head for entry in entries if entry.detached and entry.head}
    refs = git_out(settings.repo, ["for-each-ref", "--format=%(refname) %(objectname)", LOCAL_BRANCH_REF])
    for line in refs.splitlines():
        ref, tip = line.split(" ", 1)
        branch = ref.removeprefix(LOCAL_BRANCH_REF)
        if not branch.startswith(settings.prefixes):
            continue
        if not is_ancestor(settings.repo, tip, develop):
            category = "UNMERGED_BRANCH"
        elif tip in detached_heads:
            category = "DETACHED_HEAD_BRANCH"
        elif branch not in attached_branches:
            category = "REMOVABLE_BRANCH"
        else:
            continue
        categories[category].append(AuditRow(branch=branch))
    return categories


def audit(settings: Settings, develop: str) -> dict[str, list[AuditRow]]:
    entries = parse_worktrees(settings.repo)
    protected = protected_worktrees(settings, entries)
    categories: dict[str, list[AuditRow]] = {category: [] for category in ACTIONS}
    now = time.time()
    for entry in entries:
        category, row = inspect_worktree(settings, entry, protected, develop, now)
        if category:
            categories[category].append(row)

    categories.update(inspect_branches(settings, entries, develop))
    registered = {entry.path.resolve() for entry in entries}
    scanned: set[Path] = set()
    roots = dict.fromkeys(root.resolve() for root in (*settings.worktree_roots, settings.claude_root))
    for root in roots:
        for path, category in scan_worktree_root_paths(root, registered).items():
            resolved = path.resolve()
            if resolved not in scanned:
                categories[category].append(AuditRow(path=path))
                scanned.add(resolved)
    return categories


def preserved_manual_notes(path: Path) -> str:
    default = "- 意図的に保持する worktree の判断理由をここに追記する。\n"
    if not path.exists():
        return default
    text = path.read_text(encoding="utf-8")
    marker = "\n## 手動メモ\n"
    legacy_marker = "\n## Manual Notes\n"
    if marker not in text and legacy_marker not in text:
        return default
    if marker not in text:
        marker = legacy_marker
    notes = text.split(marker, 1)[1].strip()
    if not notes or notes == "- Add manual decisions here when a worktree is intentionally kept.":
        return default
    return f"{notes}\n"


def write_management_report(
    path: Path,
    settings: Settings,
    develop: str,
    main_branch: str,
    categories: dict[str, list[AuditRow]],
    result: CleanupResult | None,
) -> None:
    mode = "dry-run" if result is None else "execute"
    status = "一部失敗" if result and result.failures else "成功"
    lines = [
        "# AstralRecord Worktree 管理",
        "",
        "このファイルは `$astralrecord-prune-codex-worktrees` が生成するローカル管理スナップショットです。",
        "",
        "## スナップショット",
        "",
        f"- 生成日時: {datetime.now():%Y-%m-%d %H:%M:%S}",
        f"- repo: `{settings.repo}`",
        f"- mode: `{mode}`",
        f"- develop: `{develop}`",
        f"- main branch: `{main_branch}`",
        *(f"- 所有ルート: `{root}`" for root in settings.worktree_roots),
        f"- Claude ルート: `{settings.claude_root}`",
        f"- Codex アプリルート: `{settings.codex_app_root}`",
        f"- prefix: `{', '.join(settings.prefixes)}`",
        f"- min-idle-days: {settings.min_idle_days:g}",
        f"- 結果: {mode} {status}",
    ]
    if result:
        for label, values in (
            ("整理したメタデータ", result.pruned),
            ("削除した worktree", result.removed),
            ("削除したブランチ", result.deleted),
            ("失敗", result.failures),
        ):
            lines.append(f"- {label}")
            lines.extend(f"  - {value}" for value in values or ["なし"])

    lines.extend([
        "",
        "## 状態一覧",
        "",
        "| 分類 | branch/path | worktree/path | 最終活動 | 判断 |",
        "|:--|:--|:--|:--|:--|",
    ])
    for category, rows in categories.items():
        for row in rows:
            first = row.branch or str(row.path)
            second = f"`{row.path}`" if row.branch and row.path else ""
            activity = datetime.fromtimestamp(row.activity).strftime("%Y-%m-%d %H:%M") if row.activity is not None else ""
            lines.append(f"| `{category}` | `{first}` | {second} | {activity} | {ACTIONS[category]} |")
    if not any(categories.values()):
        lines.append("| `CLEAN` | | | | 整理対象や確認項目はありません。 |")

    lines.extend(["", "## 手動メモ", "", preserved_manual_notes(path).rstrip(), ""])
    path.parent.mkdir(parents=True, exist_ok=True)
    report = NamedTemporaryFile(mode="w", dir=path.parent, encoding="utf-8", delete=False)
    temporary_path = Path(report.name)
    try:
        with report:
            report.write("\n".join(lines))
        temporary_path.replace(path)
    finally:
        temporary_path.unlink(missing_ok=True)


def remove_empty_codex_parent(path: Path, codex_app_root: Path) -> None:
    parent = path.parent
    if parent.parent != codex_app_root.resolve() or not re.fullmatch(r"[0-9a-fA-F]{4}", parent.name):
        return
    try:
        parent.rmdir()
    except FileNotFoundError:
        pass
    except OSError as exc:
        if exc.errno not in (errno.ENOTEMPTY, errno.EEXIST):
            raise


def cleanup(settings: Settings, categories: dict[str, list[AuditRow]]) -> CleanupResult:
    result = CleanupResult()
    if categories["STALE_METADATA"]:
        try:
            proc = git(settings.repo, ["worktree", "prune", "--verbose"])
            result.pruned.extend((proc.stdout + proc.stderr).splitlines())
        except (GitError, OSError) as exc:
            result.failures.append(f"worktree prune | {exc}")

    for category in REMOVABLE_WORKTREES:
        for row in categories[category]:
            try:
                entries = parse_worktrees(settings.repo)
                entry = next((entry for entry in entries if entry.path == row.path), None)
                if entry is None or entry.branch != row.branch:
                    continue
                current_category, _ = inspect_worktree(
                    settings, entry, protected_worktrees(settings, entries), develop_tip(settings.repo), time.time()
                )
                if current_category != category:
                    continue
                path = entry.path.resolve()
                git(settings.repo, ["worktree", "remove", str(entry.path)])
                result.removed.append(row.label())
                remove_empty_codex_parent(path, settings.codex_app_root)
            except (RuntimeError, OSError) as exc:
                result.failures.append(f"{row.label()} | {exc}")

    try:
        branches = inspect_branches(settings, parse_worktrees(settings.repo), develop_tip(settings.repo))
    except (GitError, OSError) as exc:
        result.failures.append(f"ブランチの再判定 | {exc}")
        return result
    for row in branches["REMOVABLE_BRANCH"]:
        try:
            git(settings.repo, ["branch", "-d", "--", row.branch])
            result.deleted.append(row.label())
        except (GitError, OSError) as exc:
            result.failures.append(f"{row.label()} | {exc}")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Claude と Codex の作業ツリーとブランチを監査・整理します。")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[4], help="対象の Git リポジトリ。")
    parser.add_argument("--worktree-root", type=Path, action="append", help="所有ルート。複数指定可。")
    parser.add_argument("--claude-root", type=Path, help="Claude アプリの作業ツリールート。")
    parser.add_argument("--codex-app-root", type=Path, help="Codex アプリの作業ツリールート。")
    parser.add_argument("--prefix", action="append", help="対象のブランチ接頭辞。複数指定可。")
    parser.add_argument("--min-idle-days", type=float, default=7, help="削除前に必要な無活動日数。既定は 7 日。")
    parser.add_argument("--execute", action="store_true", help="実際に削除する。")
    parser.add_argument("--write-management", action="store_true", help="管理ファイルを更新する。")
    parser.add_argument("--management-file", type=Path, help="管理ファイルの出力先。")
    args = parser.parse_args()
    if not math.isfinite(args.min_idle_days) or args.min_idle_days < 0:
        parser.error("--min-idle-days は 0 以上の有限な日数を指定してください。")

    try:
        selected_repo = Path(git_out(args.repo.resolve(), ["rev-parse", "--show-toplevel"])).resolve()
        repo = parse_worktrees(selected_repo)[0].path.resolve()
        settings = Settings(
            repo=repo,
            selected_repo=selected_repo,
            cwd=Path.cwd().resolve(),
            worktree_roots=tuple(Path(os.path.abspath(root)) for root in (
                args.worktree_root or [repo.parent / "AstralRecord-Worktrees"]
            )),
            claude_root=Path(os.path.abspath(args.claude_root or repo / ".claude" / "worktrees")),
            codex_app_root=Path(os.path.abspath(args.codex_app_root or Path.home() / ".codex" / "worktrees")),
            prefixes=tuple(args.prefix or ("codex/", "claude/")),
            min_idle_days=args.min_idle_days,
        )
        develop = develop_tip(repo)
        main_branch = git_out(repo, ["branch", "--show-current"]) or "(detached)"
        categories = audit(settings, develop)
    except (RuntimeError, OSError) as exc:
        print(f"エラー: リポジトリを監査できません: {exc}", file=sys.stderr)
        return 2

    mode = "execute" if args.execute else "dry-run"
    print(f"repo: {repo}")
    print(f"mode: {mode}")
    print(f"develop: {develop}")
    print(f"main_branch: {main_branch}")
    for root in settings.worktree_roots:
        print(f"worktree_root: {root}")
    print(f"claude_root: {settings.claude_root}")
    print(f"codex_app_root: {settings.codex_app_root}")
    print(f"prefix: {', '.join(settings.prefixes)}")
    print(f"min_idle_days: {settings.min_idle_days:g}")
    for category, rows in categories.items():
        print_values(category, [row.label() for row in rows])

    result = None
    if args.execute:
        if main_branch != "develop":
            print(f"警告: メインワークスペースの現在のブランチは {main_branch} です。", file=sys.stderr)
        try:
            if not worktree_clean(repo):
                print("警告: メインワークスペースに未コミットの変更があります。", file=sys.stderr)
        except (GitError, OSError) as exc:
            print(f"警告: メインワークスペースの変更を確認できません: {exc}", file=sys.stderr)

        result = cleanup(settings, categories)
        print_values("PRUNED_METADATA", result.pruned)
        print_values("REMOVED_WORKTREE", result.removed)
        print_values("DELETED_BRANCH", result.deleted)
        print_values("FAILED", result.failures)

    exit_code = 1 if result and result.failures else 0
    if args.write_management:
        try:
            if result is not None:
                develop = develop_tip(repo)
                categories = audit(settings, develop)
            management_file = (args.management_file or settings.worktree_roots[0] / "WORKTREE_MANAGEMENT.md").resolve()
            write_management_report(management_file, settings, develop, main_branch, categories, result)
            print(f"management_file: {management_file}")
        except (RuntimeError, OSError) as exc:
            print(f"エラー: 管理ファイルを更新できません: {exc}", file=sys.stderr)
            return exit_code or 2

    print(f"result: {mode} {'一部失敗' if exit_code else '成功'}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
