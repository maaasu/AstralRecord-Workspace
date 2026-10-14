"""Read-only combat comparison under explicit provisional player-anchor assumptions."""
import csv,json,math,re,sys
from pathlib import Path
import yaml
root=Path(sys.argv[1]).resolve()
out=root/"00_docs/99_資料/field-mobs-lv50/integration"
def load(p):return yaml.safe_load(p.read_text(encoding="utf-8-sig"))
def stats(m,p):return {r["status"]:float(r["value"]) for r in p.get("baseStats",m["baseStats"])}
z=load(root/"40_filebase/40.features.mob/enemy/v1.eriva_camouflage_zombie.yml")
anchors={p["level"]:stats(z,p) for p in z["levels"]}
roles={"field_reed_hound":"mobile_melee","field_brush_crab":"durable_melee","field_sunhare":"fast_fragile_melee","field_wind_lizard":"fan_melee","field_plain_ram":"durable_melee","field_blade_mantis":"offensive_melee","field_stoneback_bison":"strong_melee","field_storm_raptor":"mobile_offensive_melee","field_bark_beetle":"ranged_skill_only","field_moss_fox":"melee_magic_area","field_root_turtle":"durable_melee_slow","field_snow_wolf":"mobile_ice_melee","field_ice_moth":"fragile_melee_ice_magic","field_frost_boar":"durable_ice_melee"}
ratios={"field_reed_hound":.9,"field_brush_crab":1.05,"field_sunhare":.95,"field_wind_lizard":.8,"field_plain_ram":1.15,"field_blade_mantis":1.1,"field_stoneback_bison":1.1,"field_storm_raptor":.9,"field_bark_beetle":1.4,"field_moss_fox":1.2,"field_root_turtle":.4,"field_snow_wolf":.8,"field_ice_moth":.65,"field_frost_boar":1.05}
def defense(r,d):
 return max(0,1-d/r/2.5)**1.35691545 if r>0 and d/r<2.5 else 0
rows=[]
for mobid in roles:
 m=load(root/f"40_filebase/40.features.mob/enemy/v1.{mobid}.yml")
 normal=m["ai"]["combat"].get("normalAttack",{}).get("intervalTicks",0)/20
 binding=m["ai"]["combat"]["skills"][0]
 cd=binding["cooldownTicks"]/20
 sale=0
 for drop in m["drops"]["items"]:
  itemid=drop["itemId"]["ref"].split(":",1)[1]
  item=load(next((root/"40_filebase/10.features.item/10.material").glob(f"v1.{itemid}.*.yml")))
  sale+=drop["rate"]/100*float(drop["amount"])*item.get("saleValue",0)
 for p in m["levels"]:
  s=stats(m,p);level=p["level"];delta=max(0,level-30)
  if level<=30:
   a=anchors[level]; bh=a["MAX_HEALTH"]; br=a["ATTACK"];bd=a["DEFENSE"]
  else:
   bh=19000+1200*delta;br=3442+217.4*delta;bd=3318+210.8*delta
  # B02 second-difference HP extension at two-level increments; R/D remain linear.
  n=delta/2; bh2=bh if not delta else 19000+2400*n+100*n*(n+1)
  playerhit=max(0,min(1,(95-s.get("EVASION",3))/100))
  mobhit=max(0,min(1,(s.get("ACCURACY",95)-3.5)/100))
  playerdps=bd*defense(bd,s["DEFENSE"])*1.3*playerhit*1.6
  normaldps=s["ATTACK"]*defense(s["ATTACK"],br)*mobhit/normal if normal>0 else 0
  skilldps=s["ATTACK"]*ratios[mobid]*defense(s["ATTACK"],br)*mobhit/cd
  ttk=s["MAX_HEALTH"] / playerdps
  if delta:
   factor=(level/30)**2; qh=19000*factor;qr=3442*factor;qd=3318*factor
  else: qh=bh;qr=br;qd=bd
  qplayerdps=qd*defense(qd,s["DEFENSE"])*1.3*playerhit*1.6
  qmobdps=s["ATTACK"]*defense(s["ATTACK"],qr)*mobhit*((1/normal if normal>0 else 0)+ratios[mobid]/cd)
  rows.append(dict(mob_id=mobid,level=level,role=roles[mobid],hp=int(s["MAX_HEALTH"]),attack=int(s["ATTACK"]),defense=int(s["DEFENSE"]),exp=p.get("drops",{}).get("exp",m["drops"]["exp"]),normal_interval_seconds=normal,skill_cd_seconds=cd,skill_ratio=ratios[mobid],anchor_state="existing_camouflage_anchor" if level<=30 else "provisional_linear",standard_hp_linear=bh,standard_hp_b02_extension=bh2,hp_vs_linear=round(s["MAX_HEALTH"]/bh,4),hp_vs_b02_extension=round(s["MAX_HEALTH"]/bh2,4),attack_vs_linear=round(s["ATTACK"]/br,4),defense_vs_linear=round(s["DEFENSE"]/bd,4),standard_hp_quadratic=round(qh,2),ttk_seconds_quadratic=round(s["MAX_HEALTH"]/qplayerdps,2),player_survival_lower_quadratic=round((qh/2)/qmobdps,2),player_dps_assumed=round(playerdps,2),ttk_seconds_assumed=round(ttk,2),mob_normal_dps=round(normaldps,2),mob_normal_plus_skill_dps_upper=round(normaldps+skilldps,2),player_survival_lower_linear=round((bh/2)/(normaldps+skilldps),2),player_survival_lower_b02_extension=round((bh2/2)/(normaldps+skilldps),2),expected_sale_value_no_luck=round(sale,2),expected_sale_per_ttk=round(sale/ttk,2),runtime_verified="false"))
with (out/"COMBAT_COMPARISON.csv").open("w",encoding="utf-8",newline="") as f:
 w=csv.DictWriter(f,fieldnames=list(rows[0]),lineterminator="\n");w.writeheader();w.writerows(rows)
lines=["# 統合済み14種の数値比較", "", "B01〜B07実マスタから56profileを抽出。COMBAT_COMPARISON.csvが全数値、generate_combat_comparison.pyが再計算元。実戦値ではない。", "", "Player基準は既存迷彩ゾンビの同格HP/2、R=標準Mob DEFENSE、D=標準Mob ATTACK。Player通常1秒/命中95/最終130%/skill込みDPS1.6倍、Player回避3.5。Mobの実ATTACK/DEFENSE/EVASION/通常間隔/CDを使用。キノカブトは通常攻撃なし（通常DPS0）、散弾140%/3秒。コケ煙は40%×3pulseを総倍率1.2として評価。近接/遠隔/魔法の別防御は同じ抽象PlayerDと仮定し、実職業/装備では別途照合。ネッコガメは遠隔40%と1.2秒減速（減速の位置取り影響は表外）、ユキオオカミは氷近接80%を属性倍率100%で比較。無属性・会心/貫通/盾/一時buffなし、防御倍率(1-D/R/2.5)^1.35691545、D/R>=2.5なら0。攻撃技倍率は防御比のRに含めない。", "", "耐久下限は通常攻撃DPS+CDごとの技DPSを連続加算した保守的上限から算出。予告中の通常攻撃停止、回避、移動、ring操作、AoEの追加対象を含まない。TTKは通常DPS×1.6の仮定であり新装備/職業構成の実性能を保証しない。", "", "Lv30超: 共通比較L=HP19000+1200×Δ/R3442+217.4×Δ/D3318+210.8×Δ。別比較C=B02 HP延長19000+2400n+100n(n+1), n=Δ/2、R/DはLと同じ。Q=B04のHP/R/Dを(Lv/30)^2で延長する案も別列に記録。正本未定義のため3案を保持し、マスタを一律修正しない。", "", "| 種 | Lv | HP/R/D | TTK仮定L/Q秒 | 耐久下限L/C/Q秒 |", "|---|---:|---|---:|---|" ]
for r in rows:
 lines.append(f"| {r['mob_id']} | {r['level']} | {r['hp']}/{r['attack']}/{r['defense']} | {r['ttk_seconds_assumed']}/{r['ttk_seconds_quadratic']} | {r['player_survival_lower_linear']}/{r['player_survival_lower_b02_extension']}/{r['player_survival_lower_quadratic']} |")
lines += ["", "Lv32の基準HPはL21400/C21600、Lv34はL23800/C24400、Lv42はL33400/C37600、Lv48はL40600/C49600。B03カマキリはLのHP0.9倍だがCではLv48で約0.737倍に見える。この差は暫定基準の違いで、role倍率と区別して全種統合時に評価する。", "", "全種統合時に通常/耐久/高火力/強めのrole倍率、同格TTK5〜7秒と耐久8〜10秒、上位装備/職業の実R/D/HP、skill停止時間とAoE密度、EXP/期待売却/素材消費量を照合してLv30超曲線を確定する。強め2種には別目標を置く。現在表から実装値の再調整はしていない。" ]
(out/"COMBAT_COMPARISON.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
print(json.dumps({"profiles":len(rows),"species":len(roles),"ttk_range_assumed":[min(x["ttk_seconds_assumed"] for x in rows),max(x["ttk_seconds_assumed"] for x in rows)],"survival_lower_linear_range":[min(x["player_survival_lower_linear"] for x in rows),max(x["player_survival_lower_linear"] for x in rows)],"runtime_verified":False},ensure_ascii=False))
