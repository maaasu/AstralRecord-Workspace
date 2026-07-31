package io.github.maaasu.astralRecord.shared.effect;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleDisplayArchitectureTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main");
    private static final String DISPLAY_SERVICE_FILE = "ParticleDisplayService.java";
    private static final String DEFINITIONS_FILE = "SharedParticleDefinitions.java";
    private static final Pattern DIRECT_PARTICLE_SPAWN = Pattern.compile("\\.spawnParticle\\s*\\(");
    private static final Pattern DIRECT_PARTICLE_RESOLVER = Pattern.compile("\\bParticle\\.valueOf\\s*\\(");
    private static final Pattern FIXED_PARTICLE_CONSTANT = Pattern.compile("\\bParticle\\.[A-Z][A-Z0-9_]*\\b");

    /** feature 実装が共通の表示・解決・固定定義を使用していることを確認します。 */
    @Test
    void featureSourcesUseSharedParticleInfrastructure() throws IOException {
        List<String> directSpawnOffenders = new ArrayList<>();
        List<String> directResolverOffenders = new ArrayList<>();
        List<String> fixedParticleOffenders = new ArrayList<>();

        for (Path source : sourceFiles()) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            String fileName = source.getFileName().toString();
            if (!DISPLAY_SERVICE_FILE.equals(fileName) && DIRECT_PARTICLE_SPAWN.matcher(content).find()) {
                directSpawnOffenders.add(source.toString());
            }
            if (DIRECT_PARTICLE_RESOLVER.matcher(content).find()) {
                directResolverOffenders.add(source.toString());
            }
            if (!DEFINITIONS_FILE.equals(fileName) && FIXED_PARTICLE_CONSTANT.matcher(content).find()) {
                fixedParticleOffenders.add(source.toString());
            }
        }

        assertTrue(directSpawnOffenders.isEmpty(), () -> "ParticleDisplayService を経由していない表示: " + directSpawnOffenders);
        assertTrue(directResolverOffenders.isEmpty(), () -> "共通 resolver を経由していない解決: " + directResolverOffenders);
        assertTrue(fixedParticleOffenders.isEmpty(), () -> "SharedParticleDefinitions 外の固定 Particle: " + fixedParticleOffenders);
    }

    /**
     * 監査対象となる Java / Kotlin の main source 一覧を返します。
     *
     * @return パス順に並べた main source 一覧
     * @throws IOException source tree の走査に失敗した場合
     */
    private static List<Path> sourceFiles() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCE_ROOT), "main source directory must exist");
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".kt"))
                .sorted()
                .toList();
        }
    }
}
