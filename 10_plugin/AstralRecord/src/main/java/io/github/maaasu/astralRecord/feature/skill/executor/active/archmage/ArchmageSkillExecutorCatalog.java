package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.service.BindCircleRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.InvulnerabilityVisualService;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** アークメイジの固有発動スキルをまとめて登録します。 */
public final class ArchmageSkillExecutorCatalog {
    private ArchmageSkillExecutorCatalog() { }

    /**
     * 固有アクティブ16種を作成します。
     * @param services 共有発動サービス
     * @param celestial セレスティアルサークル状態
     * @param skillService 回復陣のクールタイム管理
     * @param death 復活待ち状態
     * @param boss ボス死亡回数
     * @param dungeon ダンジョン死亡回数
     * @param invulnerability 復活後の無敵表示
     * @param status シールドとステータス
     * @param bind 拘束状態
     * @param plugin 独立拘束タスクの所有プラグイン
     * @return 固有アクティブ16種
     */
    public static @NotNull List<SkillExecutor> create(
            @NotNull ActiveSkillServices services,
            @NotNull ArchmageCelestialCircleRuntimeService celestial,
            @NotNull SkillService skillService,
            @NotNull PlayerDeathService death,
            @NotNull BossChallengeService boss,
            @NotNull DungeonService dungeon,
            @NotNull InvulnerabilityVisualService invulnerability,
            @NotNull StatusService status,
            @NotNull BindCircleRuntimeService bind,
            @NotNull Plugin plugin
    ) {
        return List.of(
                new ArchmageCelestialCircleExecutor(services, celestial),
                new ArchmageHealCircleExecutor(services, skillService),
                new ArchmageClearCircleExecutor(services),
                new ArchmageRebornProtectCircleExecutor(services, death, boss, dungeon, invulnerability, status),
                new ArchmageAstralRayExecutor(services),
                new ArchmageBindCircleExecutor(services, bind, plugin),
                new ArchmageAegisCircleExecutor(services),
                new ArchmageRenewalCircleExecutor(services),
                new ArchmageVigorCircleExecutor(services),
                new ArchmageStasisCircleExecutor(services),
                new ArchmageRepulsionCircleExecutor(services),
                new ArchmageMeteorCircleExecutor(services),
                new ArchmageStarfallExecutor(services),
                new ArchmageAstralSpearExecutor(services),
                new ArchmageOrbitalVolleyExecutor(services),
                new ArchmageGravityNovaExecutor(services)
        );
    }
}
