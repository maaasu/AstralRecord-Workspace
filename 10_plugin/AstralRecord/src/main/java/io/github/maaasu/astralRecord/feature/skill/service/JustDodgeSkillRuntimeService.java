package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ジャスト回避の有効時間と、ドッジごとのエネルギー回復状態を管理します。
 */
public final class JustDodgeSkillRuntimeService {
    private static final int PARTICLE_COUNT = 6;

    private final StatusService statusService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, Map<String, Configuration>> configurations = new ConcurrentHashMap<>();
    private final Map<UUID, DodgeState> states = new ConcurrentHashMap<>();

    /**
     * runtime を構築します。
     *
     * @param statusService エネルギー回復サービス
     * @param particleDisplayService パーティクル表示サービス
     */
    public JustDodgeSkillRuntimeService(
            @NotNull StatusService statusService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.statusService = statusService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * 有効化されたスキル個体の設定を登録します。
     *
     * @param context 有効化されたパッシブコンテキスト
     */
    public void activate(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        Configuration configuration = new Configuration(
                params.getInt("invulnerabilityTicks", 0),
                params.getDouble("energyRecoveryAmount", 0.0D)
        );
        UUID playerId = context.player().getBukkit().getUniqueId();
        configurations.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(configurationKey(context), configuration);
    }

    /**
     * 無効化されたスキル個体の設定と一時状態を破棄します。
     *
     * @param context 無効化されるパッシブコンテキスト
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null) {
            states.remove(playerId);
            return;
        }
        playerConfigurations.remove(configurationKey(context));
        if (playerConfigurations.isEmpty()) {
            configurations.remove(playerId, playerConfigurations);
            states.remove(playerId);
        }
    }

    /**
     * 成功したドッジによって無効化時間を開始します。
     *
     * @param player ドッジを行ったプレイヤー
     */
    public void onDodge(@NotNull AstPlayer player) {
        UUID playerId = player.getBukkit().getUniqueId();
        Configuration configuration = effectiveConfiguration(playerId);
        if (configuration == null) {
            states.remove(playerId);
            return;
        }
        long expiresAtTick = (long) Bukkit.getCurrentTick() + configuration.invulnerabilityTicks();
        states.put(playerId, new DodgeState(expiresAtTick, configuration.energyRecoveryAmount()));
    }

    /**
     * プレイヤーの短命なドッジ状態だけを破棄します。
     *
     * @param playerId 破棄対象プレイヤーのBukkit UUID
     */
    public void clearDodgeState(@NotNull UUID playerId) {
        states.remove(playerId);
    }

    /**
     * プレイヤーの設定と短命なドッジ状態を破棄します。
     *
     * @param playerId 破棄対象プレイヤーのBukkit UUID
     */
    public void clearPlayer(@NotNull UUID playerId) {
        configurations.remove(playerId);
        clearDodgeState(playerId);
    }

    /**
     * サービス停止時に全プレイヤーの設定と短命状態を破棄します。
     */
    public void clearAll() {
        configurations.clear();
        states.clear();
    }

    /**
     * 有効時間内の直接攻撃を無効化します。
     *
     * @param victim 被弾対象
     * @param source ダメージ発生元
     * @param calculated 計算済みダメージ
     * @return 無効化した場合は true
     */
    public boolean tryNegateDirectDamage(
            @NotNull AstEntity victim,
            @NotNull DamageSource source,
            @NotNull DamageResult calculated
    ) {
        if (!isDirectDamage(source)
                || !victim.isPlayer()
                || victim.player() == null
                || calculated.evaded()
                || calculated.finalDamage() <= 0.0D) {
            return false;
        }

        UUID playerId = victim.id();
        DodgeState state = states.get(playerId);
        long currentTick = Bukkit.getCurrentTick();
        if (state == null || currentTick >= state.expiresAtTick()) {
            if (state != null) states.remove(playerId, state);
            return false;
        }

        boolean energyRecovered = false;
        if (!state.energyRecovered()) {
            state.markEnergyRecovered();
            statusService.recoverEnergy(victim.player(), state.energyRecoveryAmount());
            energyRecovered = true;
        }
        renderNegation(victim.player().getBukkit(), energyRecovered);
        return true;
    }

    private @NotNull String configurationKey(@NotNull PassiveSkillContext context) {
        if (context.learnedSkill() != null) {
            return context.learnedSkill().getLearnedSkillId().toString();
        }
        return context.skill().getId();
    }

    private Configuration effectiveConfiguration(@NotNull UUID playerId) {
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null || playerConfigurations.isEmpty()) return null;
        return playerConfigurations.values().stream()
                .max(Comparator
                        .comparingInt(Configuration::invulnerabilityTicks)
                        .thenComparingDouble(Configuration::energyRecoveryAmount))
                .orElse(null);
    }

    private void renderNegation(@NotNull Player player, boolean energyRecovered) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) return;
        world.playSound(
                location,
                Sound.ITEM_SHIELD_BLOCK,
                SoundCategory.PLAYERS,
                0.85F,
                1.35F
        );
        particleDisplayService.spawnForNearbyViewers(
                location.clone().add(0.0D, 0.2D, 0.0D),
                SharedParticleDefinitions.DODGE_CLOUD.withCount(PARTICLE_COUNT)
        );
        if (energyRecovered) {
            world.playSound(
                    location,
                    Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                    SoundCategory.PLAYERS,
                    0.75F,
                    1.15F
            );
            particleDisplayService.spawnForNearbyViewers(
                    location.clone().add(0.0D, 1.0D, 0.0D),
                    SharedParticleDefinitions.JUST_DODGE_ENERGY_ABSORB_END_ROD
            );
        }
    }

    private boolean isDirectDamage(@NotNull DamageSource source) {
        return source == DamageSource.NORMAL_ATTACK || source == DamageSource.SKILL;
    }

    private record Configuration(int invulnerabilityTicks, double energyRecoveryAmount) {
    }

    private static final class DodgeState {
        private final long expiresAtTick;
        private final double energyRecoveryAmount;
        private boolean energyRecovered;

        private DodgeState(long expiresAtTick, double energyRecoveryAmount) {
            this.expiresAtTick = expiresAtTick;
            this.energyRecoveryAmount = energyRecoveryAmount;
        }

        private long expiresAtTick() {
            return expiresAtTick;
        }

        private double energyRecoveryAmount() {
            return energyRecoveryAmount;
        }

        private boolean energyRecovered() {
            return energyRecovered;
        }

        private void markEnergyRecovered() {
            energyRecovered = true;
        }
    }
}
