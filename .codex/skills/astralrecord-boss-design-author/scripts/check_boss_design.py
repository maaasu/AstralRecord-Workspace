"""ボス設計書が下位モデルへ渡せる状態かを機械的に検査する。

使い方:
    python .codex/skills/astralrecord-boss-design-author/scripts/check_boss_design.py <設計書.md>

終了コード: 0 = エラーなし、1 = エラーあり、2 = 引数・ファイル不正。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REQUIRED_SECTIONS = [
    "## 0. 概要",
    "## 1. 実装範囲と別タスク",
    "## 2. 座標と寸法",
    "## 3. マスターデータ",
    "## 4. 状態とフェーズ",
    "## 5. 技の仕様",
    "## 6. メッセージ・ログ・パーティクル",
    "## 7. 統合箇所",
    "## 8. 実装手順",
    "## 9. 境界と失敗時の挙動",
    "## 10. 完了条件",
    "## 11. 残す判断",
    "## 12. 確認事項",
]

# 実装担当に判断を残す語。見つかればエラー。
VAGUE_ERRORS = ["TBD", "TODO", "未定", "要検討", "検討する", "適宜", "適度", "いい感じ", "必要に応じて", "お任せ", "任意で"]
# 文脈次第で曖昧になる語。見つかれば警告。
VAGUE_WARNINGS = ["など", "程度", "くらい", "ぐらい", "想定", "目安", "なるべく", "できれば"]

SKILL_ITEM_LABELS = ["予兆", "判定", "効果", "回避・対処", "統合版表示", "手本"]
BOUNDARY_ROWS = ["参加者が0人", "死亡", "ログアウト", "一時的に無効", "LEASHED", "制限時間", "プラグイン停止", "同時に複数", "統合版", "例外"]


def strip_code(text: str) -> str:
    """コードブロックとインラインコードを空白へ置き換え、行番号を保つ。"""
    text = re.sub(r"```.*?```", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"`[^`\n]*`", "", text)


def split_sections(text: str) -> dict[str, str]:
    sections: dict[str, str] = {}
    current = None
    buffer: list[str] = []
    for line in text.splitlines():
        if line.startswith("## "):
            if current is not None:
                sections[current] = "\n".join(buffer)
            current = line.strip()
            buffer = []
        elif current is not None:
            buffer.append(line)
    if current is not None:
        sections[current] = "\n".join(buffer)
    return sections


def integration_ids(repo_root: Path) -> list[str]:
    shared = repo_root / ".codex/skills/_shared/boss-integration-points.md"
    if not shared.is_file():
        return []
    return sorted(set(re.findall(r"^\| (I\d{2}) \|", shared.read_text(encoding="utf-8"), flags=re.M)))


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) != 2:
        print("使い方: check_boss_design.py <設計書.md>")
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"ファイルがありません: {path}")
        return 2

    raw = path.read_text(encoding="utf-8")
    prose = strip_code(raw)
    sections = split_sections(raw)
    errors: list[str] = []
    warnings: list[str] = []

    for heading in REQUIRED_SECTIONS:
        if heading not in sections:
            errors.append(f"必須の章がありません: {heading}")

    for number, line in enumerate(prose.splitlines(), start=1):
        if re.search(r"<[^<>\n]{1,60}>", line):
            errors.append(f"{number}行目: テンプレートの <...> が残っています: {line.strip()}")
        for word in VAGUE_ERRORS:
            if word in line:
                errors.append(f"{number}行目: 判断を残す語「{word}」: {line.strip()}")
        for word in VAGUE_WARNINGS:
            if word in line:
                warnings.append(f"{number}行目: 曖昧になり得る語「{word}」: {line.strip()}")

    skills = sections.get("## 5. 技の仕様", "")
    blocks = re.split(r"^### ", skills, flags=re.M)[1:]
    if "## 5. 技の仕様" in sections and not blocks:
        errors.append("5章に「### 技<n>: <技名>」がありません")
    for block in blocks:
        title = block.splitlines()[0].strip()
        for label in SKILL_ITEM_LABELS:
            if not re.search(rf"^\|\s*{re.escape(label)}\s*\|", block, flags=re.M):
                errors.append(f"5章「{title}」に「{label}」の行がありません")
        if "tick 0" not in block:
            errors.append(f"5章「{title}」にタイムライン（tick 0 から）がありません")

    repo_root = Path(__file__).resolve().parents[4]
    integration = sections.get("## 7. 統合箇所", "")
    for item in integration_ids(repo_root):
        row = re.search(rf"^\|\s*{item}\s*\|\s*([^|]*)\|", integration, flags=re.M)
        if row is None:
            errors.append(f"7章に統合箇所 {item} の判定行がありません")
        elif row.group(1).strip() not in ("対象", "対象外"):
            errors.append(f"7章 {item} の判定は「対象」か「対象外」にしてください: {row.group(1).strip()}")

    if "## 8. 実装手順" in sections and not re.search(r"^### 段階\d+", sections["## 8. 実装手順"], flags=re.M):
        errors.append("8章に「### 段階<n>: <名前>」がありません")

    boundary = sections.get("## 9. 境界と失敗時の挙動", "")
    for key in BOUNDARY_ROWS:
        if key not in boundary:
            errors.append(f"9章に「{key}」を含む行がありません")

    if "## 12. 確認事項" in sections:
        rest = strip_code(sections["## 12. 確認事項"]).strip()
        if rest != "なし":
            errors.append("12章の確認事項が「なし」ではありません。未決事項を解消してから渡してください")

    for message in errors:
        print(f"エラー: {message}")
    for message in warnings:
        print(f"警告: {message}")
    print(f"結果: エラー {len(errors)} 件 / 警告 {len(warnings)} 件")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
