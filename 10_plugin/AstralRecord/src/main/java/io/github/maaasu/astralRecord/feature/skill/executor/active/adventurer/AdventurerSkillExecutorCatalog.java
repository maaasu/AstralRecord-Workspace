package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 冒険者用の発動スキル executor を列挙します。 */
public final class AdventurerSkillExecutorCatalog {

    private AdventurerSkillExecutorCatalog() {
    }

    /**
     * 冒険者用 executor をスキル表示順で生成します。
     *
     * @param services 共有発動スキルサービス
     * @return 2個の executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(
                new AdventurerAstralEdgeExecutor(services),
                new AdventurerSmashExecutor(services)
        );
    }
}
