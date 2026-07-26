package io.github.maaasu.astralRecord.feature.skill.active;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.ActiveSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ActiveSkillExecutorDesignTest {

    private static final Path EXECUTOR_SOURCE_ROOT = Path.of(
        "src", "main", "java", "io", "github", "maaasu", "astralRecord",
        "feature", "skill", "executor", "active"
    );

    private static final Set<String> EXPECTED_SKILL_IDS = Set.of(
        "swordsman_blade_wave",
        "swordsman_crescent_slash",
        "swordsman_earthbreaker",
        "swordsman_fortress_guard",
        "swordsman_piercing_thrust",
        "swordsman_vanguard_rush",
        "swordsman_war_cry",
        "swordsman_whirlwind",
        "hunter_arrow_rain",
        "hunter_backstep_shot",
        "hunter_fan_shot",
        "hunter_piercing_arrow",
        "hunter_power_shot",
        "hunter_rapid_fire",
        "hunter_ricochet",
        "hunter_snare_trap",
        "mage_arcane_lance",
        "mage_blink",
        "mage_chain_lightning",
        "mage_elemental_storm",
        "mage_fireball",
        "mage_frost_nova",
        "mage_mana_barrier",
        "mage_meteor"
    );

    @Test
    void hasExactlyTwentyFourConcreteExecutorSources() throws IOException {
        assertEquals(24, EXPECTED_SKILL_IDS.size());
        assertEquals(EXPECTED_SKILL_IDS.size(), concreteExecutorSources().size());
    }

    @Test
    void catalogContainsEveryDesignedSkillIdExactlyOnce() {
        List<SkillExecutor> executors = ActiveSkillExecutorCatalog.create(activeSkillServices());
        Set<String> implementationIds = executors.stream()
            .map(SkillExecutor::implementationId)
            .collect(Collectors.toSet());

        assertEquals(EXPECTED_SKILL_IDS.size(), executors.size());
        assertEquals(executors.size(), implementationIds.size(), "implementation IDs must be unique");
        assertEquals(EXPECTED_SKILL_IDS, implementationIds);
        assertTrue(executors.stream().allMatch(PlayerActiveSkillExecutor.class::isInstance));
    }

    @Test
    void concreteExecutorsUseTheSharedBaseAndDoNotReadFreeFormParams() throws IOException {
        List<Path> sources = concreteExecutorSources();
        assertEquals(24, sources.size());

        for (Path sourcePath : sources) {
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            assertTrue(
                source.contains("extends PlayerActiveSkillExecutor"),
                () -> sourcePath.getFileName() + " must use PlayerActiveSkillExecutor"
            );
            assertFalse(
                source.matches("(?s).*\\bgetParams\\s*\\(.*"),
                () -> sourcePath.getFileName() + " must not read SkillDefinition.params"
            );
            assertFalse(
                source.matches("(?s).*\\.params\\s*\\(.*"),
                () -> sourcePath.getFileName() + " must not read params through a record accessor"
            );
        }
    }

    private static List<Path> concreteExecutorSources() throws IOException {
        assertTrue(Files.isDirectory(EXECUTOR_SOURCE_ROOT), "active executor source directory must exist");
        try (Stream<Path> paths = Files.walk(EXECUTOR_SOURCE_ROOT)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith("Executor.java"))
                .filter(path -> !path.getFileName().toString().equals("PlayerActiveSkillExecutor.java"))
                .sorted()
                .toList();
        }
    }

    private static ActiveSkillServices activeSkillServices() {
        return new ActiveSkillServices(
            mock(SkillTargetingService.class),
            mock(SkillCombatService.class),
            mock(SkillEffectService.class),
            mock(SkillProjectileService.class),
            mock(SkillMovementService.class),
            mock(TemporarySkillEffectService.class),
            mock(SkillTaskService.class)
        );
    }

}
