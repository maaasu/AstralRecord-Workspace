package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** パラディン用の発動スキル executor を列挙します。 */
public final class PaladinSkillExecutorCatalog {

    private PaladinSkillExecutorCatalog() {
    }

    /**
     * パラディン用 executor をスキル表示順で生成します。
     *
     * @param services 共有発動スキルサービス
     * @return 1個の executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(new PaladinHolySmiteExecutor(services));
    }
}
