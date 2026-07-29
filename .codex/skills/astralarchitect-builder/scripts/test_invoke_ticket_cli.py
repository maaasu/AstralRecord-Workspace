"""Unit tests for the AstralArchitect safe CLI wrapper."""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import invoke_ticket_cli as wrapper


class InvokeTicketCliTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.plugin = self.root / "plugins" / "AstralArchitect"
        self.ticket = self.plugin / "tickets" / "20260730-test-ticket"
        self.ticket.mkdir(parents=True)
        (self.ticket / "ticket.json").write_text(
            json.dumps({"id": self.ticket.name, "state": "CREATED"}), encoding="utf-8"
        )
        (self.ticket / "source.schem").write_bytes(b"source")
        (self.ticket / "candidate.schem").write_bytes(b"candidate")
        self.tool = self.plugin / "tools" / "ticket_cli.py"
        self.tool.parent.mkdir(parents=True)
        self.tool.write_text("raise SystemExit('untrusted tool must not run')\n", encoding="utf-8")

    def arguments(self, command: str = "info") -> list[str]:
        return ["--ticket", str(self.ticket), "--", command]

    def test_builds_expected_shell_free_argument_vector(self) -> None:
        command = wrapper.build_invocation(self.arguments("diff"))
        self.assertEqual(str(wrapper.TRUSTED_TOOL_PATH.resolve()), command[1])
        self.assertNotEqual(str(self.tool), command[1])
        self.assertEqual(["diff", str(self.ticket)], command[2:])

    def test_inserts_ticket_after_command_before_forwarded_arguments(self) -> None:
        operations = self.root / "operations.json"
        command = wrapper.build_invocation(
            self.arguments("apply-ops") + ["--ops", str(operations)]
        )
        self.assertEqual(
            ["apply-ops", str(self.ticket), "--ops", str(operations)], command[2:]
        )

    def test_rejects_relative_ticket_path(self) -> None:
        with self.assertRaises(wrapper.SafetyError):
            wrapper.build_invocation(["--ticket", "tickets/example", "--", "info"])

    def test_rejects_parent_reference_in_ticket_path(self) -> None:
        traversal = str(self.ticket.parent / ".." / "trash" / self.ticket.name)
        with self.assertRaisesRegex(wrapper.SafetyError, "must not contain"):
            wrapper.build_invocation(["--ticket", traversal, "--", "info"])

    def test_rejects_ticket_outside_tickets_directory(self) -> None:
        other = self.plugin / "active" / self.ticket.name
        other.parent.mkdir(parents=True)
        self.ticket.rename(other)
        with self.assertRaisesRegex(wrapper.SafetyError, "immediate child"):
            wrapper.build_invocation(["--ticket", str(other), "--", "info"])

    def test_rejects_ticket_under_non_astralarchitect_plugin_root(self) -> None:
        fake_plugin = self.root / "plugins" / "OtherPlugin"
        fake_ticket = fake_plugin / "tickets" / self.ticket.name
        fake_ticket.parent.mkdir(parents=True)
        self.ticket.rename(fake_ticket)
        with self.assertRaisesRegex(wrapper.SafetyError, "AstralArchitect"):
            wrapper.build_invocation(["--ticket", str(fake_ticket), "--", "info"])

    def test_rejects_trash_ticket(self) -> None:
        trash = self.plugin / "trash" / self.ticket.name
        trash.parent.mkdir(parents=True)
        self.ticket.rename(trash)
        with self.assertRaisesRegex(wrapper.SafetyError, "trash"):
            wrapper.build_invocation(["--ticket", str(trash), "--", "info"])

    def test_rejects_metadata_id_mismatch(self) -> None:
        (self.ticket / "ticket.json").write_text(
            json.dumps({"id": "20260730-other-ticket", "state": "CREATED"}),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(wrapper.SafetyError, "does not match"):
            wrapper.build_invocation(self.arguments())

    def test_rejects_world_mutation_commands(self) -> None:
        for command in ("apply", "rollback", "delete", "restore"):
            with self.subTest(command=command), self.assertRaises(wrapper.SafetyError):
                wrapper.build_invocation(self.arguments(command))

    def test_rejects_ticket_override_in_forwarded_arguments(self) -> None:
        with self.assertRaisesRegex(wrapper.SafetyError, "override"):
            wrapper.build_invocation(
                self.arguments("info") + ["--ticket", str(self.plugin / "trash")]
            )

    def test_rejects_candidate_edit_in_applied_state(self) -> None:
        (self.ticket / "ticket.json").write_text(
            json.dumps({"id": self.ticket.name, "state": "APPLIED"}), encoding="utf-8"
        )
        with self.assertRaisesRegex(wrapper.SafetyError, "APPLIED"):
            wrapper.build_invocation(self.arguments("apply-ops") + ["ops.json"])

    def test_rejects_oversized_ticket_metadata(self) -> None:
        (self.ticket / "ticket.json").write_bytes(b" " * (1024 * 1024 + 1))
        with self.assertRaisesRegex(wrapper.SafetyError, "1 MiB"):
            wrapper.build_invocation(self.arguments())

    def test_main_invokes_subprocess_without_shell(self) -> None:
        completed = type("Completed", (), {"returncode": 7})()
        with patch.object(wrapper.subprocess, "run", return_value=completed) as run:
            result = wrapper.main(self.arguments("info"))
        self.assertEqual(7, result)
        _, keyword_arguments = run.call_args
        self.assertEqual({"shell": False, "check": False}, keyword_arguments)


if __name__ == "__main__":
    unittest.main()
