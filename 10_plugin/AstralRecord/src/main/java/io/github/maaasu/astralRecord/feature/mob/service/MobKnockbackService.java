package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

/**
 * 攻撃方向に対するノックバックベクトルを算出し、対象（プレイヤー / Mob）へ送出する。
 *
 * <p>ノックバック量は {@code StatusType.KNOCKBACK_RESISTANCE} で軽減し、
 * ダメージ処理後の共通経路から適用する。同一対象への連続適用には短い受付間隔を設ける。</p>
 */
public class MobKnockbackService {

    /** 水平ノックバックの基本量（ブロック単位の速度）。 */
    private static final double DEFAULT_HORIZONTAL = 0.4;

    /** 垂直ノックバックの基本量。 */
    private static final double DEFAULT_VERTICAL = 0.4;

    /** 同一対象へ再度ノックバックを適用できるまでのtick数。 */
    static final long KNOCKBACK_COOLDOWN_TICKS = 4L;

    /** 期限切れ状態を掃除する間隔。 */
    private static final long COOLDOWN_CLEANUP_INTERVAL_TICKS = 20L;

    private final MobService mobService;
    private final LongSupplier currentTick;
    private final Map<UUID, Long> nextAvailableTickByTarget = new HashMap<>();
    private long lastCooldownCleanupTick = Long.MIN_VALUE;
    private ToDoubleFunction<AstEntity> additionalKnockbackMultiplier = ignored -> 1.0D;

    /**
     * ノックバックサービスを初期化します。
     *
     * @param mobService 実体 Mob への速度反映に使用する Mob サービス
     */
    public MobKnockbackService(@NotNull MobService mobService) {
        this(mobService, () -> System.currentTimeMillis() / 50L);
    }

    /**
     * ノックバックサービスを、サーバーtickを返す時計付きで初期化します。
     *
     * @param mobService  実体 Mob への速度反映に使用する Mob サービス
     * @param currentTick 現在のサーバーtickを返す時計
     */
    public MobKnockbackService(
            @NotNull MobService mobService,
            @NotNull LongSupplier currentTick
    ) {
        this.mobService = Objects.requireNonNull(mobService, "mobService");
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
    }

    /**
     * 状態値以外の一時ノックバック倍率を設定します。
     *
     * @param multiplier 対象ごとの追加倍率
     */
    public void setAdditionalKnockbackMultiplier(
            @NotNull ToDoubleFunction<AstEntity> multiplier
    ) {
        this.additionalKnockbackMultiplier = multiplier;
    }

    /**
     * ダメージ処理から呼び出す共通ノックバックを適用します。
     *
     * @param source     攻撃元
     * @param target     被弾対象
     * @param multiplier ノックバック倍率
     */
    public void apply(@NotNull AstEntity source, @NotNull AstEntity target, double multiplier) {
        double effectiveMultiplier = multiplier * resistanceScale(target);
        if (effectiveMultiplier <= 0.0D) {
            return;
        }
        if (target.isPlayer() && target.player() != null) {
            if (!tryAcquire(target.id())) {
                return;
            }
            applyToPlayerWithoutCooldown(source.location(), target.player().getBukkit(), effectiveMultiplier);
            return;
        }
        if (target.isMob() && target.mob() != null) {
            if (!tryAcquire(target.id())) {
                return;
            }
            applyToMobWithoutCooldown(source.location(), target.mob(), effectiveMultiplier);
        }
    }

    /**
     * スキルなどが指定した強さで、対象のノックバック耐性を考慮した速度を加算します。
     *
     * @param target             被弾対象
     * @param sourceLocation     押し出し元の位置
     * @param horizontalStrength 水平方向の強さ
     * @param verticalStrength   垂直方向の強さ
     */
    public void applyWithStrength(
            @NotNull AstEntity target,
            @NotNull Location sourceLocation,
            double horizontalStrength,
            double verticalStrength
    ) {
        double scale = resistanceScale(target);
        if (scale <= 0.0D) {
            return;
        }
        Vector velocity = computeVelocity(
                sourceLocation,
                target.location(),
                Math.max(0.0D, horizontalStrength) * scale,
                Math.max(0.0D, verticalStrength) * scale
        );
        if (target.isPlayer() && target.player() != null) {
            if (!tryAcquire(target.id())) {
                return;
            }
            Player player = target.player().getBukkit();
            player.setVelocity(player.getVelocity().add(velocity));
            return;
        }
        if (target.isMob() && target.mob() != null) {
            if (!tryAcquire(target.id())) {
                return;
            }
            mobService.entityController().addVelocity(target.mob(), velocity);
        }
    }

    /**
     * 攻撃元 -> 対象プレイヤーへのノックバックを適用します。
     *
     * @param sourceLocation 攻撃元の位置
     * @param target         被攻撃側プレイヤー
     * @param multiplier     ノックバック倍率（既定 1.0）
     */
    public void applyToPlayer(@NotNull Location sourceLocation, @NotNull Player target, double multiplier) {
        if (!tryAcquire(target.getUniqueId())) {
            return;
        }
        applyToPlayerWithoutCooldown(sourceLocation, target, multiplier);
    }

    private void applyToPlayerWithoutCooldown(
            @NotNull Location sourceLocation,
            @NotNull Player target,
            double multiplier
    ) {
        Vector velocity = computeVelocity(sourceLocation, target.getLocation(), multiplier);
        target.setVelocity(target.getVelocity().add(velocity));
    }

    /**
     * 攻撃元 -> 対象 Mob へのノックバックを適用します。Mob 側は実体 Entity の速度へ反映します。
     *
     * @param sourceLocation 攻撃元の位置
     * @param target         被攻撃側 Mob
     * @param multiplier     ノックバック倍率
     */
    public void applyToMob(@NotNull Location sourceLocation, @NotNull MobInstance target, double multiplier) {
        if (!tryAcquire(target.instanceId())) {
            return;
        }
        applyToMobWithoutCooldown(sourceLocation, target, multiplier);
    }

    private void applyToMobWithoutCooldown(
            @NotNull Location sourceLocation,
            @NotNull MobInstance target,
            double multiplier
    ) {
        Vector velocity = computeVelocity(sourceLocation, target.currentLocation(), multiplier);
        mobService.entityController().addVelocity(target, velocity);
    }

    private boolean tryAcquire(@NotNull UUID targetId) {
        long tick = currentTick.getAsLong();
        cleanupExpiredCooldowns(tick);
        Long nextAvailableTick = nextAvailableTickByTarget.get(targetId);
        if (nextAvailableTick != null && tick < nextAvailableTick) {
            return false;
        }
        nextAvailableTickByTarget.put(targetId, tick + KNOCKBACK_COOLDOWN_TICKS);
        return true;
    }

    private void cleanupExpiredCooldowns(long currentTick) {
        if (lastCooldownCleanupTick != Long.MIN_VALUE
                && currentTick - lastCooldownCleanupTick < COOLDOWN_CLEANUP_INTERVAL_TICKS) {
            return;
        }
        lastCooldownCleanupTick = currentTick;
        nextAvailableTickByTarget.entrySet().removeIf(entry -> entry.getValue() <= currentTick);
    }

    /**
     * 攻撃元から対象への水平正規化ベクトルに、垂直成分を加えたノックバックベクトルを計算します。
     *
     * @param source     攻撃元位置
     * @param target     対象位置
     * @param multiplier 倍率
     * @return ノックバックベクトル
     */
    @NotNull
    private Vector computeVelocity(@NotNull Location source, @NotNull Location target, double multiplier) {
        return computeVelocity(
                source,
                target,
                DEFAULT_HORIZONTAL * multiplier,
                DEFAULT_VERTICAL * multiplier
        );
    }

    private Vector computeVelocity(
            @NotNull Location source,
            @NotNull Location target,
            double horizontalStrength,
            double verticalStrength
    ) {
        double dx = target.getX() - source.getX();
        double dz = target.getZ() - source.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6) {
            // 同位置なら水平成分なし、垂直のみ
            return new Vector(0.0, verticalStrength, 0.0);
        }

        double inv = 1.0 / length;
        double vx = dx * inv * horizontalStrength;
        double vz = dz * inv * horizontalStrength;
        double vy = verticalStrength;
        return new Vector(vx, vy, vz);
    }

    private double resistanceScale(@NotNull AstEntity target) {
        double resistance = Math.clamp(target.statValue(StatusType.KNOCKBACK_RESISTANCE), 0.0D, 100.0D);
        double additionalMultiplier = Math.max(0.0D, additionalKnockbackMultiplier.applyAsDouble(target));
        return (1.0D - resistance / 100.0D) * additionalMultiplier;
    }
}
