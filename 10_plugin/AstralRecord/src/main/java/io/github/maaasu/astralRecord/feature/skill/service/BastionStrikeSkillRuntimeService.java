package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** バスティオンストライクの有効設定と、被弾時の反撃状態を管理します。 */
public final class BastionStrikeSkillRuntimeService {
    public static final String SKILL_ID = "swordsman_bastion_strike";
    private static final int BASTION_PULSE_COUNT = 4;
    private static final long BASTION_PULSE_PERIOD_TICKS = 2L;
    private static final String BASTION_TASK_SCOPE = SKILL_ID + ":soul-bastion";

    private final SkillTargetingService targetingService;
    private final SkillCombatService combatService;
    private final SkillEffectService effectService;
    private final SkillTaskService taskService;
    private final Map<UUID, Map<String, Configuration>> configurations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownEndsAtTick = new ConcurrentHashMap<>();

    /** バスティオンストライクが使用する戦闘・対象選択・演出サービスで初期化します。 */
    public BastionStrikeSkillRuntimeService(
            @NotNull SkillTargetingService targetingService,
            @NotNull SkillCombatService combatService,
            @NotNull SkillEffectService effectService,
            @NotNull SkillTaskService taskService
    ) {
        this.targetingService = targetingService;
        this.combatService = combatService;
        this.effectService = effectService;
        this.taskService = taskService;
    }

    /** 有効化されたスキル個体の解決済み設定を登録します。 */
    public void activate(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        Configuration configuration = new Configuration(
                context.skill().getCooldownTicks(),
                params.getDouble("range", 6.0D),
                params.getDouble("damageRatio", 1.875D)
        );
        UUID playerId = context.player().getBukkit().getUniqueId();
        configurations.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(configurationKey(context), configuration);
    }

    /** 無効化されたスキル個体の設定と発動演出タスクを破棄します。 */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null) {
            cooldownEndsAtTick.remove(playerId);
            taskService.cancel(playerId, BASTION_TASK_SCOPE);
            return;
        }
        playerConfigurations.remove(configurationKey(context));
        if (playerConfigurations.isEmpty()) {
            configurations.remove(playerId, playerConfigurations);
            cooldownEndsAtTick.remove(playerId);
            taskService.cancel(playerId, BASTION_TASK_SCOPE);
        }
    }

    /** シールドを破壊する直接攻撃に対し、命中する反撃が成立した場合だけ攻撃を無効化します。 */
    public boolean tryNegateShieldBreakingDirectDamage(
            @NotNull AstEntity victim,
            @NotNull DamageSource source
    ) {
        if (!isDirectDamage(source) || !victim.isPlayer() || victim.player() == null) {
            return false;
        }

        UUID playerId = victim.id();
        Configuration configuration = effectiveConfiguration(playerId);
        if (configuration == null) {
            return false;
        }
        long currentTick = Bukkit.getCurrentTick();
        long cooldownEndsAt = cooldownEndsAtTick.getOrDefault(playerId, Long.MIN_VALUE);
        if (currentTick < cooldownEndsAt) {
            return false;
        }

        Player player = victim.player().getBukkit();
        Location eyeLocation = player.getEyeLocation();
        AstEntity target = targetingService.inLine(
                        player,
                        eyeLocation,
                        eyeLocation.getDirection(),
                        configuration.range(),
                        0.0D,
                        1
                )
                .stream()
                .findFirst()
                .orElse(null);
        if (target == null) {
            return false;
        }

        DamageResult counterattack = combatService.hit(
                victim,
                target,
                AttackType.MELEE,
                DamageElement.NONE,
                configuration.damageRatio()
        );
        if (!isSuccessfulHit(counterattack)) {
            return false;
        }

        cooldownEndsAtTick.put(
                playerId,
                saturatingAdd(currentTick, configuration.cooldownTicks())
        );
        recoverMissingShield(victim);
        renderActivation(player, target.location());
        return true;
    }

    /** プレイヤーの設定とクールダウンを破棄します。 */
    public void clearPlayer(@NotNull UUID playerId) {
        configurations.remove(playerId);
        cooldownEndsAtTick.remove(playerId);
        taskService.cancel(playerId, BASTION_TASK_SCOPE);
    }

    /** サービス停止時に全プレイヤーの設定、クールダウン、演出タスクを破棄します。 */
    public void clearAll() {
        configurations.keySet().forEach(playerId -> taskService.cancel(playerId, BASTION_TASK_SCOPE));
        configurations.clear();
        cooldownEndsAtTick.clear();
    }

    private void recoverMissingShield(@NotNull AstEntity victim) {
        if (!victim.isPlayer() || victim.player() == null) {
            return;
        }
        StatusSnapshot snapshot = victim.player().getStatusSnapshot();
        double missingShield = Math.max(
                0.0D,
                snapshot.getMaxValue(StatusType.MAX_SHIELD) - snapshot.getCurrentShield()
        );
        combatService.recoverShield(victim, missingShield);
    }

    private void renderActivation(@NotNull Player player, @NotNull Location targetLocation) {
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5880);

        Location center = player.getLocation().clone().add(0.0D, 0.95D, 0.0D);
        effectService.line(
                center.clone().subtract(0.0D, 0.85D, 0.0D),
                center.clone().add(0.0D, 1.35D, 0.0D),
                0.14D,
                SharedParticleDefinitions.BASTION_STRIKE_SOUL_FIRE
        );
        effectService.point(center, SharedParticleDefinitions.BASTION_STRIKE_RUNE_DUST);
        effectService.sound(center, Sound.ITEM_SHIELD_BLOCK, 0.9F, 0.85F);
        effectService.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.9F, 1.15F);
        effectService.sound(center, Sound.ITEM_TRIDENT_THUNDER, 0.7F, 1.30F);

        World castWorld = player.getWorld();
        taskService.repeat(
                player.getUniqueId(),
                BASTION_TASK_SCOPE,
                0L,
                BASTION_PULSE_PERIOD_TICKS,
                BASTION_PULSE_COUNT,
                frame -> renderBastionPulse(player, castWorld, center, targetLocation, frame)
        );
    }

    private void renderBastionPulse(
            @NotNull Player player,
            @NotNull World castWorld,
            @NotNull Location center,
            @NotNull Location targetLocation,
            int frame
    ) {
        if (!player.isOnline() || player.getWorld() != castWorld) {
            return;
        }
        Location pulseCenter = center.clone().add(0.0D, frame * 0.18D, 0.0D);
        effectService.ring(
                pulseCenter,
                0.65D + frame * 0.28D,
                24,
                SharedParticleDefinitions.BASTION_STRIKE_SOUL_FIRE
        );
        effectService.ring(
                pulseCenter,
                0.42D + frame * 0.20D,
                16,
                SharedParticleDefinitions.BASTION_STRIKE_RUNE_DUST
        );
        if (frame == BASTION_PULSE_COUNT - 1) {
            effectService.point(targetLocation, SharedParticleDefinitions.BASTION_STRIKE_IMPACT_FLASH);
            effectService.point(targetLocation, SharedParticleDefinitions.BASTION_STRIKE_IMPACT_SPARK);
            effectService.sound(targetLocation, Sound.ITEM_TRIDENT_THUNDER, 1.0F, 1.25F);
        }
    }

    private @NotNull String configurationKey(@NotNull PassiveSkillContext context) {
        if (context.learnedSkill() != null) {
            return context.learnedSkill().getLearnedSkillId().toString();
        }
        return context.skill().getId();
    }

    private Configuration effectiveConfiguration(@NotNull UUID playerId) {
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null || playerConfigurations.isEmpty()) {
            return null;
        }
        return playerConfigurations.values().stream()
                .min(Comparator
                        .comparingLong(Configuration::cooldownTicks)
                        .thenComparingDouble(configuration -> -configuration.damageRatio()))
                .orElse(null);
    }

    private static boolean isSuccessfulHit(@NotNull DamageResult result) {
        return !result.evaded()
                && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D);
    }

    private static boolean isDirectDamage(@NotNull DamageSource source) {
        return source == DamageSource.NORMAL_ATTACK || source == DamageSource.SKILL;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record Configuration(long cooldownTicks, double range, double damageRatio) {
    }
}
