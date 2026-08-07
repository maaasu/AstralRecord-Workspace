package io.github.maaasu.astralRecord.feature.skill.executor.active;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer.AdventurerSkillExecutorCatalog;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** プレイヤー用の全発動スキル executor を職業横断で列挙します。 */
public final class ActiveSkillExecutorCatalog {

    private ActiveSkillExecutorCatalog() {
    }

    /**
     * 実装済みの冒険者用 executor を生成します。
     *
     * @param services 共有発動スキルサービス
     * @return 2個の executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        List<SkillExecutor> executors = new ArrayList<>(2);
        executors.addAll(AdventurerSkillExecutorCatalog.create(services));
        return List.copyOf(executors);
    }
}
