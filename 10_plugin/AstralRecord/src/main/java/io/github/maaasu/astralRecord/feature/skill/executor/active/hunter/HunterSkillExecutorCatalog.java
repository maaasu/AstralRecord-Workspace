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
     * ハンター用 executor をスキル表示順で生成します。
     *
     * @param services 共有発動スキルサービス
     * @return 8個の executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(
                new HunterPowerShotExecutor(services),
                new HunterPiercingArrowExecutor(services),
                new HunterFanShotExecutor(services),
                new HunterRapidFireExecutor(services),
                new HunterBackstepShotExecutor(services),
                new HunterSnareTrapExecutor(services),
                new HunterArrowRainExecutor(services),
                new HunterRicochetExecutor(services)
        );
    }
}
