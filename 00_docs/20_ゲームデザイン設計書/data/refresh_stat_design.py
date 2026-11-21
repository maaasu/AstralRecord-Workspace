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
ARMOR_SLOTS = ("頭", "胴", "脚", "足")
LEVEL_COUNT = 100
CENTS = 100
PLAYER_GROUP_COUNTS = (3, 4, 6, 5, 7, 15, 9, 15, 25, 10)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_design():
    path = DATA / "v1.stat_design.yml"
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    require(type(data["schemaVersion"]) is int and data["schemaVersion"] == 1,
            f"{path.name} の schemaVersion は 1 が必要です")
    return data


def read_model(html):
    reader = r"""
const fs = require("node:fs");
const vm = require("node:vm");

const html = fs.readFileSync(0, "utf8");
const blocks = [...html.matchAll(/^  \/\/ stat-model\n([\s\S]*?)^  \/\/ \/stat-model$/gm)];
if (blocks.length !== 1) throw new Error("02の計算モデルの範囲が無効です");

const source = blocks[0][1];
const panels = vm.runInNewContext(source + "\npanels", Object.create(null), {
  filename: "02-ステータス指標.html",
  timeout: 1000
});
const result = {player: [], mob: []};
for (const {grid, title, unit, series} of panels) {
  const owner = grid.split("-")[0];
  if (!Object.hasOwn(result, owner)) throw new Error(`未登録の章 ${grid}`);
  const targets = series.map(({label, read}) => ({
    label,
    values: Array.from({length: 100}, (_, index) => {
      const level = index + 1;
      const value = read(level);
      if (!Number.isFinite(value)) throw new Error(`${title}・${label} Lv.${level} の値が無効です`);
      return value;
    })
  }));
  result[owner].push({title, unit, series: targets});
}
process.stdout.write(JSON.stringify(result));
"""
    result = subprocess.run(
        ["node", "-e", reader], input=html.encode("utf-8"),
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
                series[title, label] = (panel["unit"], values)
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
            unit, values = model[key]
            for name in names:
                require(name and name not in rows, f"表示名が空か重複しています {name}")
                rows[name] = {
                    "group": group_name, "unit": unit or "点",
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


def allocation_data(rows, allocations):
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
            "name": name, "panel": rule["title"], "group": row["group"], "unit": row["unit"],
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
    return {"player": player, "bank": bank}


def validate_equipment(equipment, rows, allocations):
    unlock = equipment["unlockLevels"]
    require(all(type(unlock[key]) is int and 1 <= unlock[key] <= LEVEL_COUNT
                for key in ("advanced", "build", "lateBuild"))
            and unlock["build"] <= unlock["lateBuild"], "装備候補の解禁Lvが無効です")

    pools = equipment["pools"]
    for name, pool in pools.items():
        stats = pool["stats"]
        require(stats and len(stats) == len(set(stats)) and set(stats) <= rows.keys(),
                f"候補グループの内訳が空か、未定義・重複の指標があります {name}")

    excluded = [name for names in equipment["excluded"].values() for name in names]
    require(len(excluded) == len(set(excluded)) and set(excluded) <= rows.keys(),
            "装備から直接付与しない指標は、02の表示名で重複なく指定してください")

    row_names = set()
    candidates = set()
    standard_unlock = {}
    sections = equipment["sections"]
    require({"武器", "防具", "補助装備", "アクセサリ"} <= sections.keys(),
            "武器・防具・補助装備・アクセサリの区分が必要です")
    row_keys = {"name", "required", "oneOf", "normal", "advanced", "build", "lateBuild"}
    for section in sections.values():
        for row in section:
            name = row["name"]
            unknown = row.keys() - row_keys
            require(not unknown, f"未定義の装備候補の欄です {name}・{sorted(unknown)}")
            require(name and name not in row_names, f"装備種別名が空か重複しています {name}")
            row_names.add(name)
            mandatory = [key for key in ("required", "oneOf") if key in row]
            require(len(mandatory) == 1 and row[mandatory[0]],
                    f"必須またはいずれか1つの候補を指定してください {name}")
            seen = set()
            for key in (*mandatory, "normal", "advanced", "build", "lateBuild"):
                for candidate in row.get(key, []):
                    require(candidate in pools or candidate in rows,
                            f"装備候補の参照が未解決です {name}・{candidate}")
                    stats = pools[candidate]["stats"] if candidate in pools else [candidate]
                    for stat in stats:
                        require(stat not in seen, f"装備候補が展開後に重複しています {name}・{stat}")
                        seen.add(stat)
                        if key not in ("build", "lateBuild"):
                            level = unlock["advanced"] if key == "advanced" else 1
                            standard_unlock[stat] = min(level, standard_unlock.get(stat, level))
            candidates.update(seen)

    overlap = candidates.intersection(excluded)
    missing = rows.keys() - candidates - set(excluded)
    require(not overlap and not missing,
            f"装備候補と適用外の分類が無効です 重複 {sorted(overlap)}／不足 {sorted(missing)}")
    # 標準装備の候補が解禁される前の配分を検証する。
    for stat, levels in allocations.items():
        if rows[stat]["rule"].get("mode") in ("attack", "derived"):
            continue
        available = standard_unlock.get(stat, LEVEL_COUNT + 1)
        for level, (_, _, _, _, gear, _) in enumerate(levels[:available - 1], 1):
            require(gear <= 0, f"標準の装備配分を付与できる候補がありません {stat} Lv.{level}")


def armor_example(armor, allocations):
    level = armor["exampleLevel"]
    require(type(level) is int and 1 <= level <= LEVEL_COUNT, "標準防具の例のLvが無効です")
    weights = armor["weights"]
    require(set(weights) == set(ARMOR_SLOTS)
            and all(type(n) is int and n > 0 for n in weights.values()) and sum(weights.values()) == 100,
            "部位配分は正の整数で合計 100 が必要です")
    gear = allocations["共通防御"][level - 1][4]
    budget = int((Decimal(gear) / CENTS).to_integral_value(rounding=ROUND_HALF_UP))
    parts = largest_remainder(budget, [weights[slot] for slot in ARMOR_SLOTS])
    values = dict(zip(ARMOR_SLOTS, parts))
    chest = values["胴"]
    offset = armor["chestOffset"]
    require(type(offset) is int and 0 <= offset <= chest, "標準防具の胴の個体差が無効です")
    shares = "、".join(f"{slot}{weights[slot]}%" for slot in ARMOR_SLOTS)
    points = "、".join(f"{slot}{value}" for slot, value in values.items())
    return (
        '    <details class="es-detail">\n'
        '      <summary>標準防具の数値例</summary>\n'
        f'      <p>標準の共通防御の装備配分を整数点に四捨五入し、防具4部位に配分する。重みは{shares}。</p>\n'
        f'      <p>Lv.{level}の例は防具合計{budget}点（{points}）、胴の個体値は{chest - offset}〜{chest + offset}点。</p>\n'
        '    </details>\n'
    )


def equipment_table(section, rows, pools, unlock):
    def candidates(names):
        items = []
        for candidate in names:
            label = escape(candidate)
            if candidate in pools:
                label = f'<a href="#candidate-groups">{label}</a>'
            items.append(f'<span class="es-stat">{label}</span>')
        return "、".join(items)

    label = escape(section)
    lines = [
        f'    <div class="es-table" role="region" aria-label="{label}の候補表" tabindex="0">',
        f'      <table aria-label="{label}の付与候補">',
        '        <thead><tr><th scope="col">装備種</th><th scope="col">必須</th>'
        '<th scope="col">通常候補</th>'
        f'<th scope="col">上位候補 <span class="es-condition">Lv.{unlock["advanced"]}〜</span></th>'
        f'<th scope="col">ビルド向け <span class="es-condition">Lv.{unlock["build"]}〜</span></th></tr></thead>',
        '        <tbody>',
    ]
    for row in rows:
        name = escape(row["name"])
        required = candidates(row.get("required", row.get("oneOf")))
        if "oneOf" in row:
            required = '<span class="es-condition">いずれか1つ</span>' + required
        normal = candidates(row.get("normal", [])) or "なし"
        advanced = candidates(row.get("advanced", [])) or "なし"
        build = candidates(row.get("build", []))
        if row.get("lateBuild"):
            build += f'<span class="es-condition es-late">Lv.{unlock["lateBuild"]}〜</span>{candidates(row["lateBuild"])}'
        lines.append(
            f'          <tr data-status-ids="" aria-label="{name}">\n'
            f'            <th scope="row">{name}</th>\n'
            f'            <td>{required}</td>\n'
            f'            <td>{normal}</td>\n'
            f'            <td>{advanced}</td>\n'
            f'            <td>{build or "なし"}</td>\n'
            '          </tr>'
        )
    lines.extend(('        </tbody>', '      </table>', '    </div>'))
    return "\n".join(lines) + "\n"


def render_equipment(html, equipment, allocations):
    unlock = equipment["unlockLevels"]
    pools = equipment["pools"]
    rules = (
        '    <dl class="es-rules">\n'
        '      <div><dt>必須</dt><dd>全品に正の補正。「いずれか1つ」は候補から1つを定義。</dd></div>\n'
        '      <div><dt>通常候補</dt><dd>Lv.1から任意。</dd></div>\n'
        f'      <div><dt>上位候補</dt><dd>Lv.{unlock["advanced"]}以上で任意。</dd></div>\n'
        f'      <div><dt>ビルド向け</dt><dd>Lv.{unlock["build"]}以上で役割が合う場合に任意。'
        f'「Lv.{unlock["lateBuild"]}〜」の候補はそのLvから。</dd></div>\n'
        '    </dl>\n'
    )

    toc = []
    chapters = []
    for number, (name, rows) in enumerate(equipment["sections"].items(), 1):
        anchor = "armor" if name == "防具" else f"equipment-{number}"
        label = escape(name)
        toc.append(f'      <li><a href="#{anchor}"><span class="toc-num">{number}</span>{label}</a></li>\n')
        table = equipment_table(name, rows, pools, unlock)
        if name == "防具":
            table += armor_example(equipment["standardArmor"], allocations)
        chapters.append(
            f'  <section class="es-chapter" id="{anchor}" aria-labelledby="{anchor}-title">\n'
            f'    <h2 id="{anchor}-title"><span class="ch-num">{number}</span>{label}</h2>\n'
            f'{table}  </section>\n'
        )

    number = len(chapters) + 1
    toc.append(
        f'      <li><a href="#equipment-excluded"><span class="toc-num">{number}</span>'
        '装備から直接付与しない指標</a></li>\n'
        '      <li><a href="#candidate-groups">候補グループの内訳</a></li>\n'
    )
    excluded = "".join(
        f'      <div><dt>{escape(reason)}</dt><dd>{escape("、".join(names))}</dd></div>\n'
        for reason, names in equipment["excluded"].items()
    )
    chapters.append(
        '  <section class="es-chapter" id="equipment-excluded" aria-labelledby="equipment-excluded-title">\n'
        f'    <h2 id="equipment-excluded-title"><span class="ch-num">{number}</span>'
        '装備から直接付与しない指標</h2>\n'
        f'    <dl class="es-rules">\n{excluded}    </dl>\n'
        '    <p>道具とスキルブックは、採集レベル・釣り性能・使用許可など用途ごとに設計する。</p>\n'
        '  </section>\n'
    )

    group_rows = []
    for name, pool in pools.items():
        stats = escape("、".join(pool["stats"]))
        if pool.get("together"):
            stats += "（2つを一組で付与）"
        group_rows.append(f'      <div><dt>{escape(name)}</dt><dd>{stats}</dd></div>\n')

    for marker, content in (("rules", rules), ("toc", "".join(toc)),
                            ("chapters", "\n".join(chapters)), ("groups", "".join(group_rows))):
        html = embed(html, f"<!-- equipment:{marker} -->", f"<!-- /equipment:{marker} -->", content)
    return html


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
    design = load_design()
    target_path = DATA.parent / "02-ステータス指標.html"
    target_html = target_path.read_text(encoding="utf-8")
    model = read_model(target_html)
    player_model = model_series(model["player"], 55, player=True)
    model_series(model["mob"], 7)
    rows = player_rows(design["groups"], player_model)
    allocations = allocate_player(rows)
    data = allocation_data(rows, allocations)

    equipment = design["equipment"]
    validate_equipment(equipment, rows, allocations)
    updated_targets = embed(
        target_html, '<script type="application/json" id="stat-allocation-data">',
        "</script>", embedded_allocation(data),
    )
    equipment_path = DATA.parent / "03-装備ステータス基準.html"
    equipment_html = equipment_path.read_text(encoding="utf-8")
    updated_equipment = render_equipment(equipment_html, equipment, allocations)
    updates = {
        path: updated
        for path, original, updated in (
            (target_path, target_html, updated_targets),
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
