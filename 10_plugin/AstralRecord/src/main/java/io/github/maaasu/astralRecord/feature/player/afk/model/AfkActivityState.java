package io.github.maaasu.astralRecord.feature.player.afk.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーごとのAFK判定に必要な入力状態と基準位置を保持します。
 * <p>
 * 入力を伴わない移動は水流・スキル・転送などによるものとして基準位置だけを更新し、
 * 操作時刻を更新しません。
 */
public final class AfkActivityState {

    /** AFK解除に必要な移動距離の二乗値。 */
    public static final double ACTIVITY_DISTANCE_SQUARED = 1.0D;

    private Location activityAnchor;
    private long lastActivityAtMs;
    private boolean directionalInput;
    private boolean afk;

    /**
     * AFK判定状態を初期化します。
     *
     * @param initialLocation 初期位置
     * @param nowMs 初期化時刻（epoch milliseconds）
     */
    public AfkActivityState(@NotNull Location initialLocation, long nowMs) {
        this.activityAnchor = initialLocation.clone();
        this.lastActivityAtMs = nowMs;
    }

    /**
     * 前後左右入力の押下状態を更新します。
     *
     * @param directionalInput 前後左右のいずれかが押下中なら {@code true}
     */
    public void setDirectionalInput(boolean directionalInput) {
        this.directionalInput = directionalInput;
    }

    /**
     * 移動後の位置を反映し、手動移動によってAFKを解除すべきか判定します。
     *
     * @param location 移動後の位置
     * @param teleport 転送イベントなら {@code true}
     * @param nowMs 判定時刻（epoch milliseconds）
     * @return 前後左右入力を伴う1m以上の移動を検知した場合は {@code true}
     */
    public boolean recordMovement(@NotNull Location location, boolean teleport, long nowMs) {
        if (teleport || !directionalInput || activityAnchor.getWorld() != location.getWorld()) {
            activityAnchor = location.clone();
            return false;
        }
        if (activityAnchor.distanceSquared(location) < ACTIVITY_DISTANCE_SQUARED) {
            return false;
        }
        activityAnchor = location.clone();
        lastActivityAtMs = nowMs;
        return true;
    }

    /**
     * 指定時刻までにAFK判定時間を超えたかを返します。
     *
     * @param nowMs 判定時刻（epoch milliseconds）
     * @param timeoutMs AFK判定時間
     * @return AFKに遷移可能なら {@code true}
     */
    public boolean isInactiveFor(long nowMs, long timeoutMs) {
        return !afk && nowMs - lastActivityAtMs >= timeoutMs;
    }

    /**
     * 現在AFK状態かを返します。
     *
     * @return AFK中なら {@code true}
     */
    public boolean isAfk() {
        return afk;
    }

    /**
     * AFK状態を更新します。
     *
     * @param afk 更新後のAFK状態
     */
    public void setAfk(boolean afk) {
        this.afk = afk;
    }
}
