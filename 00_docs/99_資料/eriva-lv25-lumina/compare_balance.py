"""旧Lv15とルミナの値・固定条件の参考戦闘を比較する。実プレイヤービルドではない。"""
from pathlib import Path
import csv
import json
import math

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
GEAR = ROOT / "40_filebase/10.features.item/20.equipment"


def load(item_id):
    path, = GEAR.glob(f"v1.{item_id}.*.yml")
    return yaml.safe_load(path.read_text(encoding="utf-8-sig"))


def bounds(value):
    if isinstance(value, dict):
        low = bounds(value["min"])
        high = bounds(value["max"])
        return low[0], high[1], (low[2]+high[2])/2
    if isinstance(value, str) and "~" in value:
        low, high = map(float, value.split("~"))
        return low, high, (low+high)/2
    return float(value), float(value), float(value)


def stat_values(data, status, level=0):
    stat, = [s for s in data["equipment"]["stats"] if s["status"] == status]
    low, high, mean = bounds(stat["value"])
    if "random" in stat:
        rnd = stat["random"]
        low += rnd["offsetMin"]
        high += rnd["offsetMax"]
        zero = rnd.get("zeroChance", 0)
        mean = (low+high)/2*(1-zero)
        if zero:
            low = min(0, low)
    for row in data["equipment"]["enhance"]["levels"]:
        if row["level"] <= level:
            for s in row.get("statIncrease", []):
                if s["status"] == status:
                    delta = bounds(s["value"])
                    low += delta[0]
                    high += delta[1]
                    mean += delta[2]
    return low, high, mean


def defense_multiplier(attack, defense):
    return max(0, 1-defense/attack/2.5)**(math.log(.5)/math.log(.6))


def run():
    old_ids = [73, 74, 75, 77, 78, 79, 80] + list(range(86, 98))
    pairs = [(load(f"20a{old:05}"), load(f"20a{new:05}")) for old, new in zip(old_ids, range(419, 438))]
    fields = ["old_id", "new_id", "name", "primary_status"] + [f"{kind}_{level}_{measure}" for kind in ["old", "new"] for level in [0, 15, 30] for measure in ["min", "max", "mean"]]
    with (HERE/"balance_comparison.csv").open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        for old, new in pairs:
            status = new["equipment"]["stats"][0]["status"]
            row = {"old_id": old["id"], "new_id": new["id"], "name": new["name"], "primary_status": status}
            for kind, data in [("old", old), ("new", new)]:
                for level in [0, 15, 30]:
                    row.update({f"{kind}_{level}_{measure}": round(value, 6) for measure, value in zip(["min", "max", "mean"], stat_values(data, status, level))})
            writer.writerow(row)

    # この残差は当初案主200/+15=290/STR14のRを2264へ合わせた仮想値。
    # 値調整後も固定して装備の差だけを比較する。実マスタの完成ビルドを表さない。
    fixed_attack = 2264/1.568-298
    fixed_strength = 42.8  # swordsman Lv30: 8+29*1.2
    fixed_defense = 1690
    fixed_health = 5610
    mob_hp, mob_attack, mob_defense = 13000, 2355, 2264
    sword_old, sword_new = pairs[0]
    armor_old = [p[0] for p in pairs[7:11]]
    armor_new = [p[1] for p in pairs[7:11]]

    def scenario(label, sword, armor, level, extreme=False, nadi=False, offensive_armor=False):
        main = stat_values(sword, "MELEE_ATTACK", level)[1 if extreme else 2]
        strength = stat_values(sword, "STRENGTH", level)[1 if extreme else 2]
        set_attack = 6 if sword["id"] == "20a00073" else 8
        rune, attribute, normal_bonus = 0, 1, 1
        if extreme:
            main = (main+48)*1.7
            strength += 0 if nadi else 12
            rune, attribute = 5, 1.07
            normal_bonus = 1.3 if nadi else 1
            if offensive_armor:
                # 防具各部位STR12/NONE増加10/同x1.24。属性7%は無属性へ適用しない。
                strength += 48
                attribute = 1+4*10*1.24/100
        attack = (fixed_attack+set_attack+main+rune)*(1+(fixed_strength+strength)/100)
        defense = sum(stat_values(d, "DEFENSE", level)[1 if extreme else 2] for d in armor)
        if extreme and not offensive_armor:
            defense = (defense+120)*1.5+192  # 各部位DEF30, DEFx1.5, MELEE_DEF48
        defense += fixed_defense
        hp = fixed_health+sum(stat_values(d, "MAX_HEALTH", level)[2] for d in armor)
        # 旧+30にはHP追加がある。新には無い。各モデルの強化値をそのまま使う。
        base_hit = attack*defense_multiplier(attack, mob_defense)*1.3*attribute
        normal_hit = base_hit*normal_bonus
        mixed_hit = base_hit*(.6*normal_bonus+.4)
        player_dps = mixed_hit*.92*1.6
        enemy_dps = mob_attack*defense_multiplier(mob_attack, defense)*.915/1.5
        return {"case": label, "R": round(attack, 6), "D": round(defense, 6), "HP": hp,
                "normalHit": round(normal_hit, 6), "referenceTTK": round(mob_hp/player_dps, 6),
                "referenceSurvival": round(hp/enemy_dps, 6),
                "R_to_Lv25Anchor": round(attack/2264, 6), "D_to_Lv25Anchor": round(defense/2355, 6)}

    cases = [scenario("Eriva +15 mean no enchant/rune", sword_old, armor_old, 15)]
    cases += [scenario(f"Lumina +{level} mean no enchant/rune", sword_new, armor_new, level) for level in [0, 15, 30]]
    cases += [scenario("Lumina +30 maxroll +3 weapon R enchants +3 DEF enchants per armor +rune +matching7%", sword_new, armor_new, 30, True),
              scenario("Lumina +30 maxroll NADI third weapon enchant; normal60/skill40 assumption", sword_new, armor_new, 30, True, True),
              scenario("Lumina +30 maxroll +3 weapon R enchants +all4 offensive armor STR/NONE/NONE scalar +rune; NONE attack", sword_new, armor_new, 30, True, False, True)]
    report = {"model": "Synthetic fixed residual; controlled sensitivity comparison, not a verified character build",
              "fixedNonEquipment": {"ATTACK_and_other_type_attack": fixed_attack, "STRENGTH": fixed_strength, "DEFENSE_including_derived": fixed_defense, "HP_including_derived": fixed_health},
              "enemy": {"level": 25, "HP": mob_hp, "R": mob_attack, "D": mob_defense},
              "assumptions": {"playerHitRate": .92, "enemyHitRate": .915, "playerNormalInterval": 1, "enemyInterval": 1.5, "mixedDpsFactor": 1.6, "playerFinalMultiplier": 1.3, "normalShareForNadiComparison": .6},
              "cases": cases,
              "limits": ["No class tree, skill timing, normal-attack degradation or 100-battle simulation", "No claim that 3 enchants chosen here globally maximize DPS/survival; other scalar/skill/element/vitality trades exist", "Extreme matching element is one applicable attribute; two different attributes are not added to the same element", "Anchor residual intentionally held fixed; no proof of real player R/D totals or Gold acquisition time"]}
    (HERE/"combat_reference.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8", newline="\n")
    print(json.dumps(cases, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    run()
