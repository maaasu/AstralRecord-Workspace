package io.github.maaasu.astralRecord.feature.skill.executor.active;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.archmage.ArchmageCelestialCircleExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.archmage.ArchmageCelestialCircleRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer.AdventurerSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.HunterSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.MageSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.paladin.PaladinSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.paladin.PaladinHolyFieldRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.paladin.PaladinHolySmiteRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.paladin.PaladinGuardianProtectRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter.SharpshooterSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.WizardMeteorExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.WizardEmulateSparkExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.WizardSelfHealExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.WizardElementalPrismExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.WizardElementalBallExecutor;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
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
     * @param archmageCelestialCircleRuntimeService セレスティアルサークル実行時状態サービス
     * @param paladinHolyFieldRuntimeService ホーリーフィールド実行時状態サービス
     * @param paladinHolySmiteRuntimeService ホーリースマイト聖柱実行時状態サービス
     * @param statusService 一時シールドを管理するステータスサービス
     * @param partyService パーティーメンバーを解決するサービス
     * @param paladinGuardianProtectRuntimeService ガーディアンプロテクトの肩代わり状態サービス
     * @param playerDeathService custom死亡状態サービス
     * @return 実装済みのexecutor一覧
     */
    public static @NotNull List<SkillExecutor> create(
            @NotNull ActiveSkillServices services,
            @NotNull ArchmageCelestialCircleRuntimeService archmageCelestialCircleRuntimeService,
            @NotNull PaladinHolyFieldRuntimeService paladinHolyFieldRuntimeService,
            @NotNull PaladinHolySmiteRuntimeService paladinHolySmiteRuntimeService,
            @NotNull StatusService statusService,
            @NotNull PartyService partyService,
            @NotNull PaladinGuardianProtectRuntimeService paladinGuardianProtectRuntimeService,
            @NotNull PlayerDeathService playerDeathService
    ) {
        List<SkillExecutor> executors = new ArrayList<>(41);
        executors.addAll(AdventurerSkillExecutorCatalog.create(services));
        executors.addAll(HunterSkillExecutorCatalog.create(services));
        executors.addAll(SharpshooterSkillExecutorCatalog.create(services));
        executors.addAll(MageSkillExecutorCatalog.create(services));
        executors.add(new ArchmageCelestialCircleExecutor(services, archmageCelestialCircleRuntimeService));
        executors.add(new WizardMeteorExecutor(services));
        executors.add(new WizardEmulateSparkExecutor(services));
        executors.add(new WizardSelfHealExecutor(services));
        executors.add(new WizardElementalPrismExecutor(services));
        executors.add(new WizardElementalBallExecutor(services));
        executors.addAll(PaladinSkillExecutorCatalog.create(
                services, paladinHolyFieldRuntimeService, paladinHolySmiteRuntimeService, statusService, partyService,
                paladinGuardianProtectRuntimeService, playerDeathService));
        executors.addAll(SwordsmanSkillExecutorCatalog.create(services));
        return List.copyOf(executors);
    }
}
