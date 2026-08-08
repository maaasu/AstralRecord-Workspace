package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * メディテーションのスニーク継続と発動状態を管理します。
 * <p>
 * 状態は Bukkit Player UUID 単位の短命なメモリ状態だけで、ログアウト・死亡・バインド解除時に
 * 破棄されます。自然回復の値そのものは変更せず、発動中かどうかだけを公開します。
 */
public final class MeditationSkillRuntimeService {
    private static final int DEFAULT_CHARGE_TICKS = 100;
    private static final double DEFAULT_REGEN_MULTIPLIER = 3.0D;
    private static final int DEFAULT_CHARGE_PARTICLE_INTERVAL_TICKS = 10;
    private static final int DEFAULT_ACTIVE_PARTICLE_INTERVAL_TICKS = 5;
    private static final int DEFAULT_ACTIVE_SOUND_INTERVAL_TICKS = 40;

    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    /**
     * 表示サービスを受け取って runtime を構築します。
     *
     * @param particleDisplayService 共通パーティクル表示サービス
     */
    public MeditationSkillRuntimeService(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * バインド済みパッシブの tick を処理します。
     * 準備中・発動中の particle と、発動中の継続音もこの tick で更新します。
     *
     * @param context 解決済みパッシブコンテキスト
     */
    public void tick(@NotNull PassiveSkillContext context) {
        AstPlayer astPlayer = context.player();
        Player player = astPlayer.getBukkit();
        UUID playerId = player.getUniqueId();
        State state = states.computeIfAbsent(playerId, ignored -> new State());
        if (!player.isOnline() || player.isDead() || !player.isSneaking()) {
            states.remove(playerId);
            return;
        }

        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        int chargeTicks = readPositiveInt(params, "chargeTicks", DEFAULT_CHARGE_TICKS);
        int chargeInterval = readPositiveInt(
            params, "chargeParticleIntervalTicks", DEFAULT_CHARGE_PARTICLE_INTERVAL_TICKS
        );
        int activeInterval = readPositiveInt(
            params, "activeParticleIntervalTicks", DEFAULT_ACTIVE_PARTICLE_INTERVAL_TICKS
        );
        int activeSoundInterval = readPositiveInt(
            params, "activeSoundIntervalTicks", DEFAULT_ACTIVE_SOUND_INTERVAL_TICKS
        );
        long activeTicks = context.activeTicks();
        if (state.sneakStartedAtTick == null) {
            state.sneakStartedAtTick = activeTicks;
        }

        if (!state.effectActive && activeTicks - state.sneakStartedAtTick >= chargeTicks) {
            state.effectActive = true;
            state.lastSoundTick = activeTicks;
            renderActivation(player);
        }

        if (state.effectActive) {
            if (activeTicks - state.lastParticleTick >= activeInterval) {
                state.lastParticleTick = activeTicks;
                renderActive(player, activeTicks);
            }
            if (activeTicks - state.lastSoundTick >= activeSoundInterval) {
                state.lastSoundTick = activeTicks;
                renderActiveSound(player);
            }
        } else if (activeTicks - state.lastParticleTick >= chargeInterval) {
            state.lastParticleTick = activeTicks;
            renderCharging(player);
        }
    }

    /**
     * プレイヤーのメディテーション発動状態を返します。
     *
     * @param playerId プレイヤー UUID
     * @return 効果発動中なら true
     */
    public boolean isEffectActive(@NotNull UUID playerId) {
        State state = states.get(playerId);
        return state != null && state.effectActive;
    }

    /**
     * スニーク継続と発動状態を即時リセットします。
     *
     * @param playerId プレイヤー UUID
     */
    public void interrupt(@NotNull UUID playerId) {
        states.remove(playerId);
    }

    /**
     * サービス停止時に全プレイヤーの runtime 状態を破棄します。
     */
    public void clearAll() {
        states.clear();
    }

    private void renderCharging(@NotNull Player player) {
        Location base = player.getLocation();
        if (base == null) return;
        particleDisplayService.spawnForNearbyViewers(
            base.clone().add(0.0D, 1.0D, 0.0D),
            SharedParticleDefinitions.ADVENTURER_MEDITATION_CHARGE
        );
    }

    private void renderActivation(@NotNull Player player) {
        Location base = player.getLocation();
        if (base == null || base.getWorld() == null) return;
        Location center = base.clone().add(0.0D, 0.08D, 0.0D);
        particleDisplayService.spawnForNearbyViewers(
            center,
            ringLocations(center, 1.0D, 16),
            SharedParticleDefinitions.ADVENTURER_MEDITATION_RING
        );
        World world = base.getWorld();
        world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55F, 1.15F);
    }

    private void renderActive(@NotNull Player player, long activeTicks) {
        Location base = player.getLocation();
        if (base == null || base.getWorld() == null) return;
        Location center = base.clone().add(0.0D, 1.0D, 0.0D);
        int points = 6;
        double phase = activeTicks * 0.16D;
        ArrayList<Location> locations = new ArrayList<>(points);
        for (int index = 0; index < points; index++) {
            double angle = phase + Math.PI * 2.0D * index / points;
            locations.add(center.clone().add(Math.cos(angle) * 0.8D, Math.sin(angle * 0.5D) * 0.2D, Math.sin(angle) * 0.8D));
        }
        particleDisplayService.spawnForNearbyViewers(
            center,
            locations,
            SharedParticleDefinitions.ADVENTURER_MEDITATION_AURA
        );
    }

    /**
     * メディテーション発動中であることを示す控えめな環境音を再生します。
     *
     * @param player 発動中のプレイヤー
     */
    private void renderActiveSound(@NotNull Player player) {
        Location base = player.getLocation();
        if (base == null || base.getWorld() == null) return;
        base.getWorld().playSound(
            base,
            Sound.BLOCK_BEACON_AMBIENT,
            SoundCategory.PLAYERS,
            0.35F,
            1.15F
        );
    }

    private ArrayList<Location> ringLocations(@NotNull Location center, double radius, int points) {
        ArrayList<Location> locations = new ArrayList<>(points);
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0D * index / points;
            locations.add(center.clone().add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius));
        }
        return locations;
    }

    /**
     * パッシブ定義から正の整数パラメータを読み取ります。
     *
     * @param params スキルパラメータ reader
     * @param key パラメータキー
     * @param defaultValue 未定義時の既定値
     * @return 1以上の整数値
     * @throws io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException 値が整数でない場合
     */
    private int readPositiveInt(
        @NotNull SkillParamReader params,
        @NotNull String key,
        int defaultValue
    ) {
        return Math.max(1, params.getInt(key, defaultValue));
    }

    private static final class State {
        private Long sneakStartedAtTick;
        private long lastParticleTick;
        private long lastSoundTick;
        private boolean effectActive;
    }
}
