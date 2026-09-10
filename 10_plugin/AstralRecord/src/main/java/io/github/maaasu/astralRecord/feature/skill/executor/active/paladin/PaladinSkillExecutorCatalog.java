package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** パラディンのツリー解放スキルを列挙します。 */
public final class PaladinSkillExecutorCatalog {
    private PaladinSkillExecutorCatalog() { }

    /**
     * 実装済みスキルを共通サービスへ接続します。
     * @param services 発動スキル共通サービス
     * @return パラディン用executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(new PaladinAnathemaExecutor(services),
                new PaladinLastJudgmentExecutor(services),
                new PaladinVesperAegisExecutor(services),
                new PaladinCovenantExecutor(services),
                new PaladinRequiemExecutor(services),
                new PaladinBlackSanctumExecutor(services));
    }
}
