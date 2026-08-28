package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** ハンター用の発動スキル executor を列挙します。 */
public final class HunterSkillExecutorCatalog {

    private HunterSkillExecutorCatalog() {
    }

    /**
     * 実装済みのハンター用 executor を生成します。
     *
     * @param services 共通発動サービス
     * @return ハンター用 executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(
                new HunterFadeShotExecutor(services),
                new HunterArrowRainExecutor(services),
                new HunterCrashArrowExecutor(services),
                new HunterHealArrowExecutor(services)
        );
    }
}
