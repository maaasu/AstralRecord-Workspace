package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** ソードマン用の発動スキル executor を列挙します。 */
public final class SwordsmanSkillExecutorCatalog {

    private SwordsmanSkillExecutorCatalog() {
    }

    /**
     * ソードマン用 executor をスキル表示順で生成します。
     *
     * @param services 共有発動スキルサービス
     * @return 8個の executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(
                new SwordsmanCrescentSlashExecutor(services),
                new SwordsmanPiercingThrustExecutor(services),
                new SwordsmanWhirlwindExecutor(services),
                new SwordsmanVanguardRushExecutor(services),
                new SwordsmanBladeWaveExecutor(services),
                new SwordsmanFortressGuardExecutor(services),
                new SwordsmanWarCryExecutor(services),
                new SwordsmanEarthbreakerExecutor(services)
        );
    }
}
