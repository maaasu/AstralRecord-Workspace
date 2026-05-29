package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーのドッジ（短距離ダッシュ回避）アクションを制御するサービス。
 * <p>
 * ドッジは「しゃがみ開始から短時間以内にしゃがみを解除した」場合に発動候補となります。
 * 発動条件・スタミナ消費・演出（効果音/パーティクル）・
 * {@link AstPlayer#isDodging} フラグの ON/OFF を一元管理します。
 * <p>
 * 攻撃処理側では {@link AstPlayer#isDodging} を参照してジャスト回避判定を行ってください。
 */
public class DodgeService {

    /** しゃがみ開始から解除までがこのミリ秒未満であればドッジ判定対象とする */
    public static final long QUICK_SNEAK_WINDOW_MS = 250L;

    /** ドッジ中（ジャスト回避判定）フラグを true に保つ Tick 数（20Tick = 1秒） */
    private static final long DODGE_FLAG_DURATION_TICKS = 8L;

    /** ドッジ発動時に消費するエネルギー量 */
    private static final double ENERGY_COST = 15.0D;

    /** ドッジの加速ベクトルの強さ（水平方向） */
    private static final double DODGE_HORIZONTAL_STRENGTH = 1.0D;

    /** 落下抑制のために加える上向き成分 */
    private static final double DODGE_VERTICAL_STRENGTH = 0.15D;

    /** 進行方向ベクトル採用に必要な最小移動距離の二乗（XZ 平面） */
    private static final double MIN_TRAVEL_SQ = 1.0E-4D;

    /** パーティクル数 */
    private static final int PARTICLE_COUNT = 6;

    private final AstralRecord plugin;
    private final StatusService statusService;
    private final PlayerHudService playerHudService;
    private final ParticleDisplayService particleDisplayService;

    public DodgeService(
        @NotNull AstralRecord plugin,
        @NotNull StatusService statusService,
        @NotNull PlayerHudService playerHudService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.statusService = statusService;
        this.playerHudService = playerHudService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * 地上スニーク入力からドッジ受付ウィンドウを開始します。
     *
     * @param astPlayer 対象プレイヤー
     * @return ドッジ受付を開始した場合は {@code true}
     */
    public boolean beginSneakWindow(@NotNull AstPlayer astPlayer) {
        clearSneakWindowState(astPlayer);

        if (!astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return false;
        }

        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || player.isDead() || !isGrounded(player)) {
            return false;
        }

        long startedAtMs = System.currentTimeMillis();
        astPlayer.setSneakStartedAtMs(startedAtMs);
        astPlayer.setSneakStartedAtLocation(player.getLocation());
        astPlayer.setSneakDodgeWindowExpiresAtMs(startedAtMs + QUICK_SNEAK_WINDOW_MS);
        playerHudService.showDodgeWindow(astPlayer);
        return true;
    }

    /**
     * しゃがみ解除時にドッジ発動を試みます。
     * しゃがみ開始からの経過時間が {@link #QUICK_SNEAK_WINDOW_MS} 未満の場合のみ判定し、
     * エネルギー不足の場合は発動しません。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void tryTriggerOnSneakRelease(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }

        long sneakStartedAt = astPlayer.getSneakStartedAtMs();
        Location sneakStartedAtLocation = astPlayer.getSneakStartedAtLocation();
        clearSneakWindowState(astPlayer);
        playerHudService.restoreStatusActionBar(astPlayer);

        if (sneakStartedAt <= 0L) {
            return;
        }

        long sneakDuration = System.currentTimeMillis() - sneakStartedAt;
        if (sneakDuration >= QUICK_SNEAK_WINDOW_MS) {
            return;
        }

        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        if (!isGrounded(player)) {
            return;
        }

        StatusSnapshot snapshot = statusService.getStatus(astPlayer);
        if (snapshot.getCurrentEnergy() < ENERGY_COST) {
            playDenied(player);
            return;
        }

        executeDodge(astPlayer, sneakStartedAtLocation);
    }

    /**
     * ドッジを実行します（条件チェック済み前提）。
     *
     * @param astPlayer            対象プレイヤー
     * @param sneakStartedAtLocation しゃがみ開始時の座標（null の場合は視線方向にフォールバック）
     */
    private void executeDodge(@NotNull AstPlayer astPlayer, Location sneakStartedAtLocation) {
        Player player = astPlayer.getBukkit();

        statusService.consumeEnergy(astPlayer, ENERGY_COST);

        Vector direction = computeDodgeDirection(player, sneakStartedAtLocation);
        player.setVelocity(direction);

        astPlayer.setDodging(true);

        playDodgeEffects(astPlayer);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> astPlayer.setDodging(false), DODGE_FLAG_DURATION_TICKS);
    }

    /**
     * ドッジ方向のベクトルを計算します。
     * しゃがみ開始から解除までのプレイヤー移動方向（XZ 平面）を採用し、
     * 移動量が小さい場合のみ視線方向へフォールバックします。最後にジャンプ風の上昇成分を僅かに付加します。
     *
     * @param player                 対象 Bukkit プレイヤー
     * @param sneakStartedAtLocation しゃがみ開始時の座標
     * @return ドッジ用の {@link Vector}
     */
    private @NotNull Vector computeDodgeDirection(@NotNull Player player, Location sneakStartedAtLocation) {
        Vector direction = null;
        if (sneakStartedAtLocation != null && sneakStartedAtLocation.getWorld() == player.getWorld()) {
            Vector delta = player.getLocation().toVector().subtract(sneakStartedAtLocation.toVector());
            delta.setY(0.0D);
            if (delta.lengthSquared() > MIN_TRAVEL_SQ) {
                direction = delta.normalize();
            }
        }
        if (direction == null) {
            direction = player.getLocation().getDirection();
            direction.setY(0.0D);
            if (direction.lengthSquared() < 1.0E-6D) {
                direction = new Vector(0.0D, 0.0D, 1.0D);
            } else {
                direction.normalize();
            }
        }
        direction.multiply(DODGE_HORIZONTAL_STRENGTH);
        direction.setY(DODGE_VERTICAL_STRENGTH);
        return direction;
    }

    /**
     * ドッジ成功時の効果音とパーティクルを再生します。
     *
     * @param player 対象 Bukkit プレイヤー
     */
    private void playDodgeEffects(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        player.getWorld().playSound(
            player.getLocation(),
            Sound.ENTITY_PLAYER_ATTACK_SWEEP,
            SoundCategory.PLAYERS,
            0.8f,
            1.6f
        );
        particleDisplayService.spawnWorld(
            astPlayer,
            player.getWorld(),
            player.getLocation().add(0.0D, 0.2D, 0.0D),
            Particle.CLOUD,
            PARTICLE_COUNT,
            0.2D, 0.05D, 0.2D,
            0.0D
        );
    }

    /**
     * ドッジ発動失敗時（エネルギー不足）のフィードバック音を再生します。
     *
     * @param player 対象 Bukkit プレイヤー
     */
    private void playDenied(@NotNull Player player) {
        player.playSound(
            player.getLocation(),
            Sound.BLOCK_NOTE_BLOCK_BASS,
            SoundCategory.PLAYERS,
            0.4f,
            0.7f
        );
    }

    private void clearSneakWindowState(@NotNull AstPlayer astPlayer) {
        astPlayer.setSneakStartedAtMs(0L);
        astPlayer.setSneakStartedAtLocation(null);
        astPlayer.setSneakDodgeWindowExpiresAtMs(0L);
    }

    private boolean isGrounded(@NotNull Player player) {
        Location below = player.getLocation().clone().subtract(0.0D, 0.05D, 0.0D);
        return below.getBlock().getType().isSolid();
    }

}
