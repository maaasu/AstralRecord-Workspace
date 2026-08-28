package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** ラストシールドの有効設定とプレイヤー別クールダウンを管理します。 */
public final class LastShieldSkillRuntimeService {
    private final SkillEffectService effects;
    private final Map<UUID, Map<String, Configuration>> configurations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownEndsAtTick = new ConcurrentHashMap<>();

    /**
     * runtime を構築します。
     *
     * @param particleDisplayService シールド防御演出サービス
     */
    public LastShieldSkillRuntimeService(@NotNull ParticleDisplayService particleDisplayService) {
        this.effects = new SkillEffectService(particleDisplayService);
    }

    /**
     * 有効化されたスキル個体の設定を登録します。
     *
     * @param context 有効化されたパッシブコンテキスト
     */
    public void activate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        configurations.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(configurationKey(context), new Configuration(context.skill().getCooldownTicks()));
    }

    /**
     * 無効化されるスキル個体の設定を破棄します。
     *
     * @param context 無効化されるパッシブコンテキスト
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null) {
            cooldownEndsAtTick.remove(playerId);
            return;
        }
        playerConfigurations.remove(configurationKey(context));
        if (playerConfigurations.isEmpty()) {
            configurations.remove(playerId, playerConfigurations);
            cooldownEndsAtTick.remove(playerId);
        }
    }

    /**
     * シールドを破壊する直接攻撃を、クールダウンが利用可能な場合だけ無効化します。
     *
     * @param victim 被弾対象
     * @param source ダメージ発生元
     * @return 無効化した場合は {@code true}
     */
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

        cooldownEndsAtTick.put(playerId, saturatingAdd(currentTick, configuration.cooldownTicks()));
        renderNegation(victim.player().getBukkit());
        return true;
    }

    /** プレイヤーの設定とクールダウンを破棄します。 */
    public void clearPlayer(@NotNull UUID playerId) {
        configurations.remove(playerId);
        cooldownEndsAtTick.remove(playerId);
    }

    /** サービス停止時に全プレイヤーの設定とクールダウンを破棄します。 */
    public void clearAll() {
        configurations.clear();
        cooldownEndsAtTick.clear();
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
                .min(Comparator.comparingLong(Configuration::cooldownTicks))
                .orElse(null);
    }

    private void renderNegation(@NotNull Player player) {
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5880);
        Location location = player.getLocation();
        Location center = location.clone().add(0.0D, 0.9D, 0.0D);
        effects.sound(location, Sound.ITEM_SHIELD_BLOCK, 0.9F, 0.85F);
        effects.sound(location, Sound.BLOCK_BEACON_ACTIVATE, 0.9F, 1.15F);
        effects.sound(location, Sound.ITEM_TRIDENT_THUNDER, 0.7F, 1.30F);
        effects.point(center, SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_TOTEM);
        effects.point(center, SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_SOUL_FIRE);
        effects.point(center, SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_SPARK);
        effects.point(center, SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_FLASH);
        effects.ring(
                center.clone().add(0.0D, -0.45D, 0.0D),
                0.85D,
                28,
                SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_DUST
        );
        effects.ring(
                center.clone().add(0.0D, 0.05D, 0.0D),
                0.64D,
                24,
                SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_END_ROD
        );
        effects.ring(
                center.clone().add(0.0D, 0.48D, 0.0D),
                0.46D,
                20,
                SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_SPARK
        );
    }

    private boolean isDirectDamage(@NotNull DamageSource source) {
        return source == DamageSource.NORMAL_ATTACK || source == DamageSource.SKILL;
    }

    private long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record Configuration(long cooldownTicks) {
    }
}
