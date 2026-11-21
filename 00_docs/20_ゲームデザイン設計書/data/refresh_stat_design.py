"""設計値の YAML からステータス関連の HTML を同期する。"""

import argparse
import json
import re
from decimal import Decimal
from html import escape
from pathlib import Path

import yaml


DATA = Path(__file__).resolve().parent
SLOTS = ("HEAD", "CHEST", "LEGS", "FEET")
STATUSES = ("DEFENSE", "VITALITY")
MARKER = re.compile(r"<!-- stat:([a-zA-Z0-9_.]+) -->.*?<!-- /stat -->", re.DOTALL)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load(name):
    path = DATA / f"v1.{name}.yml"
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    require(type(data["schemaVersion"]) is int and data["schemaVersion"] == 1,
            f"{path.name} の schemaVersion は 1 が必要です")
    return data


def target_curves(targets):
    curves = {}
    for status, variants in {
        "DEFENSE": ("standard",),
        "VITALITY": ("standard", "specialized"),
    }.items():
        levels = [1, 25, 50, 100] if status == "DEFENSE" else [1, 25, 50, 75, 100]
        for variant in variants:
            samples = targets[status][variant]
            require(sorted(samples) == levels and all(type(n) is int for n in samples.values()),
                    f"{status}.{variant} のレベル点または値が無効です")
        curves[status] = {variant: [targets[status][variant][lv] for lv in levels] for variant in variants}
        curves[status]["levels"] = levels
    return curves


def allocate_status(build, targets):
    level = build["playerLevel"]
    goal = {status: targets[status]["standard"][level] for status in STATUSES}
    grants = build["grants"]
    job = grants["currentClass"]
    nodes = build["tree"]["nodeOrder"]
    require(all(isinstance(node, str) and node.isdigit() for node in nodes)
            and len(set(nodes)) == len(nodes) and grants["tree"].keys() <= set(nodes),
            "ツリーの取得順または付与ノードが無効です")
    rows = {source: dict(grants[source]) for source in ("sharedBase", "playerLevel", "armor", "other")}
    rows["currentClass"] = {
        status: Decimal(str(job["base"][status]))
        + Decimal(str(job["perLevel"][status])) * (build["currentClass"]["level"] - 1)
        for status in STATUSES
    }
    rows["tree"] = {status: sum(node[status] for node in grants["tree"].values()) for status in STATUSES}
    vitality = sum(row["VITALITY"] for row in rows.values())
    require(vitality == goal["VITALITY"], "構成の VITALITY が標準目標と一致しません")
    factor = build["derived"]["DEFENSE"]["VITALITY"]
    derived = vitality * factor
    direct = goal["DEFENSE"] - derived
    without_armor = sum(row["DEFENSE"] for source, row in rows.items() if source != "armor")
    budget = direct - without_armor
    require(rows["armor"]["DEFENSE"] == "remainder" and budget >= 0 and budget % 1 == 0,
            "装備の DEFENSE に非負整数の残余を配分できません")
    rows["armor"]["DEFENSE"] = int(budget)
    total_defense = sum(row["DEFENSE"] for row in rows.values()) + derived
    require(total_defense == goal["DEFENSE"], "構成の DEFENSE が標準目標と一致しません")

    values = {
        "playerLevel": level, "mode": build["mode"], "quality": {"standard": "基準個体"}[build["armor"]["quality"]],
        "enhanceLevel": build["armor"]["enhanceLevel"], "tree": build["tree"]["id"],
        "nodeOrder": "、".join(nodes),
        "goalDefense": total_defense, "vitality": vitality, "factor": factor,
        "derived": derived, "direct": direct, "withoutArmor": without_armor,
    }
    for key in ("currentClass", "ancestorClass"):
        values[f"{key}.id"] = build[key]["id"]
        values[f"{key}.level"] = build[key]["level"]
    for key, points in build["tree"]["points"].items():
        require(0 <= points["spent"] <= points["available"], f"{key} の必要ポイントが取得可能量を超えています")
        values[f"points.{key}"] = f'{points["spent"]} / {points["available"]}'
    values.update({f"{source}.{status}": row[status] for source, row in rows.items() for status in STATUSES})
    values.update({f"job.{term}.{status}": job[term][status] for term in ("base", "perLevel") for status in STATUSES})
    values["treeRows"] = "\n            ".join(
        f'<tr><th scope="row">{node_id}</th>'
        f'<td>{display(grants["tree"][node_id]["DEFENSE"])}</td>'
        f'<td>{display(grants["tree"][node_id]["VITALITY"])}</td></tr>'
        for node_id in nodes if node_id in grants["tree"]
    )
    return values


def allocate_equipment(profile, allocation):
    weights = profile["armorWeights"]
    tie_order = profile["tieOrder"]
    slot = profile["slot"]
    main = profile["main"]
    required_level = profile["requiredLevel"]
    progression = profile["progression"]
    require(type(required_level) is int and 0 <= required_level <= allocation["playerLevel"],
            "装備条件のレベルは標準構成で満たす必要があります")
    require(type(progression) is int and progression > 0, "防具の入手段階は正の整数が必要です")
    require(set(weights) == set(SLOTS) and slot in SLOTS, "未登録の部位、または部位の過不足があります")
    require(profile["rounding"] == "largestRemainder" and tuple(tie_order) == SLOTS,
            "このプロファイルの丸め方または同点順が無効です")
    require(main == {"status": "DEFENSE", "type": "FLAT"} and profile["secondary"] == [],
            "このプロファイルの主・副ステータスが無効です")
    require(slot == "CHEST", "標準共通防御型の候補は CHEST が必要です")
    require(all(type(n) is int and n > 0 for n in weights.values()) and sum(weights.values()) == 100,
            "部位配分は正の整数で合計 100 が必要です")
    budget = allocation["armor.DEFENSE"]
    quotients = {part: divmod(budget * weights[part], 100) for part in SLOTS}
    nominal = {part: quotients[part][0] for part in SLOTS}
    remaining = budget - sum(nominal.values())
    priority = sorted(tie_order, key=lambda part: -quotients[part][1])
    for part in priority[:remaining]:
        nominal[part] += 1
    require(sum(nominal.values()) == budget, "防具の基準値合計が配分と一致しません")

    value = nominal[slot]
    random = profile["random"]
    low, high, step = (random[key] for key in ("offsetMin", "offsetMax", "step"))
    require(set(random) == {"offsetMin", "offsetMax", "step"}
            and all(type(n) is int for n in (low, high, step))
            and type(value) is int and low < 0 < high and low == -high
            and step > 0 and high % step == 0 and (high - low) // step + 1 <= 1_000_000,
            "単一の整数基準値に対する対称な離散抽選を指定してください")
    require(value + low >= 0, "抽選後の装備 DEFENSE が負になります")
    minimum, maximum = value + low, value + high
    equipment = {
        "slot": slot,
        "requiredLevel": required_level,
        "stats": [{**main, "value": value, "random": random}],
    }
    snippet = yaml.safe_dump(
        {"rarity": profile["rarity"], "equipment": equipment}, sort_keys=False
    ).rstrip()
    values = {
        "progression": progression, "requiredLevel": required_level,
        "rarity": profile["rarity"], "slot": slot, "main": main["status"], "modifier": main["type"],
        "expected": (Decimal(minimum) + maximum) / 2, "minimum": minimum, "maximum": maximum,
        "offsetMin": low, "offsetMax": high, "step": step, "remaining": remaining,
        "totalMin": allocation["goalDefense"] + low, "totalMax": allocation["goalDefense"] + high,
        "budget": budget, "fixedArmor": budget - value, "yaml": snippet,
    }
    for part, (floor, _) in quotients.items():
        values[f"{part}.weight"] = weights[part]
        values[f"{part}.ideal"] = Decimal(budget * weights[part]) / 100
        values[f"{part}.floor"] = floor
        values[f"{part}.extra"] = int(nominal[part] > floor)
        values[f"{part}.value"] = nominal[part]
    return values


def display(value):
    if isinstance(value, Decimal):
        return format(value.normalize(), "f")
    return escape(str(value))


def render_stat_values(html, values):
    def replace(match):
        key = match[1]
        value = values[key] if key == "treeRows" else display(values[key])
        return f"<!-- stat:{key} -->{value}<!-- /stat -->"

    return MARKER.sub(replace, html)


def refresh(check):
    target_data = load("status_targets")
    require(target_data["interpolation"] == "linear", "ステータス目標の補間方式は linear が必要です")
    targets = target_data["targets"]
    curves = target_curves(targets)
    builds = load("status_allocation")["builds"]
    (profile_id, profile), = load("equipment_stat_rules")["profiles"].items()
    build_id = profile["buildId"]
    require(build_id in builds, f"装備プロファイルの構成参照 {build_id} が未解決です")
    (allocation_id, build), = builds.items()
    allocation = allocate_status(build, targets)
    values = {"buildId": allocation_id, "profileId": profile_id, **allocation,
              **allocate_equipment(profile, allocation)}
    # 曲線は JavaScript 用の JSON、固定レベル資料は HTML 本文へ埋め込む。
    curve_block = re.compile(r"(?<=\n  // stat-targets\n).*?(?=^  // /stat-targets\n)", re.DOTALL | re.MULTILINE)
    embedded = "  const statTargets = " + json.dumps(curves, ensure_ascii=False) + ";\n"
    updates = {}
    for filename in ("02-ステータス指標.html", "03-ステータス配分基準.html", "04-装備ステータス基準.html"):
        path = DATA.parent / filename
        original = path.read_text(encoding="utf-8")
        if filename.startswith("02"):
            updated, replacements = curve_block.subn(lambda _: embedded, original)
            require(replacements == 1, f"{filename} の曲線ブロックが無効です")
        else:
            require(MARKER.search(original), f"{filename} の同期対象がありません")
            updated = render_stat_values(original, values)
        if updated != original:
            updates[path] = updated
    for path, updated in updates.items():
        if check:
            print(f"要同期 {path.name}")
        else:
            path.write_text(updated, encoding="utf-8", newline="\n")
            print(f"更新 {path.name}")
    return int(check and bool(updates))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="差分を調べる")
    args = parser.parse_args()
    try:
        return refresh(args.check)
    except (OSError, ValueError, KeyError, TypeError, ArithmeticError, yaml.YAMLError) as error:
        parser.exit(1, f"同期エラー {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
