package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 空中スニーク入力から発動するエアーシフトの設定と実行を管理します。 */
public final class AirShiftSkillRuntimeService {
    /** 地上ドッジより強い空中移動用の水平速度です。 */
    public static final double HORIZONTAL_STRENGTH = 1.45D;
    /** 落下を抑えながら横移動を成立させる上向き速度です。 */
    public static final double VERTICAL_STRENGTH = 0.25D;
    /** 発動音量の既定値です。 */
    public static final double DEFAULT_TRIGGER_SOUND_VOLUME = 0.85D;
    /** 発動音程の既定値です。 */
    public static final double DEFAULT_TRIGGER_SOUND_PITCH = 1.15D;

    private static final double MIN_HORIZONTAL_LENGTH_SQ = 1.0E-6D;
    private static final int PARTICLE_COUNT = 8;

    private final StatusService statusService;
    private final JustDodgeSkillRuntimeService justDodgeSkillRuntimeService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, Map<String, Configuration>> configurations = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingLanding = ConcurrentHashMap.newKeySet();

    /**
     * エアーシフトの runtime を構築します。
     *
     * @param statusService ENG の検証と消費を行うサービス
     * @param justDodgeSkillRuntimeService ジャスト回避判定を開始するサービス
     * @param particleDisplayService 発動演出を表示するサービス
     */
    public AirShiftSkillRuntimeService(
            @NotNull StatusService statusService,
            @NotNull JustDodgeSkillRuntimeService justDodgeSkillRuntimeService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.statusService = statusService;
        this.justDodgeSkillRuntimeService = justDodgeSkillRuntimeService;
        this.particleDisplayService = particleDisplayService;
    }

    /** 有効化されたエアーシフトの設定をプレイヤーへ登録します。 */
    public void activate(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        Double configuredCost = context.skill().getResourceCost();
        Configuration configuration = new Configuration(
                configuredCost == null ? 0.0D : configuredCost,
                params.getDouble("horizontalStrength", HORIZONTAL_STRENGTH),
                params.getDouble("verticalStrength", VERTICAL_STRENGTH),
                params.requireString("triggerSound").trim(),
                (float) params.getDouble("triggerSoundVolume", DEFAULT_TRIGGER_SOUND_VOLUME),
                (float) params.getDouble("triggerSoundPitch", DEFAULT_TRIGGER_SOUND_PITCH)
        );
        UUID playerId = context.player().getBukkit().getUniqueId();
        configurations.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(configurationKey(context), configuration);
    }

    /** 無効化されたエアーシフトの設定をプレイヤーから除去します。 */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null) {
            clearActivationLock(playerId);
            return;
        }
        playerConfigurations.remove(configurationKey(context));
        if (playerConfigurations.isEmpty()) {
            configurations.remove(playerId, playerConfigurations);
            clearActivationLock(playerId);
        }
    }

    /**
     * 空中スニーク押下に対するエアーシフト発動を試みます。
     *
     * @param astPlayer 入力したプレイヤー
     * @return エアーシフトが入力を処理した場合は {@code true}
     */
    public boolean tryTrigger(@NotNull AstPlayer astPlayer) {
        Configuration configuration = effectiveConfiguration(astPlayer.getBukkit().getUniqueId());
        if (configuration == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) return false;
        if (astPlayer.isSkillCasting() || astPlayer.isWallClinging()) return false;

        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || player.isDead()) return false;
        UUID playerId = player.getUniqueId();
        if (isGrounded(player)) {
            clearActivationLock(playerId);
            return false;
        }
        if (awaitingLanding.contains(playerId)) return true;
        if (player.isFlying() || player.isGliding() || player.isSwimming() || player.isInsideVehicle()) return false;

        StatusSnapshot snapshot = statusService.getStatus(astPlayer);
        double energyCost = SkillService.resolveResourceCost(
                snapshot,
                SkillResourceType.ENERGY,
                configuration.energyCost()
        );
        if (snapshot.getCurrentEnergy() < energyCost) {
            playDenied(player);
            return true;
        }

        Vector direction = resolveInputDirection(player, player.getCurrentInput());
        direction.multiply(configuration.horizontalStrength());
        direction.setY(configuration.verticalStrength());

        statusService.consumeEnergy(astPlayer, energyCost);
        player.setFallDistance(0.0F);
        player.setVelocity(direction);
        awaitingLanding.add(playerId);
        justDodgeSkillRuntimeService.onDodge(astPlayer);
        playEffects(player, configuration);
        return true;
    }

    /**
     * 着地までの再発動ロックを解除します。
     *
     * @param playerId 解除対象プレイヤーのBukkit UUID
     */
    public void clearActivationLock(@NotNull UUID playerId) {
        awaitingLanding.remove(playerId);
    }

    /**
     * プレイヤーが着地している場合だけ再発動ロックを解除します。
     *
     * @param playerId 着地判定対象プレイヤーのBukkit UUID
     * @param location 移動先の位置
     */
    public void clearActivationLockIfGrounded(@NotNull UUID playerId, @NotNull Location location) {
        if (awaitingLanding.contains(playerId) && isGrounded(location)) {
            clearActivationLock(playerId);
        }
    }

    /** プレイヤー退出時に保持中の設定を破棄します。 */
    public void clearPlayer(@NotNull UUID playerId) {
        configurations.remove(playerId);
        clearActivationLock(playerId);
    }

    /** Plugin 停止時に全プレイヤーの設定を破棄します。 */
    public void clearAll() {
        configurations.clear();
        awaitingLanding.clear();
    }

    private @NotNull String configurationKey(@NotNull PassiveSkillContext context) {
        if (context.learnedSkill() != null) {
            return context.learnedSkill().getLearnedSkillId().toString();
        }
        return context.skill().getId();
    }

    private @Nullable Configuration effectiveConfiguration(@NotNull UUID playerId) {
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null || playerConfigurations.isEmpty()) return null;
        return playerConfigurations.values().stream()
                .max(Comparator
                        .comparingDouble(Configuration::horizontalStrength)
                        .thenComparingDouble(Configuration::verticalStrength))
                .orElse(null);
    }

    private @NotNull Vector resolveInputDirection(@NotNull Player player, @NotNull Input input) {
        Vector forward = player.getLocation().getDirection().clone();
        forward.setY(0.0D);
        if (forward.lengthSquared() < MIN_HORIZONTAL_LENGTH_SQ) {
            forward = new Vector(0.0D, 0.0D, 1.0D);
        } else {
            forward.normalize();
        }
        Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
        Vector direction = new Vector();
        if (input.isForward()) direction.add(forward);
        if (input.isBackward()) direction.subtract(forward);
        if (input.isRight()) direction.add(right);
        if (input.isLeft()) direction.subtract(right);
        if (direction.lengthSquared() < MIN_HORIZONTAL_LENGTH_SQ) {
            return forward.multiply(-1.0D);
        }
        return direction.normalize();
    }

    private void playEffects(@NotNull Player player, @NotNull Configuration configuration) {
        player.playSound(
                player.getLocation(),
                configuration.triggerSound(),
                SoundCategory.PLAYERS,
                configuration.triggerSoundVolume(),
                configuration.triggerSoundPitch()
        );
        particleDisplayService.spawnForNearbyViewers(
                player.getLocation().add(0.0D, 0.2D, 0.0D),
                SharedParticleDefinitions.AIR_ACTION_CLOUD.withCount(PARTICLE_COUNT)
        );
    }

    private void playDenied(@NotNull Player player) {
        player.playSound(
                player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_BASS,
                SoundCategory.PLAYERS,
                0.4F,
                0.7F
        );
    }

    private boolean isGrounded(@NotNull Player player) {
        return isGrounded(player.getLocation());
    }

    private boolean isGrounded(@NotNull Location location) {
        Location below = location.clone().subtract(0.0D, 0.05D, 0.0D);
        return below.getBlock().getType().isSolid();
    }

    private record Configuration(
            double energyCost,
            double horizontalStrength,
            double verticalStrength,
            @NotNull String triggerSound,
            float triggerSoundVolume,
            float triggerSoundPitch
    ) {
    }
}
