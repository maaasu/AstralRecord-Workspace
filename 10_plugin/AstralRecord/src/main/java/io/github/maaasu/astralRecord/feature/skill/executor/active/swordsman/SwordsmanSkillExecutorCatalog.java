package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** ソードマン用の発動スキルexecutorを列挙します。 */
public final class SwordsmanSkillExecutorCatalog {

    private SwordsmanSkillExecutorCatalog() {
    }

    /**
     * 実装済みのソードマン用executorを生成します。
     *
     * @param services 共通発動サービス
     * @return ソードマン用executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(
            new SwordsmanShieldDrainExecutor(services)
        );
    }
}
