package io.github.maaasu.astralRecord.feature.skill.executor.active;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster.SwordmasterSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.service.SeijakuIssenSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.archmage.ArchmageSkillExecutorCatalog;
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
import io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher.PhantomArcherSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.WizardSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skill.service.BindCircleRuntimeService;
import io.github.maaasu.astralRecord.shared.effect.InvulnerabilityVisualService;
import org.bukkit.plugin.Plugin;
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
     * @param bossChallengeService ボスの死亡回数管理サービス
     * @param dungeonService ダンジョンの死亡回数管理サービス
     * @param invulnerabilityVisualService 復活後の無敵表示サービス
     * @param skillService 回復成立時のクールタイム解除サービス
     * @param bindCircleRuntimeService バインドサークル拘束状態
     * @param seijakuIssenSkillRuntimeService 静寂一閃の剣気を連携へ渡すサービス
     * @param plugin 設置中の円の独立 task を登録するプラグイン
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
            @NotNull PlayerDeathService playerDeathService,
            @NotNull BossChallengeService bossChallengeService,
            @NotNull DungeonService dungeonService,
            @NotNull InvulnerabilityVisualService invulnerabilityVisualService,
            @NotNull SkillService skillService,
            @NotNull BindCircleRuntimeService bindCircleRuntimeService,
            @NotNull SeijakuIssenSkillRuntimeService seijakuIssenSkillRuntimeService,
            @NotNull Plugin plugin
    ) {
        List<SkillExecutor> executors = new ArrayList<>();
        executors.addAll(AdventurerSkillExecutorCatalog.create(services));
        executors.addAll(HunterSkillExecutorCatalog.create(services));
        executors.addAll(SharpshooterSkillExecutorCatalog.create(services));
        executors.addAll(PhantomArcherSkillExecutorCatalog.create(services));
        executors.addAll(MageSkillExecutorCatalog.create(services));
        executors.addAll(ArchmageSkillExecutorCatalog.create(services, archmageCelestialCircleRuntimeService,
                skillService, playerDeathService, bossChallengeService, dungeonService,
                invulnerabilityVisualService, statusService, bindCircleRuntimeService, plugin));
        executors.addAll(WizardSkillExecutorCatalog.create(services, statusService));
        executors.addAll(PaladinSkillExecutorCatalog.create(
                services, paladinHolyFieldRuntimeService, paladinHolySmiteRuntimeService, statusService, partyService,
                paladinGuardianProtectRuntimeService, playerDeathService));
        executors.addAll(SwordsmanSkillExecutorCatalog.create(services));
        executors.addAll(SwordmasterSkillExecutorCatalog.create(services, seijakuIssenSkillRuntimeService, skillService));
        return List.copyOf(executors);
    }
}
