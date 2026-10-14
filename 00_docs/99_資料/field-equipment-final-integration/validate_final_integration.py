#!/usr/bin/env python3
"""Read-only AstralRecord final integration validator. Run with: python -B SCRIPT --repo PATH [--output PATH] [--allow-pending].
Only --output is written, when explicitly supplied. PyYAML is required.
"""
from __future__ import annotations
import argparse, copy, csv, json, math, re, subprocess, sys
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path

BASE="989d32b7f10df0c05b799cda98f2c7fb211e72d8"
ENEMY_BASE="bc7877ae3"
INTAKE="1af2b415ccc629858469fe209d72a496f8ea0948"
SOURCES=[
 ("E10","c49e7d611",4),
 ("E11","f7215c43d",4),
 ("Lv20","2bdcea3c4",30),
 ("Lv25","660f86bf8",31),
 ("E12","7bc195d8f15976d06ab7a171db298ffb5ed27c6d",4),
 ("Lv30","a05095ee63252d0ddda6f3e1994192bccb33a31d",34),
 ("E13","4f07d7a19df40b743284bac871be9a88a58ac665",2),
]
STAGES=[
 {"name":"Lapis","level":20,"start":400,"end":418,"manifest":"00_docs/99_資料/eriva-upper-equipment/lapis-lv20/MATERIAL_MANIFEST.json","array":"items"},
 {"name":"Lumina","level":25,"start":419,"end":437,"manifest":"00_docs/99_資料/eriva-lv25-lumina/material_manifest.json","array":"products"},
 {"name":"Astra","level":30,"start":438,"end":456,"manifest":"00_docs/99_資料/eriva-lv30-astra/material_manifest.json","array":"products"},
]
SHOP_FILES={
 "weapon_craftsman_shop":"40_filebase/45.features.shop/v1.weapon_craftsman_shop.yml",
 "armor_craftsman_shop":"40_filebase/45.features.shop/v1.armor_craftsman_shop.yml",
}
BATCH="00_docs/99_資料/field-mobs-lv50/BATCH_IDS.csv"
NEW_MATS="00_docs/99_資料/field-mobs-lv50/NEW_MATERIALS.csv"
OLD_MATS="00_docs/99_資料/field-mobs-lv50/EXISTING_MATERIAL_ASSIGNMENTS.csv"
SHOP_CSV="00_docs/99_資料/field-equipment-final-integration/SHOP_CONNECTIONS.csv"

try:
 import yaml
except ImportError as e:
 raise SystemExit("PyYAML is required in the selected Python runtime") from e

class UniqueLoader(yaml.SafeLoader): pass
def unique_mapping(loader,node,deep=False):
 result={}
 for kn,vn in node.value:
  key=loader.construct_object(kn,deep=deep)
  if key in result: raise ValueError(f"duplicate YAML key: {key!r}")
  result[key]=loader.construct_object(vn,deep=deep)
 return result
UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,unique_mapping)

class Check:
 def __init__(self,repo,allow_pending):
  self.repo=repo.resolve(); self.allow_pending=allow_pending; self.errors=[]; self.pending=[]
  self.report={"repo":str(self.repo),"baseline":BASE,"intakeTip":INTAKE}
 def err(self,name,detail): self.errors.append({"check":name,"detail":detail})
 def pend(self,name,detail): self.pending.append({"check":name,"detail":detail})
 def rel(self,p): return p.relative_to(self.repo).as_posix()
 def git(self,*args):
  return subprocess.check_output(["git","--no-optional-locks","-c","core.quotepath=false","-C",str(self.repo),*args],stderr=subprocess.PIPE).decode("utf-8","replace").strip()
 def yaml_file(self,p): return yaml.load(p.read_text(encoding="utf-8-sig"),Loader=UniqueLoader)
 def git_yaml(self,rev,path): return yaml.load(self.git("show",f"{rev}:{path}"),Loader=UniqueLoader)
 def load(self):
  self.paths=sorted(set((self.repo/"40_filebase").rglob("*.yml"))|set((self.repo/"40_filebase").rglob("*.yaml")))
  self.data={}; parse=[]
  for p in self.paths:
   try: self.data[p]=self.yaml_file(p)
   except Exception as e: parse.append({"path":self.rel(p),"error":str(e)})
  if parse: self.err("yaml_parse_duplicate_keys",parse)
  self.ids=defaultdict(list); self.nsids=defaultdict(list); self.catalogids=defaultdict(list)
  for p,d in self.data.items():
   if not isinstance(d,dict): continue
   parts=p.relative_to(self.repo/"40_filebase").parts
   ns=parts[0]+("/"+parts[1] if parts[0]=="80.shared.loot" and len(parts)>1 else "")
   if "id" in d:
    self.ids[str(d["id"])].append(p); self.nsids[(ns,str(d["id"]))].append(p)
   for k,rows in d.items():
    if isinstance(rows,list):
     for row in rows:
      if isinstance(row,dict) and "id" in row: self.catalogids[(ns+":"+str(k),str(row["id"]))].append(p)
  dup=lambda d:{str(k):[self.rel(x) for x in v] for k,v in d.items() if len(v)>1}
  nsdup=dup(self.nsids); rootdup=dup(self.ids); catdup=dup(self.catalogids)
  if nsdup: self.err("id_collisions_within_master_namespace",nsdup)
  self.report.update(yamlFilesParsed=len(self.paths),rootMasters=sum(len(v) for v in self.ids.values()),
                     idCollisions={"namespace":len(nsdup),"global":len(rootdup),"catalog":len(catdup)})
 def equip(self):
  return {str(d["id"]):(p,d) for p,d in self.data.items() if isinstance(d,dict) and d.get("category")=="equipment" and d.get("id")}
 def sets(self):
  return {str(d["id"]):(p,d) for p,d in self.data.items() if isinstance(d,dict) and p.parent.name=="set_effect" and d.get("id")}
 @staticmethod
 def ref(v):
  x=v.get("itemId") if isinstance(v,dict) else None
  return x.get("ref","") if isinstance(x,dict) else str(x or "")
 @staticmethod
 def merge(a,b):
  if isinstance(a,dict) and isinstance(b,dict):
   r=copy.deepcopy(a)
   for k,v in b.items(): r[k]=Check.merge(r[k],v) if k in r else copy.deepcopy(v)
   return r
  return copy.deepcopy(b)
 @staticmethod
 def canon(x):
  if isinstance(x,dict):
   r={}
   for k,v in x.items():
    cv=Check.canon(v)
    if k=="items" and cv==[]: continue
    if k=="drops" and cv=={}: continue
    r[k]=cv
   return r
  if isinstance(x,list): return [Check.canon(v) for v in x]
  return x
 def run(self):
  self.load(); self.check_equipment(); self.check_catalogs(); self.check_old_materials()
  self.check_batches(); self.check_shops(); self.check_compatibility(); self.check_intake()
  self.report["errors"]=self.errors; self.report["pending"]=self.pending
  self.report["status"]="FAIL" if self.errors or (self.pending and not self.allow_pending) else ("PASS_WITH_PENDING" if self.pending else "PASS")
  return self.report
 def check_equipment(self):
  eq=self.equip(); sets=self.sets(); expected={f"20a{i:05d}" for i in range(400,457)}
  missing=sorted(expected-set(eq)); stages=[]; targetsets=set(); randomcount=0; randbad=[]
  for st in STAGES:
   rows=[eq[f"20a{i:05d}"][1] for i in range(st["start"],st["end"]+1) if f"20a{i:05d}" in eq]
   sids={str(x.get("equipment",{}).get("setId")) for x in rows if x.get("equipment",{}).get("setId")}
   targetsets |= sids
   stages.append({"stage":st["name"],"level":st["level"],"equipment":len(rows),"setIds":sorted(sids)})
   if len(rows)!=19: self.err("stage_equipment_count",stages[-1])
   if len(sids)!=3: self.err("stage_set_count",stages[-1])
   for item in rows:
    if item.get("equipment",{}).get("requiredLevel")!=st["level"]: self.err("stage_level",item.get("id"))
    expected_enchant={20:2,25:3,30:4}[st["level"]]
    actual_enchant=item.get("equipment",{}).get("enchant",{}).get("maxSlots")
    if actual_enchant!=expected_enchant: self.err("stage_enchant_slots",{"itemId":item.get("id"),"expected":expected_enchant,"actual":actual_enchant})
   for sid in sids:
    members=[x for x in rows if x.get("equipment",{}).get("setId")==sid]
    slots=Counter(x.get("equipment",{}).get("slot") for x in members)
    if slots!=Counter({"HEAD":1,"CHEST":1,"LEGS":1,"FEET":1}): self.err("set_four_slots",{"stage":st["name"],"setId":sid,"slots":dict(slots)})
    if sid not in sets: self.err("set_effect_missing",sid)
  if missing: self.err("expected_equipment_ids",missing)
  if len(targetsets)!=9: self.err("expected_nine_sets",sorted(targetsets))
  for iid in expected & set(eq):
   for n,s in enumerate(eq[iid][1].get("equipment",{}).get("stats",[]) or []):
    if "random" in s:
     randomcount+=1
     try: self.validate_random(s)
     except Exception as e: randbad.append({"itemId":iid,"statIndex":n,"error":str(e)})
  if randbad: self.err("equipment_random_contract",randbad)
  self.report.update(equipmentStages=stages,equipmentCount=len(expected & set(eq)),setCount=len(targetsets),
                     randomStatsChecked=randomcount,randomContractFailures=randbad)
  self.check_monotonic(eq)
 def validate_random(self,s):
  if set(s)-{"status","type","value","random"}: raise ValueError("unsupported stat fields")
  r=s["random"]
  if set(r)-{"offsetMin","offsetMax","step","zeroChance"} or not {"offsetMin","offsetMax","step"}<=set(r): raise ValueError("random keys")
  def dec(v):
   if isinstance(v,bool) or not isinstance(v,(int,float,str)): raise ValueError("not numeric")
   d=Decimal(str(v))
   if not d.is_finite() or d.as_tuple().exponent < -4 or Decimal(str(float(d)))!=d: raise ValueError("precision/roundtrip")
   return d
  base=dec(s.get("value")); lo=dec(r["offsetMin"]); hi=dec(r["offsetMax"]); step=dec(r["step"]); zero=dec(r.get("zeroChance",0))
  if lo>hi or step<=0 or (hi-lo)%step or int((hi-lo)/step)+1>1000000: raise ValueError("range/step/candidate count")
  if not 0<=zero<=1: raise ValueError("zeroChance")
  for x in (base+lo,base+hi):
   if not math.isfinite(float(x)) or Decimal(str(float(x)))!=x: raise ValueError("bound roundtrip")
  if zero and base+lo<=0<=base+hi: raise ValueError("zero chance interval includes zero")
  if hi>lo and float(step)<math.ulp(max(abs(float(base+lo)),abs(float(base+hi)))): raise ValueError("step below ULP")
 def check_monotonic(self,eq):
  def expected(stat):
   value=stat.get("value",0)
   if isinstance(value,str) and "~" in value: value=sum(float(q) for q in value.split("~",1))/2
   elif isinstance(value,dict): value=(float(value["min"])+float(value["max"]))/2
   else: value=float(value)
   r=stat.get("random")
   if r:
    bonus=(float(r["offsetMin"])+float(r["offsetMax"]))/2
    value+=bonus*(1-float(r.get("zeroChance",0)))
   return value
  def stat_value(iid,predicate):
   row=eq.get(iid,(None,{}))[1]
   return sum(expected(s) for s in row.get("equipment",{}).get("stats",[]) or [] if predicate(s))
  series=[]; regress=[]
  attack_statuses={"MELEE_ATTACK","RANGED_ATTACK","MAGIC_ATTACK"}
  for off in range(7):
   ids=[f"20a{i:05d}" for i in (400+off,419+off,438+off)]
   values=[stat_value(i,lambda s:s.get("status") in attack_statuses) for i in ids]
   series.append({"kind":"primary_attack","ids":ids,"expected":values})
   if any(b<a for a,b in zip(values,values[1:])): regress.append(series[-1])
  for archetype_index,archetype in enumerate(("guard","hunter","mage")):
   set_values=[]
   for slot_index,slot in enumerate(("HEAD","CHEST","LEGS","FEET")):
    ids=[f"20a{start+7+archetype_index*4+slot_index:05d}" for start in (400,419,438)]
    if any(eq.get(i,(None,{}))[1].get("equipment",{}).get("slot")!=slot for i in ids):
     regress.append({"kind":"armor_slot_mapping","archetype":archetype,"slot":slot,"ids":ids})
     continue
    values=[stat_value(i,lambda s:s.get("status")=="DEFENSE") for i in ids]
    row={"kind":"armor_defense","archetype":archetype,"slot":slot,"ids":ids,"expected":values}
    series.append(row); set_values.append(values)
    if any(b<a for a,b in zip(values,values[1:])): regress.append(row)
   if len(set_values)==4:
    totals=[sum(set_values[p][t] for p in range(4)) for t in range(3)]
    row={"kind":"armor_set_defense_total","archetype":archetype,"expected":totals}
    series.append(row)
    if any(b<a for a,b in zip(totals,totals[1:])): regress.append(row)
  if regress: self.err("primary_attack_or_armor_defense_progression",regress)
  self.report["seriesProgression"]={"rule":"expected primary attack and per-piece DEFENSE do not decrease across Lv20/Lv25/Lv30; random zeroChance included; side stats/tradeoffs and unlike attributes are not summed","checks":len(series),"regressions":regress,"values":series}
 def check_catalogs(self):
  statusdoc=self.yaml_file(self.repo/"40_filebase/75.shared.status/v1.status_types.yml")
  tagdoc=self.yaml_file(self.repo/"40_filebase/76.shared.tag/v1.tags.yml")
  statuses={str(x.get("id")) for x in statusdoc.get("statuses",[])}
  tags={str(x.get("id")) for x in tagdoc.get("tags",[]) if x.get("category")=="EQUIPMENT"}
  sets=self.sets(); eqids={f"20a{i:05d}" for i in range(400,457)}
  mobids=set()
  bp=self.repo/BATCH
  if bp.exists():
   with bp.open(encoding="utf-8-sig",newline="") as f: mobids={r["mob_id"] for r in csv.DictReader(f)}
  target=set()
  for p,d in self.data.items():
   if isinstance(d,dict) and (str(d.get("id")) in eqids or (p.parent.name=="set_effect" and str(d.get("id","")).startswith(("lapis_","lumina_","astra_"))) or d.get("id") in mobids or p.as_posix().endswith(tuple(SHOP_FILES.values()))):
    target.add(p)
  itemids={str(d['id']) for p,d in self.data.items() if p.is_relative_to(self.repo/'40_filebase/10.features.item') and isinstance(d,dict) and d.get('category') and str(d.get('id',''))}
  prefixes={'buff':'70.shared.buff/','skill':'30.features.skill/','mob':'40.features.mob/',
            'class':'20.features.class/','shop':'45.features.shop/','world':'60.features.world/',
            'recipe':'85.features.recipe/','loot_table':'80.shared.loot/table/','loot_pool':'80.shared.loot/pool/'}
  refids={prefix:{str(d['id']) for p,d in self.data.items() if isinstance(d,dict) and d.get('id') and p.relative_to(self.repo/'40_filebase').as_posix().startswith(directory)} for prefix,directory in prefixes.items()}
  refids['item']=itemids;refids['set']=set(sets)
  refids['rune']={str(d['id']) for p,d in self.data.items() if isinstance(d,dict) and d.get('category')=='rune'}
  for alias,canonical in {'bf':'buff','sk':'skill','mb':'mob','it':'item','st':'set','rn':'rune','lt':'loot_table','lp':'loot_pool'}.items():refids[alias]=refids[canonical]
  errors={"status":[],"tag":[],"set":[],"ref":[],"item":[]}
  def walk(x,p,loc="$"):
   if isinstance(x,dict):
    if "status" in x and ("stats" in loc or "baseStats" in loc) and str(x["status"]) not in statuses: errors["status"].append((self.rel(p),loc,x["status"]))
    if "tag" in x and isinstance(x["tag"],str) and "equipment" in loc and x["tag"] not in tags: errors["tag"].append((self.rel(p),loc,x["tag"]))
    if "setId" in x and str(x["setId"]) not in sets: errors["set"].append((self.rel(p),loc,x["setId"]))
    if isinstance(x.get("ref"),str):
     prefix,separator,rid=x["ref"].partition(":")
     if not separator or prefix not in refids or rid not in refids[prefix]: errors["ref"].append((self.rel(p),loc,x["ref"]))
    if "itemId" in x:
     v=x["itemId"]; rid=v.get("ref","") if isinstance(v,dict) else str(v)
     rid=rid.split(":",1)[-1]
     if rid not in itemids: errors["item"].append((self.rel(p),loc,rid))
    for k,v in x.items(): walk(v,p,loc+"."+str(k))
   elif isinstance(x,list):
    for i,v in enumerate(x): walk(v,p,f"{loc}[{i}]")
  for p in target: walk(self.data[p],p)
  for k,v in errors.items():
   if v: self.err("catalog_"+k,v)
  self.report["catalogResolution"]={k:len(v) for k,v in errors.items()}
 def check_old_materials(self):
  csvpath=self.repo/OLD_MATS
  if not csvpath.exists(): self.pend("old_material_assignments_csv_missing",OLD_MATS); return
  with csvpath.open(encoding="utf-8-sig",newline="") as f: rows=list(csv.DictReader(f))
  failures=[]; checked=[]
  for row in rows:
   iid=row.get("item_id",""); ep=row.get("enemy_path",""); mp=self.repo/row.get("material_path",""); enemy=self.repo/ep
   if not mp.is_file() or not enemy.is_file():
    self.pend("old_material_or_enemy_missing",{"itemId":iid,"material":mp.is_file(),"enemy":enemy.is_file()}); continue
   mat=self.yaml_file(mp); mob=self.yaml_file(enemy)
   if str(mat.get("id"))!=iid: failures.append({"itemId":iid,"problem":"material id/path mismatch"})
   profiles=mob.get("levels") or [{"level":mob.get("level"),"drops":mob.get("drops",{})}]
   valid=0
   for profile in profiles:
    merged=self.merge({"drops":mob.get("drops",{})},{"drops":profile.get("drops",{})})
    drops=(merged.get("drops") or {}).get("items",[]) or []
    matches=[d for d in drops if self.ref(d) in (iid,"item:"+iid)]
    if len(matches)==1 and all(float(d.get("rate",-1))==35 and str(d.get("amount",1))=="1" and d.get("luckAffected") is True for d in matches): valid+=1
   if valid!=len(profiles): failures.append({"itemId":iid,"profiles":len(profiles),"validProfiles":valid,"problem":"expected one 35%/1/luck drop each profile"})
   try:
    old=self.git_yaml(ENEMY_BASE,ep)
    if self.countid(old,iid): failures.append({"itemId":iid,"problem":"drop already in historical baseline"})
    stripped=self.stripid(copy.deepcopy(mob),iid)
    if self.canon(stripped)!=self.canon(old): failures.append({"itemId":iid,"problem":"enemy differs from baseline after removing only assigned drop","path":ep})
   except Exception as e: self.pend("old_enemy_historical_compare",{"itemId":iid,"error":str(e)})
   checked.append({"itemId":iid,"profiles":len(profiles),"validProfiles":valid})
  if len(rows)!=25: failures.append({"expectedRows":25,"actualRows":len(rows)})
  if failures: self.err("old_materials_enemy_integrity",failures)
  self.report["oldMaterialsEnemyIntegrity"]={"assignmentRows":len(rows),"checked":len(checked),"valid":not failures and len(checked)==len(rows),"failures":failures}
 @staticmethod
 def countid(x,iid):
  n=0
  def walk(v):
   nonlocal n
   if isinstance(v,dict):
    if isinstance(v.get("drops"),dict): n+=sum(1 for z in v["drops"].get("items",[]) or [] if Check.ref(z) in (iid,"item:"+iid))
    for z in v.values(): walk(z)
   elif isinstance(v,list):
    for z in v: walk(z)
  walk(x); return n
 @staticmethod
 def stripid(x,iid):
  def walk(v):
   if isinstance(v,dict):
    d=v.get("drops")
    if isinstance(d,dict) and isinstance(d.get("items"),list): d["items"]=[z for z in d["items"] if Check.ref(z) not in (iid,"item:"+iid)]
    for z in v.values(): walk(z)
   elif isinstance(v,list):
    for z in v: walk(z)
  walk(x); return x
 def check_batches(self):
  bp=self.repo/BATCH; mp=self.repo/NEW_MATS
  if not bp.exists() or not mp.exists(): self.pend("mob_registration_csv_missing",{"batch":bp.exists(),"materials":mp.exists()}); return
  with bp.open(encoding="utf-8-sig",newline="") as f: batches=list(csv.DictReader(f))
  with mp.open(encoding="utf-8-sig",newline="") as f: newm=list(csv.DictReader(f))
  java_path=self.repo/"10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/AstralRecord.java"
  java=java_path.read_text(encoding="utf-8-sig") if java_path.exists() else ""
  mobs={str(d.get("id")):(p,d) for p,d in self.data.items() if isinstance(d,dict) and d.get("type")=="MOB" and d.get("id")}
  issues=[]; profile_count=0
  if len(batches)!=23 or len({r.get("skill_id") for r in batches})!=23: issues.append({"problem":"expected 23 unique BATCH_IDS"})
  for row in batches:
   mid,sid=row.get("mob_id",""),row.get("skill_id","")
   if mid not in mobs: issues.append({"mob":mid,"problem":"mob master missing"}); continue
   p,mob=mobs[mid]; levels=mob.get("levels",[]) or []; profile_count+=len(levels)
   if len(levels)!=4: issues.append({"mob":mid,"problem":"expected 4 profiles","count":len(levels)})
   seen=False
   for lv in levels or [mob]:
    ai=self.merge(mob.get("ai",{}),lv.get("ai",{})); skills=(ai.get("combat",{}) or {}).get("skills",[]) or []
    if any((z.get("id") if isinstance(z,dict) else z)==sid for z in skills): seen=True
   if not seen: issues.append({"mob":mid,"skill":sid,"problem":"skill ID absent from profiles"})
   ex=self.repo/row.get("executor_path","")
   if not ex.is_file(): issues.append({"skill":sid,"problem":"executor missing"}); continue
   text=ex.read_text(encoding="utf-8-sig"); cm=re.search(r"\bclass\s+(\w+)",text); cls=cm.group(1) if cm else ""
   if sid not in text: issues.append({"skill":sid,"problem":"executor does not declare ID"})
   var=cls[:1].lower()+cls[1:] if cls else ""
   if not cls or cls not in java or not re.search(r"mobSkillRegistry\.register\s*\(\s*(?:new\s+)?"+re.escape(var),java):
    issues.append({"skill":sid,"class":cls,"problem":"AstralRecord registration not found"})
  if profile_count!=92: issues.append({"expectedProfiles":92,"actual":profile_count})
  if issues: self.err("BATCH_IDS_AstralRecord_skills",issues)
  matissues=[]
  if len(newm)!=33 or len({r.get("item_id") for r in newm})!=33: matissues.append({"problem":"expected 33 unique NEW_MATERIALS rows"})
  for row in newm:
   iid=row.get("item_id",""); matches=[self.data[p] for p in self.ids.get(iid,[]) if isinstance(self.data[p],dict) and self.data[p].get("category")=="material"]
   if not matches: matissues.append({"itemId":iid,"problem":"material master missing"}); continue
   species=row.get("species","").split(" / ",1)[0].strip()
   if species not in mobs: matissues.append({"itemId":iid,"problem":"species mob missing","species":species}); continue
   mob=mobs[species][1]; wanted={x.strip() for x in row.get("levels","").split(",") if x.strip()}; levels=mob.get("levels",[]) or []; valid=0
   for lv in levels:
    if str(lv.get("level")) not in wanted: continue
    merged=self.merge({"drops":mob.get("drops",{})},{"drops":lv.get("drops",{})})
    ds=(merged.get("drops") or {}).get("items",[]) or []
    found=[d for d in ds if self.ref(d) in (iid,"item:"+iid)]
    if len(found)==1 and float(found[0].get("rate",-1))==float(row.get("rate_final") or 0) and str(found[0].get("amount",1))==str(row.get("amount_final") or "1"): valid+=1
   if valid!=len(wanted): matissues.append({"itemId":iid,"species":species,"expectedProfiles":len(wanted),"validProfiles":valid})
  if matissues: self.err("new_material_profile_registration",matissues)
  self.report["centralRegistration"]={"BATCHrows":len(batches),"uniqueMobIds":len({x.get("mob_id") for x in batches}),"profiles":profile_count,"skillIds":len({x.get("skill_id") for x in batches}),"newMaterialRows":len(newm),"uniqueMaterialIds":len({x.get("item_id") for x in newm}),"skillIssues":issues,"materialIssues":matissues}
 def check_shops(self):
  shops={}
  for sid,path in SHOP_FILES.items():
   p=self.repo/path
   if p.exists(): shops[sid]=self.yaml_file(p)
   else: self.err("shop_master_missing",path)
  expected={f"20a{i:05d}" for i in range(400,457)}
  products={}; manifests=[]
  for st in STAGES:
   p=self.repo/st["manifest"]
   if not p.exists(): self.pend("manifest_missing",st["manifest"]); continue
   doc=json.loads(p.read_text(encoding="utf-8-sig")); rows=doc.get(st["array"],[]); manifests.append((st,doc,rows))
   for r in rows: products[str(r.get("itemId"))]=(st,r)
  if set(products)!=expected: self.err("manifest_expected_57",{"missing":sorted(expected-set(products)),"extra":sorted(set(products)-expected)})
  cp=self.repo/SHOP_CSV
  con=[]
  if cp.exists():
   with cp.open(encoding="utf-8-sig",newline="") as f: con=list(csv.DictReader(f))
  else: self.pend("shop_connections_csv_missing",SHOP_CSV)
  cby={r.get("item_id"):r for r in con}
  if len(con)!=57 or set(cby)!=expected: self.err("shop_connections_57",{"rows":len(con),"uniqueIds":len(cby)})
  issues=[]; bags=[]
  for iid,(st,r) in products.items():
   sid=r.get("shopId") or r.get("primaryRoute",{}).get("shopPlacement",{}).get("shopId")
   page=r.get("page",r.get("primaryRoute",{}).get("shopPlacement",{}).get("page"))
   slot=r.get("slot",r.get("primaryRoute",{}).get("shopPlacement",{}).get("logicalSlot"))
   gold=r.get("priceGold",r.get("primaryRoute",{}).get("requiredCurrency"))
   mats=r.get("requiredItems",r.get("primaryRoute",{}).get("requiredItems",[]))
   if st["name"]=="Lapis": mats=r.get("primaryRoute",{}).get("requiredItems",[])
   actual=[x for x in shops.get(sid,{}).get("items",[]) if self.ref(x) in (iid,"item:"+iid)]
   want=sorted((str(x["itemId"]),int(x["amount"])) for x in mats)
   if len(actual)!=1: issues.append({"item":iid,"problem":"shop entry count","count":len(actual)})
   else:
    x=actual[0]; got=sorted((self.ref(m).split(":")[-1],int(m.get("amount",0))) for m in x.get("requiredItems",[]) or [])
    if (int(x.get("page",-1)),int(x.get("slot",-1)),int(x.get("priceGold",-1)),got)!=(int(page),int(slot),int(gold),want): issues.append({"item":iid,"problem":"manifest/shop mismatch"})
   cr=cby.get(iid)
   if not cr: issues.append({"item":iid,"problem":"CSV row missing"}); continue
   parsed=[]
   for bit in (cr.get("required_materials") or "").split(";"):
    if not bit.strip(): continue
    m=re.fullmatch(r"\s*([A-Za-z0-9_]+)x(\d+)\s*",bit)
    if m: parsed.append((m.group(1),int(m.group(2))))
    else: issues.append({"item":iid,"problem":"CSV material syntax","value":bit})
   if (cr.get("shop_id"),int(cr.get("page",-1)),int(cr.get("logical_slot",-1)),int(cr.get("price_gold",-1)),sorted(parsed))!=(sid,int(page),int(slot),int(gold),want): issues.append({"item":iid,"problem":"manifest/CSV mismatch"})
   slots=1
   for m in mats:
    data=next((self.data[p] for p in self.ids.get(str(m["itemId"]),[]) if isinstance(self.data[p],dict) and self.data[p].get("category")=="material"),{})
    slots+=math.ceil(int(m["amount"])/int(data.get("maxStack",64) or 64))
    if slots>28: bags.append({"item":iid,"slots":slots})
  if issues: self.err("shop_manifest_csv_agreement",issues)
  if bags: self.err("simple_bag_28_slots",bags)
  self.check_old_shops(shops,expected)
  conflicts=[]
  for sid,doc in shops.items():
   seen=set()
   for item in doc.get("items",[]) or []:
    key=(item.get("page"),item.get("slot"))
    if key in seen: conflicts.append({"shop":sid,"duplicateSlot":key})
    seen.add(key)
    if not isinstance(key[0],int) or not isinstance(key[1],int) or not 0<=key[1]<28: conflicts.append({"shop":sid,"invalidSlot":key})
    if sid=="weapon_craftsman_shop" and key[1] in (set(range(7,14))|set(range(21,28))): conflicts.append({"shop":sid,"reservedEmptySlot":key})
    if sid=="armor_craftsman_shop" and key[1]%7==3: conflicts.append({"shop":sid,"reservedColumn3":key})
  if conflicts: self.err("shop_slots",conflicts)
  srcissues=[]; sourcecount=0
  for st,doc,rows in manifests:
   sources=doc.get("sources",doc.get("materialSources",[])); sourcecount+=len(sources)
   for s in sources:
    iid=str(s.get("itemId",""))
    if not any(isinstance(self.data[p],dict) and self.data[p].get("category")=="material" for p in self.ids.get(iid,[])): srcissues.append({"stage":st["name"],"itemId":iid,"problem":"source material missing"})
  if srcissues: self.err("manifest_sources",srcissues)
  self.report["shopIntegration"]={"shops":len(shops),"connectionRows":len(con),"manifestProducts":len(products),"sourceRows":sourcecount,"routeIssues":issues,"bagIssues":bags,"slotIssues":conflicts}
 def check_old_shops(self,shops,expected):
  issues=[]; count=0
  for sid,path in SHOP_FILES.items():
   try: old=self.git_yaml(BASE,path)
   except Exception as e: self.pend("shop_baseline",{"shop":sid,"error":str(e)}); continue
   orig={self.ref(x).split(":")[-1]:x for x in old.get("items",[]) or []}
   cur={self.ref(x).split(":")[-1]:x for x in shops.get(sid,{}).get("items",[]) or []}
   count+=len(orig)
   for iid,x in orig.items():
    if iid not in cur: issues.append({"shop":sid,"item":iid,"problem":"original item missing"}); continue
    if self.canon({k:v for k,v in x.items() if k!="slot"})!=self.canon({k:v for k,v in cur[iid].items() if k!="slot"}): issues.append({"shop":sid,"item":iid,"problem":"changed beyond allowed slot"})
   if set(orig)&expected: issues.append({"shop":sid,"problem":"new item overlaps old"})
  if count!=19: issues.append({"expectedOldProducts":19,"actual":count})
  if issues: self.err("existing_shop_product_preservation",issues)
  self.report["existingShopProducts"]={"baselineCount":count,"preservedExceptSlot":not issues,"issues":issues}
 def check_compatibility(self):
  # Ask Git for paths using ASCII-only invocation, then filter in Python. This
  # avoids Windows argv code-page conversion for Japanese contract filenames.
  changed=self.git("diff","--name-only",BASE,"HEAD").splitlines()
  dirty_all=self.git("diff","--name-only","HEAD").splitlines()+self.git("ls-files","--others","--exclude-standard").splitlines()
  prefixes=("10_plugin/AstralRecord/src/main/","20_api/AstralRecordApi/",
            "60_tool/skilltree-editor/src/","60_tool/skilltree-editor/tests/")
  exact={"10_plugin/AstralRecord/pom.xml",
         "00_docs/50_Filebase設計書/feature/16-equipment.md",
         "40_filebase/10.features.item/20.equipment/docs.equipment.YAMLスキーマ定義.md",
         "40_filebase/10.features.item/20.equipment/set_effect/docs.set_effect.YAMLスキーマ定義.md"}
  def relevant(path):
   return path in exact or path.startswith(prefixes) or (path.startswith("60_tool/skilltree-editor/") and path.endswith((".csproj",".sln",".props",".targets")))
  base=[x for x in changed if relevant(x)]
  dirty=[x for x in dirty_all if relevant(x)]
  # The real Editor acceptance found a metadata-only mismatch for the already
  # supported rune wrapper. Pin the reviewed documentation and generic test
  # blobs; any runtime change, later edit, or uncommitted change still fails.
  reviewed={
   "40_filebase/10.features.item/20.equipment/docs.equipment.YAMLスキーマ定義.md":"5408b5b0b870150fb95d34154bf97297203d3db3",
   "60_tool/skilltree-editor/tests/SkillTreeEditor.Server.Tests/MasterDataTests.cs":"a043c4938bdda070c67be96567152984658f6158"}
  accepted=[]; unexpected=[]
  for path in base:
   expected_blob=reviewed.get(path)
   actual_blob=self.git("rev-parse",f"HEAD:{path}") if expected_blob else None
   if expected_blob and actual_blob==expected_blob:
    accepted.append({"path":path,"blob":actual_blob})
   else: unexpected.append(path)
  if unexpected or dirty: self.err("saved_random_compatibility_gate",{"unexpectedBaselineChanges":unexpected,"uncommitted":dirty})
  self.report["savedRandomCompatibility"]={
   "sourceAndContractsUnchanged":not base and not dirty,
   "runtimeSourceUnchanged":not unexpected and not dirty,
   "baselineChanges":base,"uncommitted":dirty,
   "reviewedMetadataAndTestChanges":accepted,
   "reviewedChangeCommit":"35bbdf3475dc4c65ab0ca3cf1a994990f6b54145",
   "reason":"Editor metadata now accepts existing random range wrapper; actual loader, scalar snapshot persistence, and saved roll decoder contracts are unchanged; see HEAVY_VALIDATION.json for executed acceptance",
   "unexpectedChanges":unexpected}
 def check_intake(self):
  results=[]; adjustments=[]
  for label,sha,count in SOURCES:
   try:
    parent=self.git("show","-s","--format=%P",sha); paths=self.git("diff-tree","--no-commit-id","--name-only","-r",sha).splitlines(); mismatch=[]
    for p in paths:
     try:
      if self.git("rev-parse",f"{sha}:{p}")!=self.git("rev-parse",f"{INTAKE}:{p}"): mismatch.append(p)
     except Exception: mismatch.append(p)
    row={"stage":label,"sha":sha,"parent":parent,"paths":len(paths),"expected":count,"intakeBlobMatches":len(paths)-len(mismatch),"mismatches":mismatch}
    results.append(row)
    if len(paths)!=count or mismatch: self.err("historical_source_intake",row)
    changed=[]
    for p in paths:
     try:
      if self.git("rev-parse",f"{INTAKE}:{p}")!=self.git("rev-parse",f"HEAD:{p}"): changed.append(p)
     except Exception: changed.append(p)
    if changed: adjustments.append({"stage":label,"sourceSha":sha,"pathsChangedAfterIntake":changed})
   except Exception as e: self.err("historical_source_intake",{"stage":label,"error":str(e)})
  self.report["sourceCommitDiffTree"]=results
  self.report["expectedAdjustmentsAfterIntake"]=adjustments

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--repo",required=True)
 p.add_argument("--output")
 p.add_argument("--allow-pending",action="store_true")
 a=p.parse_args(); repo=Path(a.repo)
 if not repo.is_dir(): p.error(f"repo directory not found: {repo}")
 result=Check(repo,a.allow_pending).run(); text=json.dumps(result,ensure_ascii=False,indent=2)+"\n"
 if a.output:
  out=Path(a.output); out.parent.mkdir(parents=True,exist_ok=True); out.write_text(text,encoding="utf-8",newline="\n")
 else: sys.stdout.write(text)
 return 1 if result["status"]=="FAIL" else 0
if __name__=="__main__": raise SystemExit(main())
