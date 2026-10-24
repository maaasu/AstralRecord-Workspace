"""Check MC-BUILD-Q1 review records, not Minecraft geometry or image quality.

Evidence is byte-hashed and bound to one final artifact. Status and aesthetic
judgments still require the named reviewer and real domain-specific checks.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

VERSION = "1.0.0"
WEIGHTS = {"silhouette": 20, "roof": 15, "facade": 15, "all_elevations": 15,
           "interfaces": 10, "interior": 10, "material_details": 10, "site": 5}
GATES = {"requirements", "preservation", "envelope", "supports", "routes",
         "fixtures", "interior", "visual_review", "artifact_consistency", "regressions"}
ALWAYS_REQUIRED = {"requirements", "preservation", "visual_review", "artifact_consistency"}
SIDES = {"front", "back", "left", "right"}
FULL_VIEWS = SIDES | {"overall", "connections"}


def evaluate(record, root):
    errors = []

    def require(test, message):
        if not test:
            errors.append(message)

    def nonempty(value):
        return isinstance(value, str) and bool(value.strip())

    def check_file(item, label):
        if not isinstance(item, dict) or not nonempty(item.get("path")):
            errors.append(f"{label}: file path required")
            return
        path = Path(item["path"])
        if not path.is_absolute():
            path = root / path
        try:
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
        except (OSError, ValueError) as exc:
            errors.append(f"{label}: cannot read evidence ({type(exc).__name__})")
            return
        require(actual == item.get("sha256"), f"{label}: SHA-256 missing or mismatched")

    require(record.get("standard") == "MC-BUILD-Q1" and record.get("version") == VERSION,
            "Unsupported standard/version")
    scope = record.get("scope", {})
    mode = scope.get("mode")
    require(mode in ("full_build", "targeted_edit"), "scope.mode must be full_build or targeted_edit")
    require(nonempty(scope.get("description")), "scope.description required")
    reviewer = record.get("reviewer", {})
    require(reviewer.get("kind") in ("agent", "human") and nonempty(reviewer.get("name")),
            "Named agent/human reviewer required")
    require(record.get("verification_level") in ("static_and_preview", "in_game"),
            "verification_level must distinguish static_and_preview from in_game")
    artifact = record.get("final_artifact", {})
    check_file(artifact, "final_artifact")
    target_hash = artifact.get("sha256")
    evidence = record.get("evidence", {})
    require(isinstance(evidence, dict) and bool(evidence), "evidence must contain verified files")
    if not isinstance(evidence, dict):
        evidence = {}
    for key, item in evidence.items():
        check_file(item, f"evidence.{key}")
        require(isinstance(item, dict) and item.get("target_sha256") == target_hash and bool(target_hash),
                f"evidence.{key}: not bound to final artifact")
        require(isinstance(item, dict) and item.get("kind") in ("plan", "image", "data_report", "review"),
                f"evidence.{key}: evidence kind required")

    def refs(item, label, kind=None):
        ids = item.get("evidence", [])
        require(isinstance(ids, list) and bool(ids), f"{label}: evidence references required")
        if not isinstance(ids, list):
            return
        for ident in ids:
            require(isinstance(ident, str) and ident in evidence, f"{label}: unknown evidence reference {ident!r}")
        if kind:
            require(any(isinstance(ident, str) and isinstance(evidence.get(ident), dict)
                        and evidence[ident].get("kind") == kind for ident in ids),
                    f"{label}: {kind} evidence required")

    plan_id = scope.get("plan_evidence")
    require(isinstance(plan_id, str) and isinstance(evidence.get(plan_id), dict)
            and evidence[plan_id].get("kind") == "plan", "Scope must reference a saved design/review plan")
    gates = record.get("gates", {})
    require(isinstance(gates, dict) and set(gates) == GATES, "All 10 named gates must be recorded")
    if not isinstance(gates, dict):
        gates = {}
    for name in GATES:
        item = gates.get(name, {})
        if not isinstance(item, dict):
            errors.append(f"gates.{name}: object required")
            continue
        require(nonempty(item.get("reason")), f"gates.{name}: reason required")
        status = item.get("status")
        if status == "not_applicable":
            require(name not in ALWAYS_REQUIRED, f"gates.{name}: cannot be excluded")
            require(nonempty(item.get("scope_reason")), f"gates.{name}: predeclared scope reason required")
        else:
            require(status == "pass", f"gates.{name}: not passed ({status})")
            refs(item, f"gates.{name}")

    features = record.get("critical_features", [])
    require(isinstance(features, list) and bool(features), "At least one critical design feature required")
    for item in features if isinstance(features, list) else []:
        require(nonempty(item.get("description")) and item.get("status") == "pass",
                "Critical feature missing, mismatched or unverified")
        refs(item, "critical_feature")

    dimensions = record.get("dimensions", {})
    require(isinstance(dimensions, dict) and set(dimensions) == set(WEIGHTS), "All eight dimensions must be recorded")
    if not isinstance(dimensions, dict):
        dimensions = {}
    earned = possible = 0
    applicable = set()
    for name, weight in WEIGHTS.items():
        item = dimensions.get(name, {})
        if not isinstance(item, dict):
            errors.append(f"dimensions.{name}: object required")
            continue
        require(type(item.get("applicable")) is bool, f"dimensions.{name}: applicability required")
        if item.get("applicable") is False:
            require(nonempty(item.get("scope_reason")), f"dimensions.{name}: scope reason required")
            require(item.get("score") is None, f"dimensions.{name}: excluded dimensions cannot have scores")
            if mode == "full_build":
                require(name not in {"silhouette", "all_elevations", "material_details", "site"},
                        f"dimensions.{name}: required for a full building")
            continue
        applicable.add(name)
        score = item.get("score")
        valid = type(score) is int and 0 <= score <= 4
        require(valid, f"dimensions.{name}: observed integer score 0..4 required")
        require(valid and score >= 3, f"dimensions.{name}: below 3/4")
        require(nonempty(item.get("reason")), f"dimensions.{name}: scoring reason required")
        refs(item, f"dimensions.{name}")
        possible += weight
        if valid:
            earned += weight * score / 4
    require(bool(possible), "At least one visual dimension must be reviewed")
    total = 100 * earned / possible if possible else None
    if mode == "full_build":
        require(total is not None and total >= 85, "Weighted visual score below 85/100")

    views = record.get("views", {})
    required_views = set(scope.get("required_views", []))
    required_views |= FULL_VIEWS if mode == "full_build" else {"detail", "context"}
    if "roof" in applicable:
        required_views.add("roof")
    if "interior" in applicable:
        required_views.add("interior")
    for name in required_views:
        item = views.get(name, {})
        require(item.get("status") == "reviewed", f"views.{name}: not reviewed")
        refs(item, f"views.{name}", "image")
    elevations = record.get("elevations", {})
    sides = SIDES if mode == "full_build" else set(scope.get("reviewed_elevations", []))
    if "all_elevations" in applicable:
        require(bool(sides), "Applicable elevation score requires individual sides")
    side_scores = []
    for name in sides:
        item = elevations.get(name, {})
        score = item.get("score")
        valid = type(score) is int and 3 <= score <= 4
        require(valid and nonempty(item.get("reason")), f"elevations.{name}: needs 3/4+ and reason")
        refs(item, f"elevations.{name}", "image")
        if valid:
            side_scores.append(score)
    if "all_elevations" in applicable and side_scores:
        value = dimensions["all_elevations"].get("score")
        require(type(value) is int and value <= min(side_scores), "Elevation average cannot hide a weak side")
    require(record.get("blocking_issues") == [], "Blocking issues must be explicitly empty")
    if record.get("verification_level") == "in_game":
        game = record.get("in_game_review", {})
        refs(game, "in_game_review", "review")
        environment = game.get("environment", {})
        require(nonempty(environment.get("minecraft_version")) and nonempty(environment.get("runtime")),
                "in_game_review: actual game environment required")
        actions = game.get("actions", [])
        require(isinstance(actions, list) and bool(actions) and all(nonempty(action) for action in actions),
                "in_game_review: performed actions required")
        require(nonempty(game.get("observations")), "in_game_review: observed results required")
    return {"result": "RECORD_COMPLETE" if not errors else "NOT_COMPLETE", "standard": "MC-BUILD-Q1",
            "weighted_score": round(total, 2) if total is not None else None,
            "errors": errors, "limitation": "Checks review records and file hashes only; does not inspect geometry, image content or game behavior."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("record", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        value = json.loads(args.record.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Record must be a JSON object")
        result = evaluate(value, args.record.resolve().parent)
    except (OSError, ValueError, TypeError, AttributeError, KeyError) as exc:
        result = {"result": "NOT_COMPLETE", "errors": [f"Malformed record: {type(exc).__name__}: {exc}"]}
    content = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        if args.output.resolve() == args.record.resolve():
            parser.error("--output must not overwrite the input review record")
        args.output.write_text(content, encoding="utf-8")
    print(content, end="")
    return 0 if result["result"] == "RECORD_COMPLETE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
