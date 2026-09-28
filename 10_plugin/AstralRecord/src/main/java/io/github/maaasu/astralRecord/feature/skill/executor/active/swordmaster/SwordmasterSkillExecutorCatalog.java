package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.service.SeijakuIssenSkillRuntimeService;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** ソードマスターの固有発動スキルを登録します。 */
public final class SwordmasterSkillExecutorCatalog {
    private SwordmasterSkillExecutorCatalog() { }

    /**
     * 固有スキルの実行処理を列挙します。
     * @param services 発動スキル共有サービス
     * @param counter 静寂一閃の反撃成功状態
     * @return 全12種の固有発動スキル
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services,
                                                     @NotNull SeijakuIssenSkillRuntimeService counter) {
        return List.of(
                new SwordmasterGaleReaperExecutor(services),
                new SwordmasterCrimsonDriveExecutor(services),
                new SwordmasterBladeTempestExecutor(services),
                new SwordmasterStarCleaveExecutor(services),
                new SwordmasterCrescentDuetExecutor(services),
                new SwordmasterHeartseekerExecutor(services),
                new SwordmasterThunderLineExecutor(services),
                new SwordmasterFrostBloomExecutor(services),
                new SwordmasterFlameLotusExecutor(services),
                new SwordmasterTrinityEdgeExecutor(services),
                new SwordmasterRiposteExecutor(services, counter),
                new SwordmasterSereneMendExecutor(services, counter)
        );
    }
}
