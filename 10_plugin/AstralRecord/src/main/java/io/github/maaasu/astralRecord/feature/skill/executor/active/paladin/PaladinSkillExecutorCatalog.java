package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
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
     * @param holyFieldRuntimeService ホーリーフィールド実行時状態サービス
     * @param holySmiteRuntimeService ホーリースマイト聖柱実行時状態サービス
     * @param statusService 一時シールドを管理するステータスサービス
     * @param partyService パーティーメンバーを解決するサービス
     * @param guardianProtectRuntimeService ガーディアンプロテクトの肩代わり状態サービス
     * @param playerDeathService custom死亡状態サービス
     * @return 10個の executor
     */
    public static @NotNull List<SkillExecutor> create(
            @NotNull ActiveSkillServices services,
            @NotNull PaladinHolyFieldRuntimeService holyFieldRuntimeService,
            @NotNull PaladinHolySmiteRuntimeService holySmiteRuntimeService,
            @NotNull StatusService statusService,
            @NotNull PartyService partyService,
            @NotNull PaladinGuardianProtectRuntimeService guardianProtectRuntimeService,
            @NotNull PlayerDeathService playerDeathService
    ) {
        return List.of(
                new PaladinHolySmiteExecutor(services, holySmiteRuntimeService),
                new PaladinHolyControlExecutor(services, holySmiteRuntimeService, statusService, partyService),
                new PaladinHolyFieldExecutor(services, holyFieldRuntimeService),
                new PaladinHolySmashExecutor(services, holyFieldRuntimeService),
                new PaladinShieldExecutor(services, statusService, partyService),
                new PaladinShieldBashExecutor(services),
                new PaladinShieldImpactExecutor(services),
                new PaladinFortressExecutor(services),
                new PaladinGuardianProtectExecutor(
                        services, guardianProtectRuntimeService, partyService, playerDeathService
                ),
                new PaladinGuardianChainExecutor(services)
        );
    }
}
