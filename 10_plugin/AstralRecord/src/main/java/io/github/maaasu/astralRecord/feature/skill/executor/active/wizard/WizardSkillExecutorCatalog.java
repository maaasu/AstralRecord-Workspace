package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** 既存魔法と元素連携・アーケイン・プリズム運用の能動魔法を列挙します。 */
public final class WizardSkillExecutorCatalog {
    private WizardSkillExecutorCatalog() { }

    /**
     * ウィザードの全能動魔法の実行処理を作成します。
     * @param services 対象選択・戦闘・演出・飛翔体などの共有サービス
     * @param statusService 実MP確認と固定MP回復の正本
     * @return 既存五魔法と追加九魔法の実行処理。受動三技能は個別登録する
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services,
                                                     @NotNull StatusService statusService) {
        return List.of(new WizardMeteorExecutor(services), new WizardEmulateSparkExecutor(services),
                new WizardSelfHealExecutor(services), new WizardElementalPrismExecutor(services),
                new WizardElementalBallExecutor(services),
                new WizardCinderLanceExecutor(services), new WizardRimeNovaExecutor(services),
                new WizardStormChainExecutor(services), new WizardTriadConvergenceExecutor(services),
                new WizardArcaneLanceExecutor(services), new WizardArcaneOverloadExecutor(services, statusService),
                new WizardManaSiphonExecutor(services, statusService), new WizardPrismaticDartExecutor(services),
                new WizardManaVeilExecutor(services));
    }
}
