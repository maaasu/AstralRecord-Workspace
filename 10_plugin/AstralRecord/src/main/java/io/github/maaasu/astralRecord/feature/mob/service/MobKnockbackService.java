package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * 攻撃方向に対するノックバックベクトルを算出し、対象（プレイヤー / Mob）へ送出する。
 *
 * <p>ノックバック量は {@code StatusType.KNOCKBACK_RESISTANCE} で軽減し、
 * ダメージ処理後の共通経路から適用する。</p>
 */
public class MobKnockbackService {

    /** 水平ノックバックの基本量（ブロック単位の速度）。 */
    private static final double DEFAULT_HORIZONTAL = 0.4;

    /** 垂直ノックバックの基本量。 */
    private static final double DEFAULT_VERTICAL = 0.4;

    private final MobService mobService;

    /**
     * ノックバックサービスを初期化します。
     *
     * @param mobService 実体 Mob への速度反映に使用する Mob サービス
     */
    public MobKnockbackService(@NotNull MobService mobService) {
        this.mobService = mobService;
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
            applyToPlayer(source.location(), target.player().getBukkit(), effectiveMultiplier);
            return;
        }
        if (target.isMob() && target.mob() != null) {
            applyToMob(source.location(), target.mob(), effectiveMultiplier);
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
        Vector velocity = computeVelocity(sourceLocation, target.currentLocation(), multiplier);
        mobService.entityController().addVelocity(target, velocity);
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
        double dx = target.getX() - source.getX();
        double dz = target.getZ() - source.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6) {
            // 同位置なら水平成分なし、垂直のみ
            return new Vector(0.0, DEFAULT_VERTICAL * multiplier, 0.0);
        }

        double inv = 1.0 / length;
        double vx = dx * inv * DEFAULT_HORIZONTAL * multiplier;
        double vz = dz * inv * DEFAULT_HORIZONTAL * multiplier;
        double vy = DEFAULT_VERTICAL * multiplier;
        return new Vector(vx, vy, vz);
    }

    private double resistanceScale(@NotNull AstEntity target) {
        double resistance = Math.clamp(target.statValue(StatusType.KNOCKBACK_RESISTANCE), 0.0D, 100.0D);
        return 1.0D - resistance / 100.0D;
    }
}
