package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MobSkillRegistryTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-Mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 2. 追加手順
     * 検証契約: Mob スキル ID は専用 registry 内で一意であり、player スキルの登録と混在しない。
     */
    @Test
    void rejectsDuplicateMobSkillIds() {
        MobSkillRegistry registry = new MobSkillRegistry();
        registry.register(testExecutor());

        assertThrows(IllegalArgumentException.class, () -> registry.register(testExecutor()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-Mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 3. 実行時の契約
     * 検証契約: Mob master の timing 指定は executor の既定値を項目単位で上書きする。
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
