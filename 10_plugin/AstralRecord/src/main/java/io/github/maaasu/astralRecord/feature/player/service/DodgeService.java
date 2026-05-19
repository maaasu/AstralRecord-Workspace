package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
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
 * 発動条件・クールダウン管理・スタミナ消費・演出（効果音/パーティクル）・
 * {@link AstPlayer#isDodging} フラグの ON/OFF を一元管理します。
 * <p>
 * 攻撃処理側では {@link AstPlayer#isDodging} を参照してジャスト回避判定を行ってください。
 */
public class DodgeService {

    /** しゃがみ開始から解除までがこのミリ秒未満であればドッジ判定対象とする */
    public static final long QUICK_SNEAK_WINDOW_MS = 250L;

    /** ドッジのクールタイム（ミリ秒） */
    private static final long COOLDOWN_MS = 2000L;

    /** ドッジ中（ジャスト回避判定）フラグを true に保つ Tick 数（20Tick = 1秒） */
    private static final long DODGE_FLAG_DURATION_TICKS = 8L;

    /** ドッジ発動時に消費するエネルギー量 */
    private static final double ENERGY_COST = 15.0D;

    /** ドッジの加速ベクトルの強さ（水平方向） */
    private static final double DODGE_HORIZONTAL_STRENGTH = 1.0D;

    /** 落下抑制のために加える上向き成分 */
    private static final double DODGE_VERTICAL_STRENGTH = 0.15D;

    /** パーティクル数 */
    private static final int PARTICLE_COUNT = 20;

    private final AstralRecord plugin;
    private final StatusService statusService;

    public DodgeService(@NotNull AstralRecord plugin, @NotNull StatusService statusService) {
        this.plugin = plugin;
        this.statusService = statusService;
    }

    /**
     * しゃがみ解除時にドッジ発動を試みます。
     * しゃがみ開始からの経過時間が {@link #QUICK_SNEAK_WINDOW_MS} 未満の場合のみ判定し、
     * クールダウン未経過 / エネルギー不足の場合は発動しません。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void tryTriggerOnSneakRelease(@NotNull AstPlayer astPlayer) {
        long sneakStartedAt = astPlayer.getSneakStartedAtMs();
        if (sneakStartedAt <= 0L) {
            return;
        }
        astPlayer.setSneakStartedAtMs(0L);

        long now = System.currentTimeMillis();
        long sneakDuration = now - sneakStartedAt;
        if (sneakDuration >= QUICK_SNEAK_WINDOW_MS) {
            return;
        }

        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || player.isDead()) {
            return;
        }

        if (now < astPlayer.getDodgeCooldownEndAtMs()) {
            playDenied(player);
            return;
        }

        StatusSnapshot snapshot = statusService.getStatus(astPlayer);
        if (snapshot.getCurrentEnergy() < ENERGY_COST) {
            playDenied(player);
            return;
        }

        executeDodge(astPlayer);
    }

    /**
     * ドッジを実行します（条件チェック済み前提）。
     *
     * @param astPlayer 対象プレイヤー
     */
    private void executeDodge(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();

        statusService.consumeEnergy(astPlayer, ENERGY_COST);

        Vector direction = computeDodgeDirection(player);
        player.setVelocity(direction);

        astPlayer.setDodging(true);
        astPlayer.setDodgeCooldownEndAtMs(System.currentTimeMillis() + COOLDOWN_MS);

        playDodgeEffects(player);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> astPlayer.setDodging(false), DODGE_FLAG_DURATION_TICKS);
    }

    /**
     * ドッジ方向のベクトルを計算します。
     * プレイヤーの移動入力（ない場合は視線方向）を水平に正規化し、ジャンプ風の上昇成分を僅かに付加します。
     *
     * @param player 対象 Bukkit プレイヤー
     * @return ドッジ用の {@link Vector}
     */
    private @NotNull Vector computeDodgeDirection(@NotNull Player player) {
        Vector facing = player.getLocation().getDirection();
        facing.setY(0.0D);
        if (facing.lengthSquared() < 1.0E-6D) {
            facing = new Vector(0.0D, 0.0D, 1.0D);
        } else {
            facing.normalize();
        }
        facing.multiply(DODGE_HORIZONTAL_STRENGTH);
        facing.setY(DODGE_VERTICAL_STRENGTH);
        return facing;
    }

    /**
     * ドッジ成功時の効果音とパーティクルを再生します。
     *
     * @param player 対象 Bukkit プレイヤー
     */
    private void playDodgeEffects(@NotNull Player player) {
        player.getWorld().playSound(
            player.getLocation(),
            Sound.ENTITY_PLAYER_ATTACK_SWEEP,
            SoundCategory.PLAYERS,
            0.8f,
            1.6f
        );
        player.getWorld().playSound(
            player.getLocation(),
            Sound.ENTITY_HORSE_BREATHE,
            SoundCategory.PLAYERS,
            0.4f,
            1.8f
        );
        player.getWorld().spawnParticle(
            Particle.CLOUD,
            player.getLocation().add(0.0D, 1.0D, 0.0D),
            PARTICLE_COUNT,
            0.3D, 0.3D, 0.3D,
            0.05D
        );
        player.getWorld().spawnParticle(
            Particle.SWEEP_ATTACK,
            player.getLocation().add(0.0D, 1.0D, 0.0D),
            1,
            0.0D, 0.0D, 0.0D,
            0.0D
        );
    }

    /**
     * ドッジ発動失敗時（クールダウン中・エネルギー不足）のフィードバック音を再生します。
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
}
