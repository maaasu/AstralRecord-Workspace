"""設計値の YAML からステータス関連の HTML を同期する。"""

import argparse
import json
import re
import subprocess
from decimal import ROUND_HALF_UP, Decimal
from html import escape
from pathlib import Path

import yaml


DATA = Path(__file__).resolve().parent
SLOTS = ("head", "chest", "legs", "feet")
LEVEL_COUNT = 100
CENTS = 100
PLAYER_GROUP_COUNTS = (3, 4, 6, 5, 7, 15, 9, 15, 25, 10)
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
        "commonDefense": ("standard",),
        "vitality": ("standard", "specialized"),
    }.items():
        levels = [1, 25, 50, 100] if status == "commonDefense" else [1, 25, 50, 75, 100]
        for variant in variants:
            samples = targets[status][variant]
            require(sorted(samples) == levels and all(type(n) is int for n in samples.values()),
                    f"{status}.{variant} のレベル点または値が無効です")
        curves[status] = {variant: [targets[status][variant][lv] for lv in levels] for variant in variants}
        curves[status]["levels"] = levels
    return curves


def read_model(html):
    result = subprocess.run(
        ["node", str(DATA / "read_stat_targets.cjs")], input=html.encode("utf-8"),
        capture_output=True, check=False,
    )
    require(result.returncode == 0, result.stderr.decode("utf-8").strip())
    return json.loads(result.stdout, parse_float=Decimal, parse_int=Decimal)


def model_series(panels, expected_count, *, player=False):
    require(len(panels) == expected_count, "02のパネル数が無効です")
    titles = set()
    series = {}
    for panel in panels:
        title = panel["title"]
        require(title and title not in titles, f"02のパネル名が空か重複しています {title}")
        titles.add(title)
        labels = set()
        for data in panel["series"]:
            label, values = data["label"], data["values"]
            require(label and label not in labels, f"02の系列名が空か重複しています {title}・{label}")
            labels.add(label)
            require(len(values) == LEVEL_COUNT and all(isinstance(n, Decimal) and n.is_finite() for n in values),
                    f"02の系列に欠落または非有限値があります {title}・{label}")
            if not player or label in ("標準", "上限", "共通", "種別"):
                series[title, label] = (panel["category"], panel["unit"], values)
        require(any(panel_title == title for panel_title, _ in series), f"対象系列がありません {title}")
    return series


def cents(value):
    return int((value * CENTS).to_integral_value(rounding=ROUND_HALF_UP))


def largest_remainder(amount, weights):
    shares = [divmod(amount * weight, sum(weights)) for weight in weights]
    grants = [whole for whole, _ in shares]
    priority = sorted(range(len(weights)), key=lambda i: -shares[i][1])
    for i in priority[:amount - sum(grants)]:
        grants[i] += 1
    return grants


def reference_key(rule):
    return rule["title"], rule.get("series", "標準")


def player_rows(groups, model):
    registered = set()
    rows = {}
    group_names = set()
    counts = []
    for group in groups:
        group_name = group["name"]
        require(group_name and group_name not in group_names, f"分類名が空か重複しています {group_name}")
        group_names.add(group_name)
        count = 0
        for rule in group["panels"]:
            key = reference_key(rule)
            require(key in model, f"02のプレイヤー系列が未定義です {key}")
            registered.add(key)
            names = rule.get("names", [key[0]])
            category, unit, values = model[key]
            for name in names:
                require(name and name not in rows, f"表示名が空か重複しています {name}")
                rows[name] = {
                    "group": group_name, "category": category, "unit": unit or "点",
                    "fixed": key[1] == "上限", "values": values, "rule": rule,
                }
            count += len(names)
        counts.append(count)
    require(registered == set(model), f"02の未登録のプレイヤー系列 {sorted(set(model) - registered)}")
    require(tuple(counts) == PLAYER_GROUP_COUNTS, f"プレイヤーの分類別の行数が無効です {counts}")
    return rows


def allocate_player(rows):
    allocations = {}
    attacks = []
    for name, row in rows.items():
        rule = row["rule"]
        mode = rule.get("mode", "weighted")
        weights = rule.get("weights", [])
        require(mode in ("weighted", "equipment", "fixed", "zero", "derived", "attack"),
                f"未登録の配分方法 {name}・{mode}")
        if mode in ("weighted", "equipment"):
            require(len(weights) == 3 and all(type(n) is int and n >= 0 for n in weights) and sum(weights),
                    f"配分には3欄の非負整数の重みが必要です {name}")
        else:
            require(not weights, f"成長配分の対象外に重みがあります {name}")
        if mode == "equipment":
            require(not any(weights[:2]) and "derived" not in rule, f"装備のみの配分に別の供給元があります {name}")
        if mode == "derived":
            require("derived" in rule, f"派生元が指定されていません {name}")
        require(not row["fixed"] or mode == "fixed", f"上限は固定値が必要です {name}")
        goals = [cents(value) for value in row["values"]]
        if mode == "attack":
            attacks.append((name, goals, rule["sources"]))
            continue
        if mode == "fixed":
            require(len(set(row["values"])) == 1 and "derived" not in rule, f"固定値に変動があります {name}")
        if mode == "zero":
            require(not any(row["values"]) and "derived" not in rule, f"標準0に付与があります {name}")
        bonuses = [Decimal(0)] * LEVEL_COUNT
        if "derived" in rule:
            derived = rule["derived"]
            factor = Decimal(str(derived["factor"]))
            require(factor.is_finite() and factor >= 0, f"派生係数が無効です {name}")
            bonuses = [value * factor for value in rows[derived["stat"]]["values"]]
            if "cap" in derived:
                base = row["values"][0] - bonuses[0]
                limits = rows[derived["cap"]]["values"]
                bonuses = [min(bonus, limit - base) for bonus, limit in zip(bonuses, limits)]
        derived_cents = list(map(cents, bonuses))
        initial = 0 if mode == "equipment" else goals[0] - derived_cents[0]
        levels = []
        for level, (goal, bonus) in enumerate(zip(goals, derived_cents), 1):
            remainder = goal - initial - bonus
            require(remainder >= 0, f"配分できる残余が負です {name} Lv.{level}")
            require(weights or remainder == 0, f"残余の配分に重みがありません {name} Lv.{level}")
            grants = largest_remainder(remainder, weights) if weights else [0] * 3
            levels.append((goal, initial, *grants, bonus))
        allocations[name] = levels

    for name, goals, sources in attacks:
        require(len(sources) == 2 and len(set(sources)) == 2, f"共通と使用種別の攻撃力を指定してください {name}")
        levels = []
        for goal, common, typed in zip(goals, *(allocations[source] for source in sources)):
            direct = [a + b for a, b in zip(common[1:-1], typed[1:-1])]
            levels.append((goal, *direct, goal - sum(direct)))
        allocations[name] = levels

    for name, levels in allocations.items():
        require(len(levels) == LEVEL_COUNT, f"配分に欠レベルがあります {name}")
        for level, (goal, *parts) in enumerate(levels, 1):
            require(min(parts) >= 0 and sum(parts) == goal,
                    f"配分が負か、合計が02の目標値と一致しません {name} Lv.{level}")
            if rows[name]["unit"] == "回":
                require(all(value % CENTS == 0 for value in [goal, *parts]), f"回数は整数が必要です {name} Lv.{level}")
    require(sum(row["rule"].get("mode") == "zero" for row in rows.values()) == 34, "標準0の行数が無効です")
    require(sum(row["fixed"] for row in rows.values()) == 5, "固定上限の行数が無効です")
    return allocations


def allocation_data(rows, allocations, mob_rules, mob_model):
    bank = []
    indices = {}

    def store(values):
        if values not in indices:
            indices[values] = len(bank)
            bank.append(values[0] if len(set(values)) == 1 else values)
        return indices[values]

    player = []
    for name, row in rows.items():
        rule = row["rule"]
        mode = rule.get("mode", "weighted")
        levels = allocations[name]
        stat = {
            "name": name, "group": row["group"], "category": row["category"], "unit": row["unit"],
            "mode": mode, "goal": store(tuple(level[0] for level in levels)),
        }
        if mode == "attack":
            stat["sources"] = rule["sources"]
        else:
            stat["initial"] = levels[0][1]
            stat["bonus"] = store(tuple(level[-1] for level in levels))
            if "weights" in rule:
                stat["weights"] = rule["weights"]
        player.append(stat)
    mob = []
    remaining = dict(mob_model)
    names = set()
    for rule in mob_rules:
        key, name = reference_key(rule), rule["name"]
        require(key in remaining, f"02のモブ系列が未定義か重複しています {key}")
        require(name and name not in names, f"標準モブの表示名が空か重複しています {name}")
        names.add(name)
        category, unit, values = remaining.pop(key)
        mob.append({"name": name, "category": category, "unit": unit or "点", "column": store(tuple(map(cents, values)))})
    require(not remaining and len(mob) == 11, f"標準モブの系列数が無効です。未登録 {list(remaining)}")
    return {"player": player, "mob": mob, "bank": bank}


def equipment_reference(build, allocations):
    level = build["playerLevel"]
    require(type(level) is int and 1 <= level <= LEVEL_COUNT, "装備プロファイルの参照Lvが無効です")
    total, _, _, _, armor, _ = allocations["共通防御"][level - 1]
    require(armor % CENTS == total % CENTS == 0, "参照Lvの共通防御と装備への配分は整数が必要です")
    return {
        "playerLevel": level, "role": build["role"],
        "goalDefense": total // CENTS, "armor.commonDefense": armor // CENTS,
    }


def allocate_equipment(profile, allocation):
    weights = profile["armorWeights"]
    tie_order = profile["tieOrder"]
    slot = profile["slot"]
    primary_stat = profile["primaryStat"]
    require(primary_stat == "commonDefense" and profile["secondaryStats"] == [],
            "標準共通防御型は共通防御を主ステータスに指定してください")
    reference_level = allocation["playerLevel"]
    required_level = profile["requiredPlayerLevel"]
    require(type(required_level) is int and 0 <= required_level <= reference_level,
            "装備条件のレベルは標準構成で満たす必要があります")
    require(set(weights) == set(SLOTS) and slot in SLOTS, "未登録の部位、または部位の過不足があります")
    require(profile["rounding"] == "largestRemainder" and tuple(tie_order) == SLOTS,
            "このプロファイルの丸め方または同点順が無効です")
    require(slot == "chest", "標準共通防御型の候補は胴部位が必要です")
    require(all(type(n) is int and n > 0 for n in weights.values()) and sum(weights.values()) == 100,
            "部位配分は正の整数で合計 100 が必要です")
    budget = allocation["armor.commonDefense"]
    quotients = {part: divmod(budget * weights[part], 100) for part in SLOTS}
    nominal = {part: quotients[part][0] for part in SLOTS}
    remaining = budget - sum(nominal.values())
    priority = sorted(tie_order, key=lambda part: -quotients[part][1])
    for part in priority[:remaining]:
        nominal[part] += 1
    require(sum(nominal.values()) == budget, "防具の基準値合計が配分と一致しません")

    value = nominal[slot]
    offsets = profile["rollOffsets"]
    low, high, step = (offsets[key] for key in ("min", "max", "step"))
    require(set(offsets) == {"min", "max", "step"}
            and all(type(n) is int for n in (low, high, step))
            and type(value) is int and low < 0 < high and low == -high
            and step > 0 and high % step == 0,
            "単一の整数基準値に対する対称な離散抽選を指定してください")
    require(value + low >= 0, "抽選後の防具の共通防御が負になります")
    minimum, maximum = value + low, value + high
    equipment = {
        "slot": slot,
        "referencePlayerLevel": reference_level,
        "requiredPlayerLevel": required_level,
        "rarity": profile["rarity"],
        "stats": {primary_stat: {"base": value, "rollOffsets": offsets}},
    }
    snippet = yaml.safe_dump(equipment, sort_keys=False).rstrip()
    values = {
        "referencePlayerLevel": reference_level, "requiredPlayerLevel": required_level,
        "rarity": profile["rarity"], "slot": slot,
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
        value = display(values[key])
        return f"<!-- stat:{key} -->{value}<!-- /stat -->"

    return MARKER.sub(replace, html)


def embed(html, start, end, content):
    block = re.compile(rf"(?<=\n{re.escape(start)}\n).*?(?=^{re.escape(end)}\n)", re.DOTALL | re.MULTILINE)
    updated, replacements = block.subn(lambda _: content, html)
    require(replacements == 1, f"同期ブロックが無効です {start}")
    return updated


def embedded_allocation(data):
    def line(value):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")

    sections = []
    for key, records in data.items():
        sections.append(f'{line(key)}:[\n' + ",\n".join(map(line, records)) + "\n]")
    return "{\n" + ",\n".join(sections) + "\n}\n"


def refresh(check):
    target_data = load("status_targets")
    require(target_data["interpolation"] == "linear", "ステータス目標の補間方式は linear が必要です")
    curves = target_curves(target_data["targets"])
    design = load("status_allocation")
    target_path = DATA.parent / "02-ステータス指標.html"
    target_html = target_path.read_text(encoding="utf-8")
    embedded = "  const statTargets = " + json.dumps(curves, ensure_ascii=False) + ";\n"
    updated_targets = embed(target_html, "  // stat-targets", "  // /stat-targets", embedded)
    model = read_model(updated_targets)
    player_model = model_series(model["player"], 55, player=True)
    mob_model = model_series(model["mob"], 7)
    rows = player_rows(design["groups"], player_model)
    allocations = allocate_player(rows)
    data = allocation_data(rows, allocations, design["mob"], mob_model)

    (profile_id, profile), = load("equipment_stat_rules")["profiles"].items()
    build_id = profile["buildId"]
    builds = design["builds"]
    require(build_id in builds, f"装備プロファイルの構成参照 {build_id} が未解決です")
    allocation = equipment_reference(builds[build_id], allocations)
    values = {"buildId": build_id, "profileId": profile_id, **allocation,
              **allocate_equipment(profile, allocation)}
    allocation_path = DATA.parent / "03-ステータス配分基準.html"
    allocation_html = allocation_path.read_text(encoding="utf-8")
    updated_allocation = embed(
        allocation_html, '<script type="application/json" id="stat-allocation-data">',
        "</script>", embedded_allocation(data),
    )
    equipment_path = DATA.parent / "04-装備ステータス基準.html"
    equipment_html = equipment_path.read_text(encoding="utf-8")
    require(MARKER.search(equipment_html), f"{equipment_path.name} の同期対象がありません")
    updated_equipment = render_stat_values(equipment_html, values)
    updates = {
        path: updated
        for path, original, updated in (
            (target_path, target_html, updated_targets),
            (allocation_path, allocation_html, updated_allocation),
            (equipment_path, equipment_html, updated_equipment),
        )
        if updated != original
    }
    for path, updated in updates.items():
        if check:
            print(f"要同期 {path.name}")
        else:
            with path.open(encoding="utf-8", newline="") as source:
                newline = "\r\n" if source.readline().endswith("\r\n") else "\n"
            path.write_text(updated, encoding="utf-8", newline=newline)
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
