package io.github.maaasu.astralRecord.feature.mob.skill;

import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MobSkillRegistryTest {

    /**
     * Mob スキル ID は専用 registry 内で一意であり、player スキルの登録と混在しない。
     */
    @Test
    void rejectsDuplicateMobSkillIds() {
        MobSkillRegistry registry = new MobSkillRegistry();
        registry.register(testExecutor());

        assertThrows(IllegalArgumentException.class, () -> registry.register(testExecutor()));
    }

    /**
     * Mob master の timing 指定は executor の既定値を項目単位で上書きする。
     */
    @Test
    void appliesOnlySpecifiedTimingOverrides() {
        MobSkillTiming timing = testExecutor().resolveTiming(
                new MobSkillBinding("mob_test", null, 40L, null, Map.of())
        );

        assertEquals(8.0D, timing.activationRange());
        assertEquals(40L, timing.cooldownTicks());
        assertEquals(6L, timing.castTimeTicks());
    }

    private static MobSkillExecutor testExecutor() {
        return new MobSkillExecutor() {
            @Override public String id() { return "mob_test"; }
            @Override public String displayName() { return "テスト"; }
            @Override public MobSkillTiming defaultTiming() { return new MobSkillTiming(8.0D, 20L, 6L); }
            @Override public boolean cast(MobSkillContext context) { return true; }
        };
    }
}
