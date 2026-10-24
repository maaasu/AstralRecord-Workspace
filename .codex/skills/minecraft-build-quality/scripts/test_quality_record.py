"""Behavioral tests for the review-record checker; no building is certified."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from check_quality_record import evaluate, WEIGHTS, GATES


class QualityRecordTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "model.json").write_text('{"fixture_only":true}', encoding="utf-8")
        target = hashlib.sha256((self.root / "model.json").read_bytes()).hexdigest()
        evidence = {}
        # Content is deliberately synthetic. This tests the record contract,
        # not rendering, geometry, judgment, or actual architectural quality.
        for name, kind in (("plan", "plan"), ("report", "data_report"), ("photo", "image")):
            path = self.root / (name + ".txt")
            path.write_text("synthetic " + name, encoding="utf-8")
            evidence[name] = {"path": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                              "kind": kind, "target_sha256": target}
        self.record = {"standard": "MC-BUILD-Q1", "version": "1.0.0",
            "scope": {"mode": "full_build", "description": "Synthetic full-build record",
                      "plan_evidence": "plan", "required_views": [], "reviewed_elevations": []},
            "reviewer": {"kind": "agent", "name": "synthetic test fixture"},
            "verification_level": "static_and_preview",
            "final_artifact": {"path": "model.json", "sha256": target}, "evidence": evidence,
            "critical_features": [{"description": "planned silhouette", "status": "pass", "evidence": ["photo"]}],
            "gates": {n: {"status": "pass", "reason": "synthetic result", "evidence": ["report"]} for n in GATES},
            "dimensions": {n: {"applicable": True, "score": 4, "reason": "synthetic review", "evidence": ["photo"]} for n in WEIGHTS},
            "views": {n: {"status": "reviewed", "evidence": ["photo"]} for n in ("front", "back", "left", "right", "overall", "connections", "roof", "interior")},
            "elevations": {n: {"score": 4, "reason": "synthetic side", "evidence": ["photo"]} for n in ("front", "back", "left", "right")},
            "blocking_issues": []}

    def outcome(self):
        return evaluate(self.record, self.root)["result"]

    def reject(self):
        self.assertEqual(self.outcome(), "NOT_COMPLETE")

    def test_complete_record_contract(self):
        self.assertEqual(self.outcome(), "RECORD_COMPLETE")

    def test_empty_template_is_not_complete(self):
        template = Path(__file__).resolve().parents[1] / "assets/quality-record.template.json"
        self.assertEqual(evaluate(json.loads(template.read_text(encoding="utf-8")), self.root)["result"], "NOT_COMPLETE")

    def test_changed_model_rejects_stale_review(self):
        (self.root / "model.json").write_text('{"changed":true}', encoding="utf-8")
        self.reject()

    def test_changed_evidence_is_rejected(self):
        (self.root / "report.txt").write_text("different", encoding="utf-8")
        self.reject()

    def test_evidence_for_other_model_is_rejected(self):
        self.record["evidence"]["photo"]["target_sha256"] = "0" * 64
        self.reject()

    def test_missing_evidence_is_rejected(self):
        self.record["dimensions"]["roof"]["evidence"] = ["nonexistent"]
        self.reject()

    def test_failed_structure_cannot_be_offset_by_100_points(self):
        self.record["gates"]["supports"]["status"] = "fail"
        self.reject()

    def test_unverified_gate_is_not_passed(self):
        self.record["gates"]["routes"]["status"] = "unverified"
        self.reject()

    def test_weak_back_cannot_be_averaged(self):
        self.record["elevations"]["back"]["score"] = 2
        self.reject()

    def test_elevation_aggregate_must_use_weakest_side(self):
        self.record["elevations"]["back"]["score"] = 3
        self.reject()

    def test_high_total_cannot_hide_one_dimension_at_two(self):
        self.record["dimensions"]["site"]["score"] = 2
        self.reject()

    def test_all_three_is_only_75(self):
        for v in self.record["dimensions"].values():
            v["score"] = 3
        self.reject()

    def test_85_boundary_is_accepted(self):
        for key, value in self.record["dimensions"].items():
            value["score"] = 4 if key in ("silhouette", "roof", "site") else 3
        self.assertEqual(evaluate(self.record, self.root)["weighted_score"], 85)
        self.assertEqual(self.outcome(), "RECORD_COMPLETE")

    def test_unobserved_score_is_not_an_exclusion(self):
        self.record["dimensions"]["roof"]["score"] = None
        self.reject()

    def test_boolean_is_not_an_integer_score(self):
        self.record["dimensions"]["roof"]["score"] = True
        self.reject()

    def test_na_requires_scope_reason(self):
        self.record["dimensions"]["interior"] = {"applicable": False, "score": None}
        self.reject()

    def test_required_gate_cannot_be_excluded(self):
        self.record["gates"]["preservation"].update(status="not_applicable", scope_reason="skip")
        self.reject()

    def test_unverified_back_view_blocks_completion(self):
        self.record["views"]["back"]["status"] = "unverified"
        self.reject()

    def test_critical_silhouette_mismatch_blocks_completion(self):
        self.record["critical_features"][0]["status"] = "fail"
        self.reject()

    def test_in_game_claim_needs_evidence(self):
        self.record["verification_level"] = "in_game"
        self.reject()

    def test_plan_is_not_in_game_evidence(self):
        self.record["verification_level"] = "in_game"
        self.record["in_game_review"] = {"evidence": ["plan"], "environment": {"minecraft_version": "test", "runtime": "test"},
                                         "actions": ["fixture action"], "observations": "fixture observations"}
        self.reject()

    def test_game_record_needs_actual_action_fields(self):
        self.record["verification_level"] = "in_game"
        self.record["evidence"]["report"]["kind"] = "review"
        self.record["in_game_review"] = {"evidence": ["report"]}
        self.reject()

    def test_open_issue_blocks_completion(self):
        self.record["blocking_issues"] = ["roof gap"]
        self.reject()

    def test_roofless_bridge_can_exclude_absent_features(self):
        for key in ("roof", "facade", "interior"):
            self.record["dimensions"][key] = {"applicable": False, "score": None, "scope_reason": "Open roofless bridge per saved plan"}
        for key in ("envelope", "interior", "fixtures"):
            self.record["gates"][key] = {"status": "not_applicable", "reason": "No enclosed room, roof, or door", "scope_reason": "Open bridge per saved plan"}
        self.record["views"].pop("roof")
        self.record["views"].pop("interior")
        self.assertEqual(self.outcome(), "RECORD_COMPLETE")

    def test_targeted_repair_does_not_require_whole_building_score(self):
        self.record["scope"].update(mode="targeted_edit", description="Repair one bridge arch", reviewed_elevations=[])
        for key in WEIGHTS.keys() - {"interfaces", "material_details"}:
            self.record["dimensions"][key] = {"applicable": False, "score": None, "scope_reason": "Unaffected part outside declared local repair"}
        self.record["views"] = {key: {"status": "reviewed", "evidence": ["photo"]} for key in ("detail", "context")}
        self.record["elevations"] = {}
        self.record["dimensions"]["interfaces"]["score"] = 3
        self.record["dimensions"]["material_details"]["score"] = 3
        self.assertEqual(self.outcome(), "RECORD_COMPLETE")


if __name__ == "__main__":
    unittest.main()
