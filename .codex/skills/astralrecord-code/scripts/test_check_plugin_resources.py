#!/usr/bin/env python3
"""Regression tests for the plugin resource guardrail patterns."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("check_plugin_resources.py")
SPEC = importlib.util.spec_from_file_location("check_plugin_resources", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class PluginResourceCheckerPatternTest(unittest.TestCase):
    def test_direct_logger_variants_are_rejected(self) -> None:
        samples = (
            (CHECKER.DIRECT_PLATFORM_LOG_PATTERN, 'getLogger()\n    .warning("message")'),
            (CHECKER.DIRECT_PLATFORM_LOG_PATTERN, 'plugin.getLogger().info("message")'),
            (CHECKER.DIRECT_LOGGER_VARIABLE_PATTERN, 'logger.error("message")'),
            (CHECKER.DIRECT_LOGGER_VARIABLE_PATTERN, 'LOGGER.warn("message")'),
            (CHECKER.DIRECT_LOGGER_WITHOUT_ID_PATTERN, 'Logger.warn("message")'),
            (CHECKER.DIRECT_LOGGER_WITHOUT_ID_PATTERN, 'Logger.info(message)'),
            (CHECKER.DIRECT_STD_STREAM_PATTERN, 'System.err.println("message")'),
        )
        for pattern, source in samples:
            with self.subTest(source=source):
                self.assertIsNotNone(pattern.search(source))

        self.assertIsNone(
            CHECKER.DIRECT_LOGGER_WITHOUT_ID_PATTERN.search(
                'Logger.error(\n    LogId.E_5000, exception, context)'
            )
        )

    def test_new_human_log_arguments_are_rejected_but_structured_values_are_allowed(self) -> None:
        human = 'Logger.log(\n    LogId.E_6400, "npc", path, "directory creation failed"\n);'
        self.assertEqual(
            ["direct log text argument: Example.java:1"],
            CHECKER.report_inline_log_text(Path("Example.java"), human, {3}),
        )
        structured = 'Logger.log(LogId.E_6201, "cancel_snapshot:" + sessionId);'
        self.assertEqual(
            [],
            CHECKER.report_inline_log_text(Path("Example.java"), structured, {1}),
        )
        self.assertEqual(
            [],
            CHECKER.report_inline_log_text(Path("Example.java"), human, {10}),
        )

    def test_inline_command_and_raw_messages_are_rejected(self) -> None:
        self.assertIsNotNone(CHECKER.DIRECT_COMMAND_MESSAGE_LITERAL_PATTERN.search(
            'sendError(sender, String.format("failed: %s", reason));'
        ))
        self.assertIsNotNone(CHECKER.DIRECT_RAW_MESSAGE_LITERAL_PATTERN.search(
            'messageService.sendRaw(player, "failed");'
        ))

    def test_changed_log_arguments_match_property_placeholders(self) -> None:
        templates = {
            "E_5600": "メニューイベント処理に失敗しました: %s",
            "E_5601": "GUI イベント処理に失敗しました: player=%s, operation=%s",
            "W_6604": "補償を実行しました: accountId=%s, questId=%s",
        }
        mismatch = (
            'Logger.log(LogId.E_5600, player.getName(), "mail_gui_close");'
        )
        self.assertEqual(
            ["log placeholder mismatch: Example.java:1 E_5600 expects 1, got 2"],
            CHECKER.report_log_placeholder_mismatches(
                Path("Example.java"), mismatch, {1}, templates
            ),
        )
        valid = (
            'Logger.log(LogId.E_5601, player.getName(), "mail_gui_close");\n'
            'Logger.log(LogId.W_6604, exception, accountId, questId);\n'
            'Logger.error(LogId.E_5155, e, e.message ?: e.javaClass.simpleName)'
        )
        self.assertEqual(
            [],
            CHECKER.report_log_placeholder_mismatches(
                Path("Example.java"), valid, {1, 2, 3}, {
                    **templates,
                    "E_5155": "アカウント進行の更新に失敗しました: %s",
                }
            ),
        )

    def test_property_backed_messages_are_allowed(self) -> None:
        source = (
            'sendInfo(sender, PlayerMsgResource.format('
            'PlayerMsgId.P_5000.getId(), String.join(", ", values)));'
        )
        self.assertIsNone(CHECKER.DIRECT_COMMAND_MESSAGE_LITERAL_PATTERN.search(source))

    def test_java_properties_key_syntaxes_are_recognized(self) -> None:
        source = " P_5000: colon\n\tP_5001 = equals\nP_5002 whitespace\n"
        self.assertEqual(
            ["P_5000", "P_5001", "P_5002"],
            CHECKER.PROPERTY_PATTERN.findall(source),
        )

    def test_id_name_and_constructor_value_are_captured_separately(self) -> None:
        self.assertEqual(
            [("P_5050", "5050", "5051")],
            CHECKER.ID_VALUE_PATTERN.findall("    P_5050(5051),"),
        )


if __name__ == "__main__":
    unittest.main()
