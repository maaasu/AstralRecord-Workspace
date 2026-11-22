"""設計値の YAML からステータス関連の HTML を同期する。"""

import argparse
import json
import re
import subprocess
from decimal import ROUND_HALF_UP, Decimal
from html import escape
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import quote

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
for (const {grid, title, unit, cats, series} of panels) {
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
  result[owner].push({title, unit, cats, series: targets});
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


def valid_equipment_icon(icon):
    return isinstance(icon, str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]*', icon) is not None


def validate_equipment(equipment, rows, allocations):
    unlock = equipment['unlockLevels']
    require(set(unlock) == {'advanced', 'build', 'lateBuild'}
            and all(type(level) is int and 1 <= level <= LEVEL_COUNT for level in unlock.values())
            and unlock['build'] <= unlock['lateBuild'], '装備候補の解禁Lvが無効です')
    policy = equipment['twoHanded']
    require(set(policy) == {'sharePercent', 'exampleLevel'}
            and type(policy['sharePercent']) is int and 1 <= policy['sharePercent'] <= 99
            and type(policy['exampleLevel']) is int and 1 <= policy['exampleLevel'] <= LEVEL_COUNT,
            f'両手補正の配分は1〜99の整数%、表示例はLv.1〜{LEVEL_COUNT}の整数で指定してください')

    pools = equipment['pools']
    for name, pool in pools.items():
        require(isinstance(name, str) and name.strip() and not re.search(r'[\t\n\f\r ]', name),
                f'候補グループ名をフラグメントIDに使用できません {name}')
        stats = pool['stats']
        require(isinstance(stats, list) and stats and len(stats) == len(set(stats))
                and set(stats) <= rows.keys(), f'候補グループの内訳が空か、未定義・重複の指標があります {name}')

    excluded = [name for names in equipment['excluded'].values() for name in names]
    require(len(excluded) == len(set(excluded)) and set(excluded) <= rows.keys(),
            '装備から直接付与しない指標は、02の表示名で重複なく指定してください')

    fixed_weapons = {'ハンマー', '長弓', 'スタッフ'}
    sections = equipment['sections']
    require({'武器', '防具', '補助装備', 'アクセサリ'} <= sections.keys(), '装備の必須4分類を確認してください')
    equipment_names = set()
    found_fixed = set()
    candidates = set()
    standard_unlock = {}
    stages = ('normal', 'advanced', 'build', 'lateBuild')
    row_keys = {'name', 'role', 'icon', 'alias', 'required', 'oneOf', 'twoHandedBonus', *stages}
    for section, entries in sections.items():
        require(isinstance(entries, list) and entries, f'装備種別の一覧が空です {section}')
        for row in entries:
            name = row.get('name')
            require(isinstance(name, str) and name.strip() and name not in equipment_names,
                    f'装備名が空か重複しています {section}・{name}')
            equipment_names.add(name)
            unknown = row.keys() - row_keys
            require(not unknown, f'未定義の装備候補の欄です {name}・{sorted(unknown)}')
            require(isinstance(row.get('role'), str) and row['role'].strip()
                    and valid_equipment_icon(row.get('icon')), f'役割またはアイコンが無効です {name}')
            if 'alias' in row:
                require(isinstance(row['alias'], str) and row['alias'].strip(), f'別名が空です {name}')
            if name == '長弓':
                require(row.get('alias') == 'ロングボウ', '長弓の別名を確認してください')

            mandatory = [key for key in ('required', 'oneOf') if key in row]
            require(len(mandatory) == 1, f'必須またはいずれか1つの候補が必要です {name}')
            mandatory_key = mandatory[0]
            required_names = row[mandatory_key]
            require(isinstance(required_names, list) and required_names,
                    f'必須候補は空でない一覧で指定してください {name}')
            require(('twoHandedBonus' in row) == (name in fixed_weapons), f'両手固定の対象を確認してください {name}')
            seen = set()
            if name in fixed_weapons:
                require(section == '武器', f'両手固定の装備は武器章に指定してください {name}')
                found_fixed.add(name)
                stat = row['twoHandedBonus']
                require(mandatory_key == 'required' and len(required_names) == 1
                        and required_names[0] in rows and required_names[0] not in pools
                        and isinstance(stat, str) and stat in rows and stat in allocations,
                        f'両手固定の必須能力または専用補正が無効です {name}')
                require(len(allocations[stat]) == LEVEL_COUNT and allocations[stat][0][4] == 0,
                        f'両手補正の配分にはLv.1の原資0を含む全Lvの値が必要です {stat}')
                seen.add(stat)
                standard_unlock[stat] = 1

            for key in (mandatory_key, *stages):
                names = row.get(key, [])
                require(isinstance(names, list), f'装備候補は一覧で指定してください {name}・{key}')
                for candidate in names:
                    require(isinstance(candidate, str) and (candidate in pools or candidate in rows),
                            f'装備候補の参照が未解決です {name}・{candidate}')
                    stats = pools[candidate]['stats'] if candidate in pools else [candidate]
                    for stat in stats:
                        require(stat not in seen, f'必須・専用補正・付与候補が展開後に重複しています {name}・{stat}')
                        seen.add(stat)
                        if key not in ('build', 'lateBuild'):
                            level = unlock['advanced'] if key == 'advanced' else 1
                            standard_unlock[stat] = min(level, standard_unlock.get(stat, level))
            candidates.update(seen)

    require(found_fixed == fixed_weapons, f'武器章の両手固定装備が不足しています {sorted(fixed_weapons - found_fixed)}')
    overlap = candidates.intersection(excluded)
    missing = rows.keys() - candidates - set(excluded)
    require(not overlap and not missing,
            f'装備候補と適用外の分類が無効です 重複 {sorted(overlap)}／不足 {sorted(missing)}')
    for stat, levels in allocations.items():
        if rows[stat]['rule'].get('mode') in ('attack', 'derived'):
            continue
        available = standard_unlock.get(stat, LEVEL_COUNT + 1)
        for level, (_, _, _, _, gear, _) in enumerate(levels[:available - 1], 1):
            require(gear <= 0, f'標準の装備配分を付与できる候補がありません {stat} Lv.{level}')


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


EQUIPMENT_CATEGORIES = {
    'resource': 'リソース', 'primary': '基本能力値', 'offense': '攻撃', 'defense': '防御',
    'element': '属性', 'condition': '状態異常', 'utility': 'ユーティリティ',
}


def equipment_stat(stat, cat):
    return f'<span class="es-stat cat-{escape(cat, quote=True)}">{escape(stat)}</span>'


def equipment_search_context(row):
    terms = [row['role'], *row.get('required', row.get('oneOf', []))]
    if 'twoHandedBonus' in row:
        terms.extend((row['twoHandedBonus'], '両手固定', 'オフハンド不可'))
    return terms


def equipment_cards(section, rows, equipment, allocations, categories, search_ids, units):
    pools = equipment['pools']
    unlock = equipment['unlockLevels']
    policy = equipment['twoHanded']
    level = policy['exampleLevel']
    stages = (
        ('normal', '通常候補', 1), ('advanced', '上位候補', unlock['advanced']),
        ('build', 'ビルド向け候補', unlock['build']), ('lateBuild', '後期ビルド向け候補', unlock['lateBuild']),
    )
    category_order = tuple(EQUIPMENT_CATEGORIES)

    def candidate_value(name):
        if name in pools:
            fragment = 'es-pool-' + quote(name, safe='')
            cats = candidate_categories(name)
            color = f' cat-{cats[0]}' if len(cats) == 1 else ''
            return f'<a class="es-stat{color}" href="#{fragment}">{escape(name)}</a>'
        return equipment_stat(name, categories[name][0])

    def candidate_categories(name):
        stats = pools[name]['stats'] if name in pools else [name]
        member_cats = [set(categories[stat]) for stat in stats]
        shared = set.intersection(*member_cats)
        cats = shared or set.union(*member_cats)
        return tuple(cat for cat in category_order if cat in cats)

    def stage(heading, names, tag):
        grouped = {}
        for name in names:
            cats = candidate_categories(name)
            grouped.setdefault(cats, []).append(candidate_value(name))
        lines = [f'<div class="es-stage"><{tag}>{heading}</{tag}>']
        for cats in sorted(grouped, key=lambda group: tuple(map(category_order.index, group))):
            labels = '・'.join(f'<span class="es-category-name cat-{cat}">{EQUIPMENT_CATEGORIES[cat]}</span>'
                              for cat in cats)
            values = ' '.join(grouped[cats])
            row_class = f' cat-{cats[0]}' if len(cats) == 1 else ''
            lines.append(f'<div class="es-category-row{row_class}"><div>{labels}</div> '
                         f'<div class="es-values">{values}</div></div>')
        lines.append('</div>')
        return '\n'.join(lines)

    def card(row):
        name = escape(row['name'])
        if 'alias' in row:
            name += '（' + escape(row['alias']) + '）'
        role = escape(row['role'])
        icon = escape(row['icon'], quote=True)
        bonus = row.get('twoHandedBonus')
        card_class = 'es-card es-two-hand' if bonus else 'es-card'
        title_tag, stage_tag = ('h4', 'h5') if section == '武器' else ('h3', 'h4')
        choice = 'oneOf' in row
        mandatory = row['oneOf'] if choice else row['required']
        label = '必須（いずれか1つ）' if choice else '必須'
        summary = ' '.join(candidate_value(candidate) for candidate in mandatory[:2])
        if len(mandatory) > 2:
            summary += f' <small>ほか{len(mandatory) - 2}候補</small>'
        facts = [f'<div><dt>{label}</dt><dd class="es-values">{summary}</dd></div>']
        if bonus:
            grant, _ = two_handed_allocation(allocations, bonus, level, policy['sharePercent'])
            unit = 'ポイント' if units[bonus] == '%' else escape(units[bonus])
            value = f'+{Decimal(grant) / CENTS:.2f}{unit}'
            facts.append(f'<div><dt>両手固定の補正</dt><dd class="es-values">{equipment_stat(bonus, categories[bonus][0])} '
                         f'<strong>{value}</strong> <small class="es-example">Lv.{level}の例</small></dd></div>')

        details = []
        if len(mandatory) > 2:
            details.append(stage(label, mandatory, stage_tag))
        candidates = list(mandatory)
        for key, title, start in stages:
            names = row.get(key, [])
            if names:
                details.append(stage(f'{title} <span class="es-condition">Lv.{start}〜</span>', names, stage_tag))
                candidates.extend(names)
        search_terms = equipment_search_context(row)
        for candidate in candidates:
            if candidate in pools:
                search_terms.extend(pools[candidate]['stats'])
        ids = dict.fromkeys(search_ids[term] for term in search_terms)
        search_attr = f' data-status-ids="{" ".join(ids)}"' if ids else ''
        return '\n'.join((
            f'<article class="{card_class}">',
            '<div class="es-card-head">',
            f'<svg class="es-gear-icon" viewBox="0 0 32 32" aria-hidden="true" focusable="false"><use href="#es-icon-{icon}"></use></svg>',
            f'<div><{title_tag} class="es-name">{name}</{title_tag}><p class="es-role">{role}</p></div></div>',
            '<dl class="es-facts">', *facts, '</dl>',
            f'<details class="es-detail es-card-more"{search_attr}>',
            f'<summary><span class="es-sr">{name}の</span>付与候補</summary>',
            '<div class="es-stages">', *details, '</div></details></article>',
        ))

    def grid(entries):
        return '<div class="es-grid">\n' + '\n'.join(map(card, entries)) + '\n</div>\n'

    if section != '武器':
        return grid(rows)
    return (
        '<div class="es-subhead"><h3>両手固定</h3>'
        '<p class="es-subnote">オフハンド（盾・補助装備）は使用不可。</p></div>\n'
        + grid(row for row in rows if 'twoHandedBonus' in row)
        + '<div class="es-subhead"><h3>その他の武器</h3></div>\n'
        + grid(row for row in rows if 'twoHandedBonus' not in row)
    )


def two_handed_allocation(allocations, stat, level, share):
    """同じLvの装備配分を、両手武器分と他部位分に0.01単位で分ける。"""
    require(type(level) is int and 1 <= level <= LEVEL_COUNT, '両手補正のLvが無効です')
    gear = allocations[stat][level - 1][4]
    weapon = largest_remainder(gear, [share, 100 - share])[0]
    return weapon, gear - weapon


class EquipmentSymbols(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        if tag == 'symbol':
            self.ids.add(dict(attrs).get('id'))


def render_equipment(html, equipment, allocations, categories, units):
    require(categories.keys() == allocations.keys(), '装備表示の能力と02の配分が一致しません')
    for stat, cats in categories.items():
        require(isinstance(cats, list) and cats and len(cats) == len(set(cats))
                and set(cats) <= EQUIPMENT_CATEGORIES.keys(), f'02の能力分類が無効です {stat}・{cats}')
    symbols = EquipmentSymbols()
    symbols.feed(html)
    symbols.close()
    search_terms = {stat for pool in equipment['pools'].values() for stat in pool['stats']}
    for entries in equipment['sections'].values():
        for row in entries:
            icon = row.get('icon')
            require(valid_equipment_icon(icon), f'装備アイコンのIDが無効です {row["name"]}・{icon}')
            require(f'es-icon-{icon}' in symbols.ids, f'装備アイコンのsymbol定義がありません {row["name"]}・es-icon-{icon}')
            search_terms.update(equipment_search_context(row))

    search_ids = {term: f'es-{i}' for i, term in enumerate(sorted(search_terms), 1)}
    aliases = {key: stat for stat, key in search_ids.items()}
    alias_json = json.dumps(aliases, ensure_ascii=False).replace('<', '\\u003c')
    share = equipment['twoHanded']['sharePercent']
    rules = (
        '    <p>Lvは装備を初めて入手できる攻略段階の目安。通常・上位・ビルド向けの候補は、装備の役割に応じて任意で付与する。</p>\n'
        '    <p>ビルド向けの付与は標準の付与と入れ替え、同じLvの特化目標と比較する。'
        '標準値は<a href="02-ステータス指標.html#player-allocation">02の配分一覧</a>の装備欄を参照。</p>\n'
        f'    <p>両手固定武器の種別攻撃力は02の標準値に従う。専用補正には、対応する能力の同じLvの装備配分の{share}%を使う。'
        'これは攻撃用の補助装備1枠分の目安で、両手固定時はその分を武器で付与する。オフハンドの値は重ねて加算しない。</p>\n'
        '    <p>専用補正は0.01単位に四捨五入し、装備配分から差し引いた残りを他部位に配る。'
        'Lv.1は装備配分が0のため補正も0。装備配分が正になるLvから有効になる。命中率の補正はポイントで加算する。</p>\n'
        f'    <script type="application/json" id="doc-search-aliases">{alias_json}</script>\n'
    )
    toc = []
    chapters = []
    for number, (section, entries) in enumerate(equipment['sections'].items(), 1):
        anchor = 'armor' if section == '防具' else f'equipment-{number}'
        label = escape(section)
        toc.append(f'      <li><a href="#{anchor}"><span class="toc-num">{number}</span>{label}</a></li>\n')
        cards = equipment_cards(section, entries, equipment, allocations, categories, search_ids, units)
        if section == '防具':
            cards += armor_example(equipment['standardArmor'], allocations)
        chapters.append(
            f'  <section class="es-chapter" id="{anchor}" aria-labelledby="{anchor}-title">\n'
            f'    <h2 id="{anchor}-title"><span class="ch-num">{number}</span>{label}</h2>\n'
            f'{cards}  </section>\n'
        )

    number = len(chapters) + 1
    toc.append(
        f'      <li><a href="#equipment-excluded"><span class="toc-num">{number}</span>'
        '装備から直接付与しない指標</a></li>\n'
        '      <li><a href="#candidate-groups">候補グループの内訳</a></li>\n'
    )

    def stat_list(names):
        return ' '.join(equipment_stat(stat, categories[stat][0]) for stat in names)

    excluded = ''.join(
        f'      <div><dt>{escape(reason)}</dt><dd class="es-values">{stat_list(names)}</dd></div>\n'
        for reason, names in equipment['excluded'].items()
    )
    chapters.append(
        '  <section class="es-chapter" id="equipment-excluded" aria-labelledby="equipment-excluded-title">\n'
        f'    <h2 id="equipment-excluded-title"><span class="ch-num">{number}</span>'
        '装備から直接付与しない指標</h2>\n'
        f'    <dl class="es-rules">\n{excluded}    </dl>\n'
        '    <p>道具とスキルブックは、採集レベル・釣り性能・使用許可など用途ごとに設計する。</p>\n'
        '  </section>\n'
    )
    groups = []
    for name, pool in equipment['pools'].items():
        values = stat_list(pool['stats'])
        if pool.get('together'):
            values += ' <span>（2つを一組で付与）</span>'
        fragment = escape('es-pool-' + name, quote=True)
        groups.append(f'      <div><dt id="{fragment}">{escape(name)}</dt><dd class="es-values">{values}</dd></div>\n')

    for marker, content in (('rules', rules), ('toc', ''.join(toc)),
                            ('chapters', '\n'.join(chapters)), ('groups', ''.join(groups))):
        html = embed(html, f'<!-- equipment:{marker} -->', f'<!-- /equipment:{marker} -->', content)
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
    panel_categories = {panel['title']: panel['cats'] for panel in model['player']}
    categories = {name: panel_categories[row['rule']['title']] for name, row in rows.items()}
    units = {name: row['unit'] for name, row in rows.items()}
    updated_equipment = render_equipment(equipment_html, equipment, allocations, categories, units)
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
