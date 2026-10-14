#!/usr/bin/env python3
"""Read-only inventory and reference audit for AstralRecord item masters."""
import argparse, csv, json, re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
import yaml

DATA_EXTS = {".yml", ".yaml", ".json"}
SOURCE_EXTS = {".java", ".kt", ".cs", ".ts", ".tsx", ".js", ".py", ".json", ".yml", ".yaml", ".xml", ".properties", ".conf", ".config"}
SELF_FUNCTION_CATEGORIES = {"equipment", "consumable", "orb", "sigil", "rune", "currency", "bundle", "pet", "pet_egg"}
CONSUMER_MARKERS = (".requireditems", ".requiredmaterials", ".ingredients", ".learnrequireditems", ".leveluprequireditems", ".hatchmaterials", ".breedmaterials", ".revivematerials", ".acceptrequirements.items")

def load_data(path):
    raw = path.read_text(encoding="utf-8-sig")
    return yaml.safe_load(raw) if path.suffix.lower() in {".yml", ".yaml"} else json.loads(raw)

def add_ref(value, path, item_ids, refs):
    if not isinstance(value, str):
        return
    item_id = value[5:] if value.startswith("item:") else value
    if item_id in item_ids:
        refs[item_id].add(path)

def walk_refs(value, path, item_ids, refs, skip_root_id=False):
    if isinstance(value, dict):
        ref = value.get("ref")
        if isinstance(ref, str):
            add_ref(ref, path + ".ref", item_ids, refs)
        for key, child in value.items():
            if key == "ref" or (skip_root_id and key == "id"):
                continue
            walk_refs(child, path + "." + str(key), item_ids, refs)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            walk_refs(child, path + f"[{index}]", item_ids, refs)
    else:
        add_ref(value, path, item_ids, refs)

def classify(path):
    p = path.lower()
    if any(marker in p for marker in CONSUMER_MARKERS) or ".eggitemid" in p or ".reviveorbitemid" in p:
        return "consumption"
    if "allowedsigilids" in p or ".petitemid" in p:
        return "intrinsic_or_eligibility"
    if "45.features.shop/" in p and ".items[" in p:
        return "shop_listing_or_purchase_output"
    if any(marker in p for marker in ("40.features.mob/", "42.features.gathering/", "43.features.gathering.spawner/", "44.features.fishing.fish/", "80.shared.loot/")):
        return "acquisition"
    if any(marker in p for marker in ("05.features.mail/", "51.features.quest/", "65.features.dungeon/")) and any(word in p for word in ("reward", "clearrewards")):
        return "reward_output"
    if ".requireditemid" in p:
        return "access_requirement"
    if "09.features.guide/" in p or ".targetid" in p or ".targetids" in p:
        return "guide_or_objective_target"
    if ".bundle.items[" in p or ".result.itemid" in p:
        return "item_output"
    return "other"

def write_csv(path, rows, fields):
    with path.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, type=Path)
    ap.add_argument("--output-dir", required=True, type=Path)
    args = ap.parse_args()
    repo, out = args.repo.resolve(), args.output_dir.resolve()
    fb, item_root = repo / "40_filebase", repo / "40_filebase" / "10.features.item"
    if not item_root.is_dir():
        raise SystemExit(f"Item directory not found: {item_root}")
    out.mkdir(parents=True, exist_ok=True)
    items, master_paths, errors = {}, {}, []
    for path in sorted(item_root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in DATA_EXTS:
            continue
        try:
            doc = load_data(path)
        except Exception as exc:
            errors.append((path.relative_to(repo).as_posix(), str(exc)))
            continue
        # Category-less records (such as equipment set definitions) are not item masters.
        if isinstance(doc, dict) and isinstance(doc.get("id"), str) and isinstance(doc.get("category"), str):
            items[doc["id"]] = doc
            master_paths[doc["id"]] = path
    item_ids, refs = set(items), defaultdict(set)
    for path in sorted(fb.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in DATA_EXTS:
            continue
        try:
            doc = load_data(path)
        except Exception as exc:
            errors.append((path.relative_to(repo).as_posix(), str(exc)))
            continue
        walk_refs(doc, path.relative_to(fb).as_posix(), item_ids, refs, path.parent.is_relative_to(item_root))
    refs_by_kind = defaultdict(lambda: defaultdict(set))
    for item_id, paths in refs.items():
        for path in paths:
            refs_by_kind[item_id][classify(path)].add(path)
    consumer_ids = {i for i in items if refs_by_kind[i]["consumption"]}
    rows, candidates = [], []
    fields = ["id", "category", "name", "rarity", "saleValue", "saleValuePositive", "unSellable", "unTradeable", "hasConsumptionReference", "selfFunctionCategoryCaution", "classification", "consumptionRefs", "acquisitionRefs", "rewardOutputRefs", "shopListingRefs", "intrinsicOrEligibilityRefs", "accessRequirementRefs", "guideOrObjectiveRefs", "otherRefs", "allFilebaseRefPaths", "masterFile"]
    for item_id, item in sorted(items.items()):
        category, sale = item.get("category", ""), item.get("saleValue")
        sale_positive = isinstance(sale, (int, float)) and sale > 0
        kinds = refs_by_kind[item_id]
        has_consumer = item_id in consumer_ids
        if has_consumer:
            status = "has_consumption_reference"
        elif sale_positive:
            status = "no_consumption_reference_saleValue_positive"
        elif category in SELF_FUNCTION_CATEGORIES:
            status = "no_consumption_reference_intrinsic_function_category"
        else:
            status = "no_consumption_reference_candidate"
        row = {
            "id": item_id, "category": category, "name": item.get("name", ""), "rarity": item.get("rarity", ""),
            "saleValue": "" if sale is None else sale, "saleValuePositive": sale_positive,
            "unSellable": item.get("unSellable", ""), "unTradeable": item.get("unTradeable", ""),
            "hasConsumptionReference": has_consumer, "selfFunctionCategoryCaution": category in SELF_FUNCTION_CATEGORIES,
            "classification": status,
            "consumptionRefs": " | ".join(sorted(kinds["consumption"])),
            "acquisitionRefs": " | ".join(sorted(kinds["acquisition"])),
            "rewardOutputRefs": " | ".join(sorted(kinds["reward_output"])),
            "shopListingRefs": " | ".join(sorted(kinds["shop_listing_or_purchase_output"])),
            "intrinsicOrEligibilityRefs": " | ".join(sorted(kinds["intrinsic_or_eligibility"])),
            "accessRequirementRefs": " | ".join(sorted(kinds["access_requirement"])),
            "guideOrObjectiveRefs": " | ".join(sorted(kinds["guide_or_objective_target"])),
            "otherRefs": " | ".join(sorted(kinds["other"] | kinds["item_output"])),
            "allFilebaseRefPaths": " | ".join(sorted(refs[item_id])),
            "masterFile": master_paths[item_id].relative_to(repo).as_posix(),
        }
        rows.append(row)
        if not has_consumer:
            candidates.append(row)
    write_csv(out / "ITEM_PURPOSE_AUDIT.csv", rows, fields)
    write_csv(out / "NO_CONSUMPTION_REFERENCE_CANDIDATES.csv", candidates, fields)
    write_csv(out / "NO_CONSUMPTION_REFERENCE_MATERIALS.csv", [row for row in candidates if row["category"] == "material"], fields)

    # Supplementary sweep for literal item IDs in source/config/resource files.
    token_re = re.compile(r"(?<![A-Za-z0-9])[A-Za-z0-9]{8}(?![A-Za-z0-9])")
    source_rows = []
    for top in ("10_plugin", "20_api", "30_web", "50_resourcepack"):
        base = repo / top
        if not base.exists():
            continue
        for path in sorted(base.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in SOURCE_EXTS:
                continue
            try:
                with path.open("r", encoding="utf-8-sig", errors="replace") as stream:
                    for line_no, line in enumerate(stream, 1):
                        for token in sorted(set(token_re.findall(line))):
                            item_id = token.lower()
                            if item_id in item_ids:
                                source_rows.append({"id": item_id, "category": items[item_id].get("category", ""), "name": items[item_id].get("name", ""), "sourceFile": path.relative_to(repo).as_posix(), "line": line_no})
            except OSError:
                continue
    write_csv(out / "SOURCE_LITERAL_ITEM_ID_REFERENCES.csv", source_rows, ["id", "category", "name", "sourceFile", "line"])

    categories = Counter(item.get("category", "") for item in items.values())
    consumer_categories = Counter(items[i].get("category", "") for i in consumer_ids)
    no_consumer = [i for i in items if i not in consumer_ids]
    positive = [i for i in no_consumer if isinstance(items[i].get("saleValue"), (int, float)) and items[i]["saleValue"] > 0]
    nonpositive = [i for i in no_consumer if i not in positive]
    sellable_positive = [i for i in positive if items[i].get("unSellable") is not True]
    new_equipment_ids = {f"20a{number:05d}" for number in range(400, 457)}
    new_equip = [i for i in items if i in new_equipment_ids]
    listed_new = [i for i in new_equip if refs_by_kind[i]["shop_listing_or_purchase_output"]]
    report = [
        "# Item purpose audit snapshot", "",
        f"- Generated date: {datetime.now(timezone.utc).date().isoformat()}",
        f"- Repository: {repo}",
        f"- Category-bearing item masters: {len(items)}",
        f"- Items by category: {dict(sorted(categories.items()))}",
        f"- Items with consumption references: {len(consumer_ids)}; by category: {dict(sorted(consumer_categories.items()))}",
        f"- Items without consumption references: {len(no_consumer)}; by category: {dict(sorted(Counter(items[i].get('category', '') for i in no_consumer).items()))}",
        f"- No-consumption items with saleValue > 0: {len(positive)}; unSellable is not true for {len(sellable_positive)}.",
        f"- No-consumption items with saleValue not positive or missing: {len(nonpositive)}.",
        f"- IDs 20a00400–20a00456: {len(new_equip)}; shop listed {len(listed_new)}; consumption references {sum(i in consumer_ids for i in new_equip)}.",
        f"- Parse errors: {len(errors)}; source literal-ID matches: {len(source_rows)} rows.", "",
        "## Classification", "",
        "Count only input/requirement paths as consumption: Shop requiredItems, recipe ingredients, skill learnRequiredItems/levelUpRequiredItems, equipment requiredMaterials including transcendence, pet hatchMaterials/breedMaterials/reviveMaterials, quest acceptRequirements.items, pet eggItemId mapping, and reviveOrbItemId. Drops, gathering, fishing, loot, mail/quest/dungeon rewards, and shop product listings are acquisition/output refs and are recorded separately.",
        "saleValue > 0 is a separate configured generic economic value. Check unSellable before interpreting it as a player sale path. Equipment, consumable, orb, sigil, rune, currency, bundle, pet, and pet_egg can have their own behavior even without inbound consumption refs; the candidate CSV is a review list, not proof that an item has no function.",
        "The literal source sweep only finds static eight-character IDs in source/config/resource files. Runtime-generated IDs, indirect or dynamic lookup, external server data, and deployed configuration are not covered. Findings describe current filebase references only.", "",
        "## Reproduce", "",
        "Run with -B -X utf8 and provide both required paths:",
        r"& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -B -X utf8 'extract_unused_items.py' --repo 'E:\AstralRecord-Worktrees\field-equipment-final-integration' --output-dir 'E:\AstralRecord-Worktrees\field-equipment-final-integration\00_docs\99_資料\field-equipment-final-integration'",
    ]
    (out / "METHOD_AND_SUMMARY.md").write_text("\n\n".join(report) + "\n", encoding="utf-8")
    print(f"items={len(items)} consumer={len(consumer_ids)} no_consumer={len(no_consumer)} saleValue_positive={len(positive)}")
    print(f"new_equipment={len(new_equip)} shop_listed={len(listed_new)}")
    print(f"source_literal_rows={len(source_rows)} parse_errors={len(errors)}")
    print(f"output_dir={out}")
    for path, error in errors:
        print(f"PARSE_ERROR {path}: {error}")

if __name__ == "__main__":
    main()
