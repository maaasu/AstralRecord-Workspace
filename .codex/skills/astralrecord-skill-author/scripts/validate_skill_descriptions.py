"""スキル説明の共通書式と実装用語の混入を確認する（PyYAMLを使用）。"""

from __future__ import annotations

import argparse
from pathlib import Path
import re

import yaml


SUMMARY_PREFIX = "&8【概要】 &7"
CATEGORY_COLORS = {
    "効果": "b", "攻撃": "b", "参照": "b", "範囲": "b", "対象": "b",
    "移動": "b", "持続": "b",
    "回復": "a", "防御": "a", "強化": "a",
    "状態": "d", "弱体": "d",
    "条件": "6", "継承": "6", "制限": "6",
}
DETAIL_PREFIX = re.compile(r"^&([0-9a-f])【([^】]+)】 &f(.+)$")
LEGACY_CODE = re.compile(r"[&§][0-9a-fk-or]", re.IGNORECASE)
PLACEHOLDER = re.compile(r"\{skill\.([A-Za-z0-9_.-]+)(?:\[(\d+)\])?(?::(integer|decimal|percent|seconds|list))?\}")
IMPLEMENTATION_TERMS = re.compile(
    r"BlockDisplay|END_ROD|\bBlock\b|HUD|アクションバー|当たり判定|"
    r"壁[をは]貫通しな|壁は越えない|遮蔽を確認|粒子を表示|サウンドを再生"
)


def check_line(text: object, location: str, summary: bool, values: dict) -> list[str]:
    """一行の書式と参照を検証し、人間が確認する場所付きエラーを返す。"""
    if not isinstance(text, str):
        return [f"{location}: 説明は文字列で指定してください"]
    issues = []
    if summary:
        if not text.startswith(SUMMARY_PREFIX):
            issues.append(f"{location}: 概要は {SUMMARY_PREFIX!r} で開始してください")
        elif LEGACY_CODE.search(text[len(SUMMARY_PREFIX):]):
            issues.append(f"{location}: 概要の本文は灰色のまま記載してください")
    else:
        match = DETAIL_PREFIX.fullmatch(text)
        if not match or CATEGORY_COLORS.get(match[2]) != match[1]:
            issues.append(f"{location}: 詳細は規定色の【分類】と白い本文で指定してください")
        else:
            active_color = "f"
            for code in LEGACY_CODE.findall(match[3]):
                color = code[-1].lower()
                if color not in {"a", "b", "d", "f"} or color != "f" and active_color != "f":
                    issues.append(f"{location}: 強調語は水色・紫・緑とし、直後に &f で本文の白へ戻してください")
                    break
                active_color = color
            if active_color != "f":
                issues.append(f"{location}: 強調語の直後に &f で本文の白へ戻してください")
    if re.search(r"[&§][el]", text, re.IGNORECASE):
        issues.append(f"{location}: 黄色と太字は数値プレースホルダーの共通表示処理に任せてください")
    plain = LEGACY_CODE.sub("", text)
    technical = IMPLEMENTATION_TERMS.search(plain)
    if technical:
        issues.append(f"{location}: 実装用語 {technical[0]!r} をRPGの効果説明へ整理してください")
    for match in PLACEHOLDER.finditer(text):
        key = match[1]
        if key not in values:
            issues.append(f"{location}: 未定義のプレースホルダー {match[0]}")
        if key in values and match[2] is not None:
            value = values[key]
            if not isinstance(value, list) or int(match[2]) >= len(value):
                issues.append(f"{location}: 配列要素を解決できないプレースホルダー {match[0]}")
    if "{skill." in PLACEHOLDER.sub("", text):
        issues.append(f"{location}: プレースホルダーの書式が不正です")
    return issues


def presentation_values(data: dict, params: dict) -> dict:
    """Pluginの基礎表示値と同じ条件で共通値・派生値を追加する。"""
    values = normalize_params(params)
    values.update(
        name=LEGACY_CODE.sub("", data.get("name") or ""),
        cooldownTicks=data.get("cooldownTicks", 0), level=1,
        maxLevel=data.get("maxLevel", 1), skillDamageIncrease=0.0, damageIncrease=0.0,
    )
    for key, alias in (("damageRatio", "effectiveDamageRatio"), ("chainDamageRatio", "effectiveChainDamageRatio")):
        value = values.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            values[alias] = value
    damage_ratios = values.get("damageRatios")
    if isinstance(damage_ratios, list):
        effective = [value for value in damage_ratios if isinstance(value, (int, float)) and not isinstance(value, bool)]
        values["effectiveDamageRatios"] = effective
        values["maxHits"] = len(effective)
        bounce_count = values.get("bounceCount")
        if isinstance(bounce_count, (int, float)) and not isinstance(bounce_count, bool):
            values["bounceCount"] = min(max(0, int(bounce_count)), max(0, len(effective) - 1))
    return values


def normalize_params(value: object) -> object:
    """SkillRepositoryと同じく辞書・配列のnullを再帰的に取り除く。"""
    if isinstance(value, dict):
        return {key: normalize_params(child) for key, child in value.items() if child is not None}
    if isinstance(value, list):
        return [normalize_params(child) for child in value if child is not None]
    return value


def validate_file(path: Path) -> list[str]:
    """YAML一件の概要・詳細・条件付き説明を同じ規則で検証する。"""
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8-sig"))
    except (OSError, yaml.YAMLError) as error:
        return [f"{path}: YAMLを読み込めません: {error}"]
    if not isinstance(data, dict):
        return [f"{path}: スキル定義はマッピングで指定してください"]
    params = data.get("params") or {}
    if not isinstance(params, dict):
        return [f"{path}: paramsはマッピングで指定してください"]
    values = presentation_values(data, params)
    issues = []
    description = data.get("description")
    if description:
        issues.extend(check_line(description, f"{path.name}:description", True, values))
    lore = data.get("lore") or []
    if not isinstance(lore, list):
        issues.append(f"{path.name}:lore: 配列で指定してください")
    else:
        for index, line in enumerate(lore):
            issues.extend(check_line(line, f"{path.name}:lore[{index}]", False, values))
    conditional = data.get("conditionalLore") or []
    if not isinstance(conditional, list):
        issues.append(f"{path.name}:conditionalLore: 配列で指定してください")
    else:
        for index, entry in enumerate(conditional):
            if not isinstance(entry, dict) or not isinstance(entry.get("lines", []), list):
                issues.append(f"{path.name}:conditionalLore[{index}]: linesは配列で指定してください")
                continue
            for line_index, line in enumerate(entry.get("lines", [])):
                issues.extend(check_line(line, f"{path.name}:conditionalLore[{index}].lines[{line_index}]", False, values))
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="検証するYAML。省略時は全スキル")
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[4])
    args = parser.parse_args()
    paths = args.paths or sorted((args.repo_root / "40_filebase/30.features.skill").rglob("*.yml"))
    if not paths:
        print("検証対象のスキルYAMLがありません")
        return 1
    issues = [issue for path in paths for issue in validate_file(path)]
    for issue in issues:
        print(issue)
    print(f"スキル説明: {len(paths)}ファイル / エラー{len(issues)}件")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
