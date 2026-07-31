from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "validate_test_traceability.py"
SPEC = importlib.util.spec_from_file_location("validate_test_traceability", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
VALIDATOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VALIDATOR
SPEC.loader.exec_module(VALIDATOR)


class TraceabilityValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repo_root = Path(self.temporary_directory.name)
        self.test_root = (
            self.repo_root / "10_plugin" / "AstralRecord" / "src" / "test"
        )
        self.test_root.mkdir(parents=True)
        self._write(
            "10_plugin/AstralRecord/pom.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>sample</artifactId>
              <version>1.0.0</version>
            </project>
            """,
        )
        self.design_path = (
            "00_docs/10_Plugin設計書/feature/01-sample/"
            "3-メソッド仕様/01_3-サービス.md"
        )
        self._write(
            self.design_path,
            """
            # 01_3-サービス

            ## 1. SampleService メソッド仕様

            ### 値の計算

            入力値が境界に達した場合は上限値を返す。
            """,
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _write(self, relative_path: str, content: str) -> Path:
        path = self.repo_root.joinpath(*Path(relative_path).parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
        return path

    def _test_source(self, filename: str, content: str) -> Path:
        path = self.test_root / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
        return path

    def _javadoc(self, contract: str = "入力値が境界に達した場合は上限値を返す。") -> str:
        return textwrap.dedent(
            f"""
            /**
             * 設計入力: {self.design_path}
             * 章・見出し: # 01_3-サービス > ## 1. SampleService メソッド仕様 > ### 値の計算
             * 検証契約: {contract}
             */
            """
        ).strip()

    def _validate(self):
        return VALIDATOR.validate_repository(self.repo_root, [self.test_root])

    def test_accepts_java_test_and_parameterized_test(self) -> None:
        doc = self._javadoc()
        self._test_source(
            "SampleServiceTest.java",
            f"""
            class SampleServiceTest {{
                {doc}
                @Test
                void returnsLimit() {{}}

                {doc}
                @ParameterizedTest
                @ValueSource(ints = {{1, 2}})
                void returnsLimitForEachInput(int value) {{}}
            }}
            """,
        )

        issues, method_count, file_count = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(2, method_count)
        self.assertEqual(1, file_count)

    def test_accepts_kotlin_repeated_and_template_tests(self) -> None:
        doc = self._javadoc()
        self._test_source(
            "KotlinCalculationTests.kt",
            f"""
            class KotlinCalculationTests {{
                {doc}
                @RepeatedTest(2)
                fun `returns limit repeatedly`() {{}}

                {doc}
                @org.junit.jupiter.api.TestTemplate
                fun usesTemplate() {{}}
            }}
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(2, method_count)

    def test_accepts_test_factory_with_one_contract(self) -> None:
        self._test_source(
            "CalculationMatrixTest.java",
            f"""
            class CalculationMatrixTest {{
                {self._javadoc("すべての境界入力について同じ上限制約を適用する。")}
                @TestFactory
                Stream<DynamicTest> boundaryCases() {{ return Stream.empty(); }}
            }}
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(1, method_count)

    def test_accepts_continuous_method_annotation_stack(self) -> None:
        self._test_source(
            "AnnotatedMethodTest.java",
            f"""
            class AnnotatedMethodTest {{
                {self._javadoc()}
                @DisplayName("boundary (value)")
                @Tag("design")
                @Timeout(value = 1, unit = TimeUnit.SECONDS)
                @Test
                void returnsLimit() {{}}
            }}
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(1, method_count)

    def test_rejects_unrelated_declaration_between_javadoc_and_test(self) -> None:
        self._test_source(
            "InterruptedMethodTest.java",
            f"""
            class InterruptedMethodTest {{
                {self._javadoc()}
                @Deprecated
                void unrelatedHelper() {{}}

                @Test
                void returnsLimit() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual(["JAVADOC_MISSING"], [issue.code for issue in issues])

    def test_accepts_multiple_paired_design_inputs(self) -> None:
        second_design_path = (
            "00_docs/10_Plugin設計書/feature/02-linked/"
            "3-メソッド仕様/02_3-サービス.md"
        )
        self._write(
            second_design_path,
            """
            # 02_3-サービス

            ## 1. LinkedService メソッド仕様

            ### 結果の連携

            上限値を後続処理へ一度だけ連携する。
            """,
        )
        self._test_source(
            "LinkedContractTest.java",
            f"""
            class LinkedContractTest {{
                /**
                 * 設計入力: {self.design_path}
                 * 章・見出し: # 01_3-サービス > ## 1. SampleService メソッド仕様 > ### 値の計算
                 * 設計入力: {second_design_path}
                 * 章・見出し: # 02_3-サービス > ## 1. LinkedService メソッド仕様 > ### 結果の連携
                 * 検証契約: 算出した上限値を後続処理へ一度だけ連携する。
                 */
                @Test
                void calculatesAndForwardsLimit() {{}}
            }}
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(1, method_count)

    def test_ignores_support_sources_without_test_annotations(self) -> None:
        self._test_source(
            "support/MockBukkitTestBase.java",
            """
            class MockBukkitTestBase {
                String text = "@Test";
                // @Test
                /* @TestFactory */
            }
            """,
        )

        issues, method_count, file_count = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(0, method_count)
        self.assertEqual(1, file_count)

    def test_rejects_missing_fields_invalid_paths_and_unknown_headings(self) -> None:
        self._test_source(
            "BrokenTraceabilityTest.java",
            f"""
            class BrokenTraceabilityTest {{
                @Test
                void hasNoJavadoc() {{}}

                /**
                 * 設計入力: C:/workspace/00_docs/spec.md
                 * 章・見出し: # Spec > ## Rule
                 * 検証契約: 絶対パスを入力として使用してしまう。
                 */
                @Test
                void usesAbsolutePath() {{}}

                /**
                 * 設計入力: {self.design_path}
                 * 章・見出し: # 01_3-サービス > ## 9. 存在しない節
                 * 検証契約: 存在しない見出しを参照してしまう。
                 */
                @Test
                void usesUnknownHeading() {{}}

                /**
                 * 設計入力: {self.design_path}
                 * 章・見出し: # 01_3-サービス > ## 1. SampleService メソッド仕様 > ### 値の計算
                 */
                @Test
                void omitsContract() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()
        codes = {issue.code for issue in issues}

        self.assertTrue(
            {"JAVADOC_MISSING", "DESIGN_PATH_FORMAT", "HEADING_NOT_FOUND", "JAVADOC_FIELD"}
            <= codes
        )

    def test_rejects_non_adopted_review_and_todo_references(self) -> None:
        planned_path = (
            "00_docs/10_Plugin設計書/feature/01-sample/01_8-実装予定.md"
        )
        pending_path = (
            "00_docs/10_Plugin設計書/feature/01-sample/01_9-未決事項.md"
        )
        review_path = "00_docs/10_Plugin設計書/review/sample-review.md"
        self._write(planned_path, "# 01_8-実装予定\n\n## 1. 将来仕様\n")
        self._write(pending_path, "# 01_9-未決事項\n\n## 1. 判断待ち\n")
        self._write(review_path, "# sample-review\n\n## 1. 指摘\n")
        self._test_source(
            "NonAdoptedDesignTest.java",
            f"""
            class NonAdoptedDesignTest {{
                /**
                 * 設計入力: {planned_path}
                 * 章・見出し: # 01_8-実装予定 > ## 1. 将来仕様
                 * 検証契約: 未実装の内容を現在の恒久仕様として固定してしまう。
                 */
                @Test
                void citesPlannedDesign() {{}}

                /**
                 * 設計入力: {pending_path}
                 * 章・見出し: # 01_9-未決事項 > ## 1. 判断待ち
                 * 検証契約: 判断待ちの内容を恒久仕様として固定してしまう。
                 */
                @Test
                void citesPendingDesign() {{}}

                /**
                 * 設計入力: {review_path}
                 * 章・見出し: # sample-review > ## 1. 指摘
                 * 検証契約: レビュー記録を恒久仕様として固定してしまう。
                 */
                @Test
                void citesReview() {{}}

                {self._javadoc("TODO 境界値の期待結果を後で決める。")}
                @Test
                void leavesTodo() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()
        codes = [issue.code for issue in issues]

        self.assertEqual(3, codes.count("NON_ADOPTED_DESIGN"))
        self.assertIn("TODO_VALUE", codes)

    def test_rejects_disabled_ad_hoc_and_surefire_excluded_tests(self) -> None:
        doc = self._javadoc()
        self._test_source(
            "DisabledSampleTest.java",
            f"""
            @Disabled
            class DisabledSampleTest {{
                {doc}
                @Test
                void skipped() {{}}
            }}
            """,
        )
        self._test_source(
            "AdHocProbeTest.java",
            f"""
            class AdHocProbeTest {{
                {doc}
                @Test
                void probe() {{}}
            }}
            """,
        )
        self._test_source(
            "InvisibleSpecification.java",
            f"""
            class InvisibleSpecification {{
                {doc}
                @Test
                void neverDiscoveredByDefault() {{}}
            }}
            """,
        )
        self._test_source(
            "VisibleFilenameTest.java",
            f"""
            class InvisibleType {{
                {doc}
                @Test
                void classIsNotDiscoveredByDefault() {{}}
            }}
            """,
        )
        self._test_source(
            "PermanentContainerTest.java",
            f"""
            class PermanentContainerTest {{}}
            class NestedProbeOneShotTest {{
                {doc}
                @Test
                void hiddenOneShotProbe() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()
        codes = {issue.code for issue in issues}

        self.assertTrue(
            {
                "DISABLED_TEST",
                "AD_HOC_TEST_REMAINS",
                "SUREFIRE_NAME_MISMATCH",
                "SUREFIRE_TYPE_MISMATCH",
            }
            <= codes
        )

    def test_reports_missing_design_file_and_generic_contract(self) -> None:
        missing_path = (
            "00_docs/10_Plugin設計書/feature/01-sample/01_3-存在しない.md"
        )
        self._test_source(
            "MissingDesignTest.java",
            f"""
            class MissingDesignTest {{
                /**
                 * 設計入力: {missing_path}
                 * 章・見出し: # 01_3-存在しない > ## 1. 仕様
                 * 検証契約: 正しく動作する。
                 */
                @Test
                void missingDesign() {{}}

                {self._javadoc("正しく動作する。")}
                @Test
                void genericContract() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()
        codes = {issue.code for issue in issues}

        self.assertIn("DESIGN_PATH_MISSING", codes)
        self.assertIn("CONTRACT_NOT_CONCRETE", codes)

    def test_rejects_long_template_contract_without_concrete_expectation(self) -> None:
        self._test_source(
            "TemplateContractTest.java",
            f"""
            class TemplateContractTest {{
                {self._javadoc("テスト名が示す入力について、入力条件・実行結果・禁止される副作用を設計節の記載どおりに固定する。")}
                @Test
                void usesTemplateContract() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual(
            ["CONTRACT_NOT_CONCRETE"],
            [issue.code for issue in issues],
        )

    def test_rejects_ad_hoc_variants_for_files_outer_and_nested_types(self) -> None:
        doc = self._javadoc()
        file_type_names = (
            "AdHocProbeTests",
            "AdHocProbeTestCase",
            "ProbeOneShotTests",
            "ProbeOneShotTestCase",
        )
        for type_name in file_type_names:
            self._test_source(
                f"{type_name}.java",
                f"""
                class {type_name} {{
                    {doc}
                    @Test
                    void probe() {{}}
                }}
                """,
            )

        outer_type_names = (
            "AdHocOuterTests",
            "AdHocOuterTestCase",
            "OuterOneShotTests",
            "OuterOneShotTestCase",
        )
        outer_types = "\n".join(
            f"""
            class {type_name} {{
                {doc}
                @Test
                void probe() {{}}
            }}
            """
            for type_name in outer_type_names
        )
        self._test_source("VisibleOuterTest.java", outer_types)

        nested_type_names = (
            "AdHocNestedTests",
            "AdHocNestedTestCase",
            "NestedOneShotTests",
            "NestedOneShotTestCase",
        )
        nested_types = "\n".join(
            f"static class {type_name} {{}}" for type_name in nested_type_names
        )
        self._test_source(
            "PermanentContainerTest.java",
            f"""
            class PermanentContainerTest {{
                {nested_types}
            }}
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual(
            12,
            [issue.code for issue in issues].count("AD_HOC_TEST_REMAINS"),
        )

    def test_rejects_repeated_surefire_suffixes_for_files_outer_and_nested_types(self) -> None:
        doc = self._javadoc()
        repeated_names = (
            "ProbeOneShotTestTests",
            "ProbeOneShotTestsTest",
            "ProbeOneShotTestCaseTest",
            "TestProbeOneShotTestTests",
        )
        for type_name in repeated_names:
            self._test_source(
                f"{type_name}.java",
                f"""
                class {type_name} {{
                    {doc}
                    @Test
                    void probe() {{}}
                }}
                """,
            )

        outer_types = "\n".join(
            f"""
            class Outer{type_name} {{
                {doc}
                @Test
                void probe() {{}}
            }}
            """
            for type_name in repeated_names
        )
        self._test_source("VisibleOuterTest.java", outer_types)

        nested_types = "\n".join(
            f"static class Nested{type_name} {{}}" for type_name in repeated_names
        )
        self._test_source(
            "PermanentContainerTest.java",
            f"""
            class PermanentContainerTest {{
                {nested_types}
            }}
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual(
            12,
            [issue.code for issue in issues].count("AD_HOC_TEST_REMAINS"),
        )

    def test_rejects_kotlin_junit_alias_and_conditional_skip_annotations(self) -> None:
        doc = self._javadoc()
        self._test_source(
            "AliasedAnnotationTest.kt",
            f"""
            import org.junit.jupiter.api.Test as Spec

            class AliasedAnnotationTest {{
                {doc}
                @Spec
                fun hiddenFromLiteralAnnotationScan() {{}}
            }}
            """,
        )
        self._test_source(
            "BacktickAliasedAnnotationTest.kt",
            """
            import org.junit.jupiter.api.Test as `Spec Test`
            import org.junit.jupiter.api.condition.DisabledOnOs as `Conditional Spec`

            class BacktickAliasedAnnotationTest {
                @`Spec Test`
                fun hiddenFromBacktickAnnotationScan() {}

                @`Conditional Spec`
                fun hiddenConditionalSkip() {}
            }
            """,
        )
        self._test_source(
            "ConditionalSkipTest.java",
            f"""
            class ConditionalSkipTest {{
                {doc}
                @Test
                @DisabledOnOs(OS.WINDOWS)
                void disabledOnOs() {{}}

                {doc}
                @Test
                @EnabledOnJre(JRE.JAVA_21)
                void enabledOnJre() {{}}

                {doc}
                @Test
                @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
                void disabledByEnvironment() {{}}

                {doc}
                @Test
                @EnabledIfSystemProperty(named = "profile", matches = "unit")
                void enabledBySystemProperty() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()
        codes = [issue.code for issue in issues]

        self.assertEqual(3, codes.count("JUNIT_ANNOTATION_ALIAS"))
        self.assertEqual(4, codes.count("DISABLED_TEST"))

    def test_rejects_unicode_junit_import_aliases_without_parsing_alias_kind(self) -> None:
        self._test_source(
            "UnicodeAliasedAnnotationTest.kt",
            """
            import org.junit.jupiter.api.Test as 試験
            import org.junit.jupiter.api.condition.DisabledOnOs as 条件;

            class UnicodeAliasedAnnotationTest {
                @試験
                fun hiddenUnicodeTest() {}

                @条件
                fun hiddenUnicodeSkip() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual(0, method_count)
        self.assertEqual(
            2,
            [issue.code for issue in issues].count("JUNIT_ANNOTATION_ALIAS"),
        )

    def test_rejects_kotlin_junit_typealiases_including_backtick_alias(self) -> None:
        self._test_source(
            "TypeAliasedAnnotationTest.kt",
            """
            import org.junit.jupiter.api.Test
            import org.junit.jupiter.api.condition.DisabledOnOs

            typealias Spec = Test
            typealias `Conditional Spec` = DisabledOnOs
            typealias FullyQualifiedSpec = org.junit.jupiter.api.TestFactory
            typealias BacktickTargetSpec = `Test`

            class TypeAliasedAnnotationTest {
                @Spec
                fun hiddenTest() {}

                @`Conditional Spec`
                fun hiddenSkip() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual(0, method_count)
        self.assertEqual(
            4,
            [issue.code for issue in issues].count("JUNIT_ANNOTATION_TYPEALIAS"),
        )

    def test_rejects_unicode_semicolon_and_backtick_junit_typealiases(self) -> None:
        self._test_source(
            "UnicodeTypeAliasedAnnotationTest.kt",
            """
            typealias 試験 = org.junit.jupiter.api.Test;
            typealias `工場 試験` = org.junit.jupiter.api.`TestFactory`;
            typealias 条件 = org.junit.jupiter.api.condition.DisabledOnOs;
            typealias `条件 別名` = org.junit.jupiter.api.condition.`EnabledOnJre`;

            class UnicodeTypeAliasedAnnotationTest {
                @試験
                fun hiddenUnicodeTest() {}

                @条件
                fun hiddenUnicodeSkip() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual(0, method_count)
        self.assertEqual(
            4,
            [issue.code for issue in issues].count("JUNIT_ANNOTATION_TYPEALIAS"),
        )

    def test_detects_backtick_fully_qualified_test_and_skip_annotations(self) -> None:
        doc = self._javadoc()
        self._test_source(
            "BacktickQualifiedAnnotationTest.kt",
            f"""
            class BacktickQualifiedAnnotationTest {{
                {doc}
                @org.junit.jupiter.api.`Test`
                fun directlyQualifiedTest() {{}}

                {doc}
                @org.junit.jupiter.api.condition.`DisabledOnOs`
                @org.junit.jupiter.api.Test
                fun directlyQualifiedConditionalSkip() {{}}
            }}
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual(2, method_count)
        self.assertEqual(["DISABLED_TEST"], [issue.code for issue in issues])

    def test_rejects_non_adjacent_design_heading_pairs(self) -> None:
        second_design_path = (
            "00_docs/10_Plugin設計書/feature/02-linked/"
            "3-メソッド仕様/02_3-サービス.md"
        )
        self._write(
            second_design_path,
            """
            # 02_3-サービス

            ## 1. LinkedService メソッド仕様

            ### 結果の連携

            上限値を後続処理へ一度だけ連携する。
            """,
        )
        self._test_source(
            "NonAdjacentPairTest.java",
            f"""
            class NonAdjacentPairTest {{
                /**
                 * 設計入力: {self.design_path}
                 * 設計入力: {second_design_path}
                 * 章・見出し: # 01_3-サービス > ## 1. SampleService メソッド仕様 > ### 値の計算
                 * 章・見出し: # 02_3-サービス > ## 1. LinkedService メソッド仕様 > ### 結果の連携
                 * 検証契約: 算出した上限値を後続処理へ一度だけ連携する。
                 */
                @Test
                void separatesDesignPathsFromTheirHeadings() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()

        self.assertTrue(issues)
        self.assertEqual({"JAVADOC_FIELD"}, {issue.code for issue in issues})

    def test_rejects_prose_between_design_path_and_heading(self) -> None:
        self._test_source(
            "InterruptedPairTest.java",
            f"""
            class InterruptedPairTest {{
                /**
                 * 設計入力: {self.design_path}
                 * この説明行によりpathとheadingの物理的な対応が途切れる。
                 * 章・見出し: # 01_3-サービス > ## 1. SampleService メソッド仕様 > ### 値の計算
                 * 検証契約: 入力値が境界に達した場合は上限値を返す。
                 */
                @Test
                void interruptsThePairWithProse() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual(["JAVADOC_FIELD"], [issue.code for issue in issues])

    def test_rejects_todo_body_and_non_adopted_heading(self) -> None:
        unresolved_design_path = (
            "00_docs/10_Plugin設計書/feature/01-sample/"
            "3-メソッド仕様/01_3-未確定サービス.md"
        )
        self._write(
            unresolved_design_path,
            """
            # 01_3-未確定サービス

            ## 1. 境界値

            TODO: 上限値を決定する。

            ## 2. 未決事項

            例外時の値は判断待ちとする。
            """,
        )
        self._test_source(
            "UnresolvedDesignTest.java",
            f"""
            class UnresolvedDesignTest {{
                /**
                 * 設計入力: {unresolved_design_path}
                 * 章・見出し: # 01_3-未確定サービス > ## 1. 境界値
                 * 検証契約: 境界入力で上限値を返す。
                 */
                @Test
                void citesTodoBody() {{}}

                /**
                 * 設計入力: {unresolved_design_path}
                 * 章・見出し: # 01_3-未確定サービス > ## 2. 未決事項
                 * 検証契約: 例外入力で既定値を返す。
                 */
                @Test
                void citesPendingHeading() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()
        codes = {issue.code for issue in issues}

        self.assertEqual(
            {
                "DESIGN_BODY_TODO",
                "DESIGN_BODY_UNRESOLVED",
                "NON_ADOPTED_DESIGN_SECTION",
            },
            codes,
        )

    def test_rejects_unresolved_ancestors_descendants_and_section_body(self) -> None:
        unresolved_design_path = (
            "00_docs/10_Plugin設計書/feature/01-sample/"
            "3-メソッド仕様/01_3-判断待ちサービス.md"
        )
        self._write(
            unresolved_design_path,
            """
            # 01_3-サービス

            ## 1. 未決事項

            ### 一見確定した契約

            入力値が境界に達した場合は上限値を返す。

            ## 2. 採用済み契約

            この値の例外時処理は要検討とする。

            ## 3. 親契約

            現在値を返す。

            ### 判断待ちの子契約

            将来値を返す。
            """,
        )
        self._test_source(
            "UnresolvedHierarchyTest.java",
            f"""
            class UnresolvedHierarchyTest {{
                /**
                 * 設計入力: {unresolved_design_path}
                 * 章・見出し: # 01_3-サービス > ## 1. 未決事項 > ### 一見確定した契約
                 * 検証契約: 境界入力で上限値を返す。
                 */
                @Test
                void citesUnresolvedAncestor() {{}}

                /**
                 * 設計入力: {unresolved_design_path}
                 * 章・見出し: # 01_3-サービス > ## 2. 採用済み契約
                 * 検証契約: 例外入力で既定値を返す。
                 */
                @Test
                void citesUnresolvedBody() {{}}

                /**
                 * 設計入力: {unresolved_design_path}
                 * 章・見出し: # 01_3-サービス > ## 3. 親契約
                 * 検証契約: 通常入力で現在値を返す。
                 */
                @Test
                void citesUnresolvedDescendant() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()
        codes = [issue.code for issue in issues]

        self.assertEqual(2, codes.count("NON_ADOPTED_DESIGN_SECTION"))
        self.assertEqual(2, codes.count("DESIGN_BODY_UNRESOLVED"))

    def test_rejects_generic_and_unresolved_meta_contracts(self) -> None:
        self._test_source(
            "MetaContractTest.java",
            f"""
            class MetaContractTest {{
                {self._javadoc("処理結果が期待どおりになることを確認する。")}
                @Test
                void usesParaphrasedGenericContract() {{}}

                {self._javadoc("現設計は具体式をsource正本としている。")}
                @Test
                void treatsSourceAsAuthority() {{}}

                {self._javadoc("境界値の期待結果は設計書へ明記要。")}
                @Test
                void leavesDocumentationWorkPending() {{}}

                {self._javadoc("対象の挙動が正しいことを確認する。")}
                @Test
                void checksCorrectBehavior() {{}}

                {self._javadoc("処理内容が適切であることを検証する。")}
                @Test
                void verifiesAppropriateProcessing() {{}}

                {self._javadoc("機能の動作に問題がないことを確かめる。")}
                @Test
                void checksFeatureOperation() {{}}

                {self._javadoc("空入力の場合はnullを返し、cacheを更新しない。")}
                @Test
                void keepsConcreteConditionAndResult() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual(
            6,
            [issue.code for issue in issues].count("CONTRACT_NOT_CONCRETE"),
        )

    def test_rejects_named_subject_generic_predicates_only(self) -> None:
        self._test_source(
            "NamedSubjectContractTest.java",
            f"""
            class NamedSubjectContractTest {{
                {self._javadoc("SampleServiceの動作を確認する。")}
                @Test
                void namesAServiceOnly() {{}}

                {self._javadoc("CurrencyFeatureの挙動を検証する。")}
                @Test
                void namesAFeatureOnly() {{}}

                {self._javadoc("クエストサービスの処理を確認する。")}
                @Test
                void namesAJapaneseServiceOnly() {{}}

                {self._javadoc("SampleServiceへ空入力を渡した場合はnullを返し、cacheを更新しない。")}
                @Test
                void keepsConcreteConditionAndResult() {{}}

                {self._javadoc("異常入力では例外を送出する動作を確認する。")}
                @Test
                void keepsConcreteResultBeforeBehaviorNoun() {{}}

                {self._javadoc("SampleServiceの処理は空入力の場合にnullを返す。")}
                @Test
                void keepsConcreteResultAfterNamedSubject() {{}}
            }}
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual(6, method_count)
        self.assertEqual(
            3,
            [issue.code for issue in issues].count("CONTRACT_NOT_CONCRETE"),
        )

    def test_distinguishes_normalized_generic_predicates_from_concrete_contracts(self) -> None:
        generic_contracts = (
            "SampleServiceの動作確認を行う。",
            "InventoryFeatureの挙動検証を実施する。",
            "WorldControllerの処理確認をする。",
            "クエストサービスの動作確認を実施する。",
            "プレイヤー設定機能の挙動検証を行う。",
            "ActionRingServiceが正しく動くことを確かめる。",
            "MobServiceの正常動作を確認する。",
            "ステータス計算処理を検証する。",
            "Feature12Serviceの動作確認を行う。",
            "キャッシュサービスの処理を確認する。",
        )
        concrete_contracts = (
            "SampleServiceへ空入力を渡した場合はnullを返し、cacheを更新しない。",
            "再試行回数が3回に達した場合は例外を送出する動作を確認する。",
            "失敗時は通知を送信せず、既存cacheを変更しない挙動を検証する。",
            "上限超過時は値を64へ丸める処理を確認する。",
            "IDが登録済みの場合は既存値を保持する動作確認を行う。",
            "空入力では0件の結果を返す挙動検証を実施する。",
        )
        methods = "\n".join(
            f"""
            {self._javadoc(contract)}
            @Test
            void rejectsGenericContract{index}() {{}}
            """
            for index, contract in enumerate(generic_contracts)
        )
        methods += "\n" + "\n".join(
            f"""
            {self._javadoc(contract)}
            @Test
            void acceptsConcreteContract{index}() {{}}
            """
            for index, contract in enumerate(concrete_contracts)
        )
        self._test_source(
            "GenericPredicateCorpusTest.java",
            f"""
            class GenericPredicateCorpusTest {{
                {methods}
            }}
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual(len(generic_contracts) + len(concrete_contracts), method_count)
        self.assertEqual(
            len(generic_contracts),
            [issue.code for issue in issues].count("CONTRACT_NOT_CONCRETE"),
        )
        self.assertEqual(
            {"CONTRACT_NOT_CONCRETE"},
            {issue.code for issue in issues},
        )

    def test_rejects_surefire_filters_in_namespaced_profile_configuration(self) -> None:
        self._write(
            "10_plugin/AstralRecord/pom.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>sample</artifactId>
              <version>1.0.0</version>
              <profiles>
                <profile>
                  <id>filtered-tests</id>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                          <excludes>
                            <exclude>**/*DesignTest.*</exclude>
                          </excludes>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </profile>
              </profiles>
            </project>
            """,
        )
        self._test_source(
            "FilteredDesignTest.java",
            f"""
            class FilteredDesignTest {{
                {self._javadoc()}
                @Test
                void wouldBeFilteredByPom() {{}}
            }}
            """,
        )

        issues, _, _ = self._validate()

        self.assertIn("POM_SUREFIRE_FILTER", {issue.code for issue in issues})

    def test_accepts_surefire_system_and_provider_property_maps(self) -> None:
        self._write(
            "10_plugin/AstralRecord/pom.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>sample</artifactId>
              <version>1.0.0</version>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                      <systemPropertyVariables>
                        <test>integration</test>
                        <groups>domain-a</groups>
                        <skipTests>false</skipTests>
                      </systemPropertyVariables>
                      <properties>
                        <test>provider-value</test>
                        <groups>provider-group</groups>
                      </properties>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual([], issues)

    def test_rejects_surefire_filter_in_execution_configuration(self) -> None:
        self._write(
            "10_plugin/AstralRecord/pom.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>sample</artifactId>
              <version>1.0.0</version>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <executions>
                      <execution>
                        <id>filtered-tests</id>
                        <configuration>
                          <test>**/*DesignTest</test>
                        </configuration>
                      </execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
            </project>
            """,
        )

        issues, _, _ = self._validate()

        self.assertIn("POM_SUREFIRE_FILTER", {issue.code for issue in issues})


if __name__ == "__main__":
    unittest.main()
