package io.github.maaasu.astralRecord.feature.skill.executor.active;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer.AdventurerSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.HunterSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.MageSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanSkillExecutorCatalog;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** プレイヤー用の全発動スキル executor を職業横断で列挙します。 */
public final class ActiveSkillExecutorCatalog {

    private ActiveSkillExecutorCatalog() {
    }

    /**
     * 実装済みのプレイヤー用 executor を職業横断で生成します。
     *
     * @param services 共有発動スキルサービス
     * @return 17個の executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        List<SkillExecutor> executors = new ArrayList<>(17);
        executors.addAll(AdventurerSkillExecutorCatalog.create(services));
        executors.addAll(HunterSkillExecutorCatalog.create(services));
        executors.addAll(MageSkillExecutorCatalog.create(services));
        executors.addAll(SwordsmanSkillExecutorCatalog.create(services));
        return List.copyOf(executors);
    }
}
