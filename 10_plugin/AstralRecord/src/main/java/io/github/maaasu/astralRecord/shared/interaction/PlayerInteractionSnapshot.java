package io.github.maaasu.astralRecord.shared.interaction;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Bukkit イベントを候補 resolver が共通利用できる形へ正規化した読み取り専用 snapshot です。
 *
 * @param player 入力元プレイヤー
 * @param event 元の Bukkit イベント
 * @param hand 入力 hand。hand を持たない入口では null
 * @param action PlayerInteractEvent の action。その他の入口では null
 * @param targetEntity イベントが直接示す entity。存在しない場合は null
 * @param clickedBlock イベントが直接示す block。存在しない場合は null
 * @param blockFace block の操作面。存在しない場合は null
 * @param willAttack vanilla entity 攻撃が成立する入口なら true
 * @param ray 共通の視線 ray
 * @param blockingDistance 最初の遮蔽 block までの距離。遮蔽なしは ray 最大距離
 */
public record PlayerInteractionSnapshot(
    Player player,
    Event event,
    @Nullable EquipmentSlot hand,
    @Nullable Action action,
    @Nullable Entity targetEntity,
    @Nullable Block clickedBlock,
    @Nullable BlockFace blockFace,
    boolean willAttack,
    PlayerInteractionRayTrace ray,
    double blockingDistance
) {
    private static final double MAX_RAY_DISTANCE = 8.0D;
    private static final double VISIBILITY_EPSILON = 1.0E-6D;

    /**
     * snapshot を生成します。
     */
    public PlayerInteractionSnapshot {
        player = Objects.requireNonNull(player, "player");
        event = Objects.requireNonNull(event, "event");
        ray = Objects.requireNonNull(ray, "ray");
        if (!Double.isFinite(blockingDistance) || blockingDistance < 0.0D) {
            throw new IllegalArgumentException("blockingDistance must be finite and zero or greater");
        }
    }

    /**
     * プレイヤーの現在視線から snapshot を生成します。
     *
     * @param player 入力元プレイヤー
     * @param event 元イベント
     * @param hand 入力 hand
     * @param action interact action
     * @param targetEntity 直接対象 entity
     * @param clickedBlock 直接対象 block
     * @param blockFace 操作面
     * @param willAttack vanilla entity 攻撃成立可否
     * @return 正規化 snapshot
     * @throws IllegalArgumentException 視線方向が無効な場合
     */
    public static @NotNull PlayerInteractionSnapshot create(
        @NotNull Player player,
        @NotNull Event event,
        @Nullable EquipmentSlot hand,
        @Nullable Action action,
        @Nullable Entity targetEntity,
        @Nullable Block clickedBlock,
        @Nullable BlockFace blockFace,
        boolean willAttack
    ) {
        Location eye = player.getEyeLocation();
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
            eye.toVector(),
            eye.getDirection(),
            MAX_RAY_DISTANCE
        );
        if (ray == null) {
            throw new IllegalArgumentException("player eye direction must form a valid ray");
        }
        RayTraceResult blockHit = player.getWorld().rayTraceBlocks(
            eye,
            ray.direction(),
            ray.maxDistance(),
            FluidCollisionMode.NEVER,
            true
        );
        double blockingDistance = blockHit == null || blockHit.getHitPosition() == null
            ? ray.maxDistance()
            : eye.toVector().distance(blockHit.getHitPosition());
        return new PlayerInteractionSnapshot(
            player,
            event,
            hand,
            action,
            targetEntity,
            clickedBlock,
            blockFace,
            willAttack,
            ray,
            Math.min(ray.maxDistance(), blockingDistance)
        );
    }

    /**
     * 同じ入力メタデータを保ち、プレイヤーの現在位置・視線・遮蔽状態でray情報を再生成します。
     * 遅延executorの実行直前再検証に使用します。
     *
     * @return 現在状態へ更新したsnapshot
     */
    public @NotNull PlayerInteractionSnapshot refresh() {
        return create(player, event, hand, action, targetEntity, clickedBlock, blockFace, willAttack);
    }

    /**
     * 候補の hitDistance が最初の遮蔽物より手前か判定します。
     *
     * @param hitDistance 候補の ray 入口距離
     * @return 視認可能なら true
     */
    public boolean isVisible(double hitDistance) {
        return Double.isFinite(hitDistance)
            && hitDistance >= 0.0D
            && hitDistance <= blockingDistance + VISIBILITY_EPSILON;
    }

    /**
     * entity の AABB 入口までの距離を返します。
     *
     * @param entity 対象 entity
     * @return 命中距離。ray 範囲外なら null
     */
    public @Nullable Double hitDistance(@NotNull Entity entity) {
        return hitDistance(entity, 0.0D);
    }

    /**
     * 指定量だけ拡張したentity AABBの入口までの距離を返します。
     *
     * @param entity 対象entity
     * @param expansion 各方向へのAABB拡張量
     * @return 命中距離。ray範囲外ならnull
     */
    public @Nullable Double hitDistance(@NotNull Entity entity, double expansion) {
        Objects.requireNonNull(entity, "entity");
        if (!Double.isFinite(expansion) || expansion < 0.0D) {
            throw new IllegalArgumentException("expansion must be finite and zero or greater");
        }
        org.bukkit.util.BoundingBox hitBox = entity.getBoundingBox();
        if (expansion > 0.0D) {
            hitBox.expand(expansion);
        }
        return ray.aabbEntryDistance(hitBox);
    }

    /**
     * block の AABB 入口までの距離を返します。
     *
     * @param block 対象 block
     * @return 命中距離。ray 範囲外なら null
     */
    public @Nullable Double hitDistance(@NotNull Block block) {
        return ray.aabbEntryDistance(Objects.requireNonNull(block, "block").getBoundingBox());
    }

    /**
     * 相関用の直接対象キーを返します。
     *
     * @return entity UUID、block 座標、または空文字
     */
    public @NotNull String directTargetKey() {
        if (targetEntity != null) {
            return "entity:" + targetEntity.getUniqueId();
        }
        if (clickedBlock != null) {
            return "block:" + clickedBlock.getWorld().getUID()
                + ":" + clickedBlock.getX()
                + ":" + clickedBlock.getY()
                + ":" + clickedBlock.getZ();
        }
        return "";
    }

    /**
     * メイン hand または hand 非依存の入口か判定します。
     *
     * @return メイン入力なら true
     */
    public boolean isMainHandInput() {
        return hand == null || hand == EquipmentSlot.HAND;
    }

    /**
     * ray の始点コピーを返します。
     *
     * @return ray 始点
     */
    public @NotNull Vector rayOrigin() {
        return ray.origin();
    }
}
