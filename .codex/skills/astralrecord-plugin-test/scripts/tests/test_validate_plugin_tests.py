from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "validate_plugin_tests.py"
SPEC = importlib.util.spec_from_file_location("validate_plugin_tests", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
VALIDATOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VALIDATOR
SPEC.loader.exec_module(VALIDATOR)


class PluginTestValidatorTest(unittest.TestCase):
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

    def _validate(self):
        return VALIDATOR.validate_repository(self.repo_root, [self.test_root])

    def test_accepts_java_tests_without_documentation(self) -> None:
        self._test_source(
            "SampleServiceTest.java",
            """
            class SampleServiceTest {
                @Test
                void returnsLimit() {}

                @ParameterizedTest
                @ValueSource(ints = {1, 2})
                void returnsLimitForEachInput(int value) {}
            }
            """,
        )

        issues, method_count, file_count = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(2, method_count)
        self.assertEqual(1, file_count)

    def test_accepts_kotlin_repeated_and_template_tests(self) -> None:
        self._test_source(
            "KotlinCalculationTests.kt",
            """
            class KotlinCalculationTests {
                @RepeatedTest(2)
                fun `returns limit repeatedly`() {}

                @org.junit.jupiter.api.TestTemplate
                fun usesTemplate() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(2, method_count)

    def test_accepts_test_factory(self) -> None:
        self._test_source(
            "CalculationMatrixTest.java",
            """
            class CalculationMatrixTest {
                @TestFactory
                Stream<DynamicTest> boundaryCases() { return Stream.empty(); }
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(1, method_count)

    def test_accepts_test_in_annotation_stack(self) -> None:
        self._test_source(
            "AnnotatedMethodTest.java",
            """
            class AnnotatedMethodTest {
                @DisplayName("boundary (value)")
                @Tag("boundary")
                @Timeout(value = 1, unit = TimeUnit.SECONDS)
                @Test
                void returnsLimit() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(1, method_count)

    def test_ignores_plain_support_sources_without_test_annotations(self) -> None:
        self._test_source(
            "support/TestFixture.java",
            """
            class TestFixture {
                String text = "@Test";
                // @Test
                /* @TestFactory */
                /** @RepeatedTest */
            }
            """,
        )

        issues, method_count, file_count = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(0, method_count)
        self.assertEqual(1, file_count)

    def test_rejects_mockbukkit_source_even_without_test_annotations(self) -> None:
        self._test_source(
            "support/MockBukkitTestBase.java",
            """
            import org.mockbukkit.mockbukkit.MockBukkit;

            class MockBukkitTestBase {
                void start() { MockBukkit.mock(); }
            }
            """,
        )

        issues, method_count, file_count = self._validate()

        self.assertIn(
            "MOCK_SERVER_SOURCE_REMAINS",
            [issue.code for issue in issues],
        )
        self.assertEqual(0, method_count)
        self.assertEqual(1, file_count)

    def test_rejects_legacy_mockbukkit_source_using_server_mock(self) -> None:
        self._test_source(
            "support/LegacyServerFixture.java",
            """
            import be.seeseemelk.mockbukkit.ServerMock;

            class LegacyServerFixture {
                private ServerMock server;
            }
            """,
        )

        issues, method_count, file_count = self._validate()

        self.assertIn(
            "MOCK_SERVER_SOURCE_REMAINS",
            [issue.code for issue in issues],
        )
        self.assertEqual(0, method_count)
        self.assertEqual(1, file_count)

    def test_allows_custom_player_mock_without_pseudo_server_package(self) -> None:
        self._test_source(
            "support/PlayerMock.java",
            """
            class PlayerMock {
                String name() { return "test-player"; }
            }
            """,
        )

        issues, method_count, file_count = self._validate()

        self.assertNotIn(
            "MOCK_SERVER_SOURCE_REMAINS",
            [issue.code for issue in issues],
        )
        self.assertEqual(0, method_count)
        self.assertEqual(1, file_count)

    def test_rejects_disabled_ad_hoc_and_surefire_excluded_tests(self) -> None:
        self._test_source(
            "DisabledSampleTest.java",
            """
            @Disabled
            class DisabledSampleTest {
                @Test
                void skipped() {}
            }
            """,
        )
        self._test_source(
            "AdHocProbeTest.java",
            """
            class AdHocProbeTest {
                @Test
                void probe() {}
            }
            """,
        )
        self._test_source(
            "InvisibleSpecification.java",
            """
            class InvisibleSpecification {
                @Test
                void neverDiscoveredByDefault() {}
            }
            """,
        )
        self._test_source(
            "VisibleFilenameTest.java",
            """
            class InvisibleType {
                @Test
                void classIsNotDiscoveredByDefault() {}
            }
            """,
        )
        self._test_source(
            "PermanentContainerTest.java",
            """
            class PermanentContainerTest {}
            class NestedProbeOneShotTest {
                @Test
                void hiddenOneShotProbe() {}
            }
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

    def test_rejects_ad_hoc_variants_for_files_outer_and_nested_types(self) -> None:
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
                    @Test
                    void probe() {{}}
                }}
                """,
            )

        outer_types = "\n".join(
            f"""
            class Outer{type_name} {{
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
        self._test_source(
            "AliasedAnnotationTest.kt",
            """
            import org.junit.jupiter.api.Test as Spec

            class AliasedAnnotationTest {
                @Spec
                fun hiddenFromLiteralAnnotationScan() {}
            }
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
            """
            class ConditionalSkipTest {
                @Test
                @DisabledOnOs(OS.WINDOWS)
                void disabledOnOs() {}

                @Test
                @EnabledOnJre(JRE.JAVA_21)
                void enabledOnJre() {}

                @Test
                @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
                void disabledByEnvironment() {}

                @Test
                @EnabledIfSystemProperty(named = "profile", matches = "unit")
                void enabledBySystemProperty() {}
            }
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
        self._test_source(
            "BacktickQualifiedAnnotationTest.kt",
            """
            class BacktickQualifiedAnnotationTest {
                @org.junit.jupiter.api.`Test`
                fun directlyQualifiedTest() {}

                @org.junit.jupiter.api.condition.`DisabledOnOs`
                @org.junit.jupiter.api.Test
                fun directlyQualifiedConditionalSkip() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual(2, method_count)
        self.assertEqual(["DISABLED_TEST"], [issue.code for issue in issues])

    def test_rejects_mockbukkit_dependency_in_pom(self) -> None:
        self._write(
            "10_plugin/AstralRecord/pom.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>sample</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.mockbukkit.mockbukkit</groupId>
                  <artifactId>mockbukkit-v1.21</artifactId>
                  <version>4.110.0</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """,
        )

        issues, _, _ = self._validate()

        self.assertIn(
            "POM_MOCK_SERVER_DEPENDENCY",
            [issue.code for issue in issues],
        )

    def test_rejects_other_pseudo_server_dependency_marker_in_pom(self) -> None:
        self._write(
            "10_plugin/AstralRecord/pom.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>sample</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>example.test</groupId>
                  <artifactId>paper-test-server</artifactId>
                  <version>1.0.0</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """,
        )

        issues, _, _ = self._validate()

        self.assertIn(
            "POM_MOCK_SERVER_DEPENDENCY",
            [issue.code for issue in issues],
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
                            <exclude>**/*SampleTest.*</exclude>
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
            "FilteredSampleTest.java",
            """
            class FilteredSampleTest {
                @Test
                void wouldBeFilteredByPom() {}
            }
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
                          <test>**/*SampleTest</test>
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

    def test_accepts_java_newline_qualified_and_unicode_escaped_tests(self) -> None:
        self._test_source(
            "JavaLexicalAnnotationTest.java",
            """
            class JavaLexicalAnnotationTest {
                @org.junit.jupiter.api.
                    Test
                void newlineQualifiedTest() {}

                @T\\u0065st
                void unicodeEscapedTest() {}

                @
                    org.junit.jupiter.api.Test
                void whitespaceAfterAtTest() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(3, method_count)

    def test_detects_java_newline_and_unicode_test_and_skip_annotations(self) -> None:
        self._test_source(
            "JavaAnnotationBypassTest.java",
            """
            class JavaAnnotationBypassTest {
                @org.junit.jupiter.api.
                    Test
                void newlineQualifiedTest() {}

                @T\\u0065st
                void unicodeEscapedTest() {}

                // \\u000a @Test
                void testIntroducedAfterEscapedLineTerminator() {}

                @org.junit.jupiter.api.condition.
                    DisabledOnOs
                void newlineQualifiedSkip() {}

                @Dis\\u0061bled
                void unicodeEscapedSkip() {}

                @
                    Disabled
                void whitespaceAfterAtSkip() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()
        codes = [issue.code for issue in issues]

        self.assertEqual(3, method_count)
        self.assertEqual(["DISABLED_TEST"] * 3, codes)

    def test_translates_java_unicode_escapes_before_comment_scanning(self) -> None:
        self._test_source(
            "EscapedCommentTest.java",
            """
            class EscapedCommentTest {
                \\u002f\\u002f @Test
                void plainHelper() {}
            }
            """,
        )

        issues, method_count, _ = self._validate()

        self.assertEqual([], issues)
        self.assertEqual(0, method_count)

    def test_rejects_nonstandard_project_and_profile_test_source_directories(self) -> None:
        self._write(
            "10_plugin/AstralRecord/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <build>
                <testSourceDirectory>src/no-tests</testSourceDirectory>
              </build>
              <profiles>
                <profile>
                  <id>alternate-tests</id>
                  <build>
                    <testSourceDirectory>${custom.test.source}</testSourceDirectory>
                  </build>
                </profile>
              </profiles>
            </project>
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual(
            ["POM_TEST_SOURCE_DIRECTORY", "POM_TEST_SOURCE_DIRECTORY"],
            [issue.code for issue in issues],
        )

    def test_accepts_default_test_source_and_unrelated_compiler_settings(self) -> None:
        self._write(
            "10_plugin/AstralRecord/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <build>
                <testSourceDirectory>./src/test/java/</testSourceDirectory>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <includes><include>**/*.java</include></includes>
                      <compilerArgs><arg>-AtestIncludes=metadata</arg></compilerArgs>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
              <profiles>
                <profile>
                  <id>default-tests</id>
                  <build>
                    <testSourceDirectory>${project.basedir}/src/test/java</testSourceDirectory>
                  </build>
                </profile>
              </profiles>
            </project>
            """,
        )

        issues, _, _ = self._validate()

        self.assertEqual([], issues)

    def test_rejects_compiler_test_filters_in_direct_and_execution_configurations(self) -> None:
        cases = (
            ("testIncludes", "direct"),
            ("testExcludes", "direct"),
            ("testIncludes", "execution"),
            ("testExcludes", "execution"),
        )
        for setting_name, placement in cases:
            with self.subTest(setting=setting_name, placement=placement):
                setting = (
                    f"<{setting_name}><testInclude>**/*SampleTest.java</testInclude>"
                    f"</{setting_name}>"
                )
                if placement == "direct":
                    configuration = f"<configuration>{setting}</configuration>"
                else:
                    configuration = (
                        "<executions><execution><configuration>"
                        f"{setting}"
                        "</configuration></execution></executions>"
                    )
                self._write(
                    "10_plugin/AstralRecord/pom.xml",
                    f"""
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <build><plugins><plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        {configuration}
                      </plugin></plugins></build>
                    </project>
                    """,
                )

                issues, _, _ = self._validate()

                self.assertEqual(
                    ["POM_COMPILER_TEST_FILTER"],
                    [issue.code for issue in issues],
                )


if __name__ == "__main__":
    unittest.main()
