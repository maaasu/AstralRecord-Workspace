package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーの空中アクション（二段ジャンプ / 壁張り付き / 壁キック）を制御するサービスです。
 */
public class AirActionService {

    /** 二段ジャンプ時に与える上方向速度です。 */
    public static final double DOUBLE_JUMP_VERTICAL_STRENGTH = 0.55D;

    /** 壁張り付き可能な最大時間です。 */
    public static final long WALL_CLING_DURATION_MS = 1500L;

    private static final double DOUBLE_JUMP_FORWARD_STRENGTH = 0.30D;
    private static final double WALL_KICK_HORIZONTAL_STRENGTH = 0.95D;
    private static final double WALL_KICK_VERTICAL_STRENGTH = 0.48D;
    private static final double WALL_DETECT_DISTANCE = 0.90D;
    private static final double MIN_HORIZONTAL_LENGTH_SQ = 1.0E-6D;
    private static final double WALL_KICK_DIRECTION_DOT_THRESHOLD = -0.5D;
    private static final int AIR_ACTION_PARTICLE_COUNT = 8;
    private static final long DOUBLE_JUMP_TRIGGER_DELAY_TICKS = 4L;
    private static final long DOUBLE_JUMP_TRIGGER_DELAY_MS = DOUBLE_JUMP_TRIGGER_DELAY_TICKS * 50L;
    private static final Sound DOUBLE_JUMP_SOUND = Sound.ENTITY_HORSE_JUMP;

    private final AstralRecord plugin;
    private final PlayerHudService playerHudService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, BukkitTask> wallClingTasks = new HashMap<>();

    public AirActionService(
        @NotNull AstralRecord plugin,
        @NotNull PlayerHudService playerHudService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.playerHudService = playerHudService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * ジャンプ入力の押下状態を取り込み、必要に応じて二段ジャンプを実行します。
     *
     * @param astPlayer   対象プレイヤー
     * @param jumpPressed 現在のジャンプ押下状態
     */
    public void handleJumpInput(@NotNull AstPlayer astPlayer, boolean jumpPressed) {
        boolean wasJumpPressed = astPlayer.isJumpInputPressed();
        astPlayer.setJumpInputPressed(jumpPressed);

        if (!astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        if (astPlayer.isSkillCasting()) {
            return;
        }

        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        long nowMs = System.currentTimeMillis();

        if (isGrounded(player)) {
            astPlayer.setAirJumpConsumed(false);
            if (jumpPressed && !wasJumpPressed) {
                astPlayer.setDoubleJumpCooldownUntilMs(nowMs + DOUBLE_JUMP_TRIGGER_DELAY_MS);
            }
            return;
        }

        if (!jumpPressed || wasJumpPressed || astPlayer.isAirJumpConsumed() || astPlayer.isWallClinging()) {
            return;
        }
        if (nowMs < astPlayer.getDoubleJumpCooldownUntilMs()) {
            return;
        }

        if (player.isFlying() || player.isGliding() || player.isSwimming() || player.isInsideVehicle()) {
            return;
        }

        executeDoubleJump(astPlayer);
    }

    /**
     * 空中スニーク時に壁張り付きを開始できるか判定し、条件を満たす場合は開始します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 壁張り付きを開始した場合は {@code true}
     */
    public boolean tryStartWallCling(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return false;
        }
        if (astPlayer.isSkillCasting()) {
            return false;
        }

        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || player.isDead() || isGrounded(player)) {
            return false;
        }
        if (player.isFlying() || player.isGliding() || player.isSwimming() || player.isInsideVehicle()) {
            return false;
        }

        Vector towardWallDirection = findTowardWallDirection(player);
        if (towardWallDirection == null) {
            return false;
        }

        long expiresAtMs = System.currentTimeMillis() + WALL_CLING_DURATION_MS;
        astPlayer.setWallClingTowardWallDirection(towardWallDirection);
        astPlayer.setWallClingExpiresAtMs(expiresAtMs);
        astPlayer.setWallClinging(true);

        player.setGravity(false);
        player.setFallDistance(0.0F);
        player.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        playerHudService.showWallClingWindow(astPlayer);

        cancelWallClingTask(player.getUniqueId());
        BukkitTask wallClingTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> maintainWallCling(astPlayer), 1L, 1L);
        wallClingTasks.put(player.getUniqueId(), wallClingTask);
        return true;
    }

    /**
     * 壁張り付き中のスニーク解除を処理し、必要に応じて壁キックを実行します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 壁張り付き解除を処理した場合は {@code true}
     */
    public boolean releaseWallCling(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.isWallClinging()) {
            return false;
        }

        endWallCling(astPlayer, !astPlayer.isSkillCasting() && shouldWallKick(astPlayer));
        return true;
    }

    /**
     * 停止中タスクを解除し、壁張り付き状態を安全に解放します。
     */
    public void stop() {
        for (BukkitTask task : wallClingTasks.values()) {
            task.cancel();
        }
        wallClingTasks.clear();

        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            if (astPlayer.isWallClinging()) {
                endWallCling(astPlayer, false);
            }
        }
    }

    private void executeDoubleJump(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        Vector velocity = player.getVelocity().clone();
        Vector facing = horizontalDirection(player.getLocation());

        if (facing != null) {
            Vector forwardBoost = facing.multiply(DOUBLE_JUMP_FORWARD_STRENGTH);
            velocity.setX((velocity.getX() * 0.6D) + forwardBoost.getX());
            velocity.setZ((velocity.getZ() * 0.6D) + forwardBoost.getZ());
        }
        velocity.setY(Math.max(velocity.getY(), 0.0D) + DOUBLE_JUMP_VERTICAL_STRENGTH);

        astPlayer.setAirJumpConsumed(true);
        player.setFallDistance(0.0F);
        player.setVelocity(velocity);
        player.playSound(
            player.getLocation(),
            DOUBLE_JUMP_SOUND,
            SoundCategory.PLAYERS,
            0.55F,
            1.08F
        );
        particleDisplayService.spawnForNearbyViewers(
            player.getLocation().add(0.0D, 0.2D, 0.0D),
            SharedParticleDefinitions.AIR_ACTION_CLOUD.withCount(AIR_ACTION_PARTICLE_COUNT)
        );
    }

    private void maintainWallCling(@NotNull AstPlayer astPlayer) {
        if (!shouldKeepWallCling(astPlayer)) {
            endWallCling(astPlayer, false);
            return;
        }

        Player player = astPlayer.getBukkit();
        player.setFallDistance(0.0F);
        player.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
    }

    private boolean shouldKeepWallCling(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        return astPlayer.isWallClinging()
            && player.isOnline()
            && !player.isDead()
            && !isGrounded(player)
            && player.isSneaking()
            && astPlayer.getWallClingExpiresAtMs() > System.currentTimeMillis();
    }

    private void endWallCling(@NotNull AstPlayer astPlayer, boolean wallKick) {
        Player player = astPlayer.getBukkit();
        Vector towardWallDirection = astPlayer.getWallClingTowardWallDirection() == null
            ? null
            : astPlayer.getWallClingTowardWallDirection().clone();
        cancelWallClingTask(player.getUniqueId());

        astPlayer.setWallClinging(false);
        astPlayer.setWallClingExpiresAtMs(0L);
        astPlayer.setWallClingTowardWallDirection(null);

        player.setGravity(true);
        playerHudService.restoreStatusActionBar(astPlayer);

        if (wallKick && player.isOnline() && !player.isDead()) {
            executeWallKick(player, astPlayer, towardWallDirection);
        }
    }

    private void executeWallKick(@NotNull Player player, @NotNull AstPlayer astPlayer, @Nullable Vector towardWallDirection) {
        Vector facing = horizontalDirection(player.getLocation());
        if (facing == null) {
            facing = towardWallDirection == null ? new Vector(0.0D, 0.0D, 1.0D) : towardWallDirection.clone().multiply(-1.0D);
            facing.setY(0.0D);
            facing.normalize();
        }

        Vector velocity = facing.multiply(WALL_KICK_HORIZONTAL_STRENGTH);
        velocity.setY(WALL_KICK_VERTICAL_STRENGTH);

        player.setFallDistance(0.0F);
        player.setVelocity(velocity);
        player.playSound(player.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 0.75F, 1.15F);
        particleDisplayService.spawnForNearbyViewers(
            player.getLocation().add(0.0D, 0.2D, 0.0D),
            SharedParticleDefinitions.AIR_ACTION_CLOUD.withCount(AIR_ACTION_PARTICLE_COUNT)
        );
    }

    private boolean shouldWallKick(@NotNull AstPlayer astPlayer) {
        Vector towardWall = astPlayer.getWallClingTowardWallDirection();
        Vector facing = horizontalDirection(astPlayer.getBukkit().getLocation());
        if (towardWall == null || facing == null) {
            return false;
        }

        return facing.dot(towardWall) <= WALL_KICK_DIRECTION_DOT_THRESHOLD;
    }

    private @Nullable Vector findTowardWallDirection(@NotNull Player player) {
        Vector facing = horizontalDirection(player.getLocation());
        if (facing == null) {
            return null;
        }

        Location probeLocation = player.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        RayTraceResult hit = player.getWorld().rayTraceBlocks(
            probeLocation,
            facing,
            WALL_DETECT_DISTANCE,
            FluidCollisionMode.NEVER,
            true
        );
        if (hit == null || hit.getHitBlock() == null || !hit.getHitBlock().getType().isSolid()) {
            return null;
        }

        Vector towardWall = hit.getHitPosition().subtract(probeLocation.toVector());
        towardWall.setY(0.0D);
        if (towardWall.lengthSquared() < MIN_HORIZONTAL_LENGTH_SQ) {
            return facing;
        }
        return towardWall.normalize();
    }

    private @Nullable Vector horizontalDirection(@NotNull Location location) {
        Vector direction = location.getDirection().clone();
        direction.setY(0.0D);
        if (direction.lengthSquared() < MIN_HORIZONTAL_LENGTH_SQ) {
            return null;
        }
        return direction.normalize();
    }

    private boolean isGrounded(@NotNull Player player) {
        Location below = player.getLocation().clone().subtract(0.0D, 0.05D, 0.0D);
        return below.getBlock().getType().isSolid();
    }

    private void cancelWallClingTask(@NotNull UUID playerUuid) {
        BukkitTask task = wallClingTasks.remove(playerUuid);
        if (task != null) {
            task.cancel();
        }
    }
}
