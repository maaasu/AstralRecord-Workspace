package io.github.maaasu.astralRecord.feature.mob.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * サーバー上に生成された Mob の実体インスタンス。
 *
 * <p>テンプレート {@link MobTemplate} と異なり、本クラスはセッション中に変動するステートを保持するため、
 * 不変な record ではなく可変クラスとして定義する。</p>
 */
public final class MobInstance {

    private final UUID instanceId;
    private final int entityId;
    private final MobTemplate template;
    private final Location spawnLocation;
    private final Location wanderAnchor;
    private final MobThreatTable threatTable = new MobThreatTable();

    private Location currentLocation;
    private double currentHealth;
    private MobState state = MobState.IDLE;
    private UUID targetId;
    private long lastAttackTick;

    // ナビゲーション状態
    /** 現在の経路ウェイポイントリスト。null の場合は未計算。 */
    private List<Location> navPath;
    /** 次に向かうウェイポイントのインデックス。 */
    private int navPathIndex;
    /** 最後に経路計算したターゲットの X 座標。 */
    private double navTargetX;
    /** 最後に経路計算したターゲットの Z 座標。 */
    private double navTargetZ;
    /** 最後に経路を再計算した内部 tick（レート制限用）。 */
    private long navRecomputeTick = -1000L;
    /** WANDER 行動時の現在の徘徊目的地。 */
    private Location wanderTarget;

    // 頭部の向き（プレイヤーへの追跡用）
    /** 頭部の yaw（度）。体と独立してプレイヤー方向を向く。 */
    private float headYaw;
    /** 頭部の pitch（度）。 */
    private float headPitch;

    /**
     * インスタンスを生成します。初期 HP は {@code MAX_HEALTH} のベースステータス値、
     * 状態は {@link MobState#IDLE} となります。
     *
     * @param instanceId    一意なインスタンス ID
     * @param entityId      パケット送出用の仮想 Entity ID
     * @param template      元テンプレート
     * @param spawnLocation スポーン位置（leash 判定の基準）
     */
    public MobInstance(@NotNull UUID instanceId, int entityId, @NotNull MobTemplate template, @NotNull Location spawnLocation) {
        this.instanceId = instanceId;
        this.entityId = entityId;
        this.template = template;
        this.spawnLocation = spawnLocation.clone();
        this.wanderAnchor = spawnLocation.clone();
        this.currentLocation = spawnLocation.clone();
        this.currentHealth = template.statValue("MAX_HEALTH", 1.0);
    }

    /** インスタンス ID を返します。 */
    @NotNull
    public UUID instanceId() {
        return instanceId;
    }

    /** 仮想 Entity ID を返します。 */
    public int entityId() {
        return entityId;
    }

    /** 元テンプレートを返します。 */
    @NotNull
    public MobTemplate template() {
        return template;
    }

    /** スポーン位置を返します（変更不可なコピー）。 */
    @NotNull
    public Location spawnLocation() {
        return spawnLocation.clone();
    }

    /** WANDER 時の中心座標を返します（変更不可なコピー）。 */
    @NotNull
    public Location wanderAnchor() {
        return wanderAnchor.clone();
    }

    /** 現在位置を返します（変更不可なコピー）。 */
    @NotNull
    public Location currentLocation() {
        return currentLocation.clone();
    }

    /**
     * 現在位置を更新します。
     *
     * @param location 新しい位置
     */
    public void currentLocation(@NotNull Location location) {
        this.currentLocation = location.clone();
    }

    /** 現在 HP を返します。 */
    public double currentHealth() {
        return currentHealth;
    }

    /**
     * 現在 HP を更新します。
     *
     * @param value 新しい HP 値
     */
    public void currentHealth(double value) {
        this.currentHealth = value;
    }

    /** 状態を返します。 */
    @NotNull
    public MobState state() {
        return state;
    }

    /**
     * 状態を更新します。
     *
     * @param state 新しい状態
     */
    public void state(@NotNull MobState state) {
        this.state = state;
    }

    /** 現ターゲットのプレイヤー UUID を返します。 */
    @Nullable
    public UUID targetId() {
        return targetId;
    }

    /**
     * 現ターゲットを更新します。
     *
     * @param targetId 新しいターゲット UUID（{@code null} で解除）
     */
    public void targetId(@Nullable UUID targetId) {
        this.targetId = targetId;
    }

    /** 最終攻撃 tick を返します。 */
    public long lastAttackTick() {
        return lastAttackTick;
    }

    /**
     * 最終攻撃 tick を更新します。
     *
     * @param tick 新しい tick 値
     */
    public void lastAttackTick(long tick) {
        this.lastAttackTick = tick;
    }

    /** 脅威値テーブルを返します。 */
    @NotNull
    public MobThreatTable threatTable() {
        return threatTable;
    }

    // ---------- ナビゲーション ----------

    /** 現在の経路ウェイポイントリストを返します。未計算の場合は {@code null}。 */
    @Nullable
    public List<Location> navPath() {
        return navPath;
    }

    /**
     * 経路ウェイポイントリストを設定します。
     *
     * @param path ウェイポイントリスト。{@code null} で未計算状態にリセット
     */
    public void navPath(@Nullable List<Location> path) {
        this.navPath = path;
    }

    /** 次のウェイポイントインデックスを返します。 */
    public int navPathIndex() {
        return navPathIndex;
    }

    /**
     * 次のウェイポイントインデックスを設定します。
     *
     * @param index 新しいインデックス
     */
    public void navPathIndex(int index) {
        this.navPathIndex = index;
    }

    /** 最後に経路計算したターゲットの X 座標を返します。 */
    public double navTargetX() {
        return navTargetX;
    }

    /**
     * 最後に経路計算したターゲットの X 座標を設定します。
     *
     * @param x X 座標
     */
    public void navTargetX(double x) {
        this.navTargetX = x;
    }

    /** 最後に経路計算したターゲットの Z 座標を返します。 */
    public double navTargetZ() {
        return navTargetZ;
    }

    /**
     * 最後に経路計算したターゲットの Z 座標を設定します。
     *
     * @param z Z 座標
     */
    public void navTargetZ(double z) {
        this.navTargetZ = z;
    }

    /** 最後に経路を再計算した内部 tick を返します。 */
    public long navRecomputeTick() {
        return navRecomputeTick;
    }

    /**
     * 最後に経路を再計算した内部 tick を設定します。
     *
     * @param tick 内部 tick
     */
    public void navRecomputeTick(long tick) {
        this.navRecomputeTick = tick;
    }

    /** 現在の経路をリセットします（次 tick で再計算される）。 */
    public void clearNavPath() {
        this.navPath = null;
        this.navPathIndex = 0;
    }

    /** WANDER 時の現在の徘徊目的地を返します。未設定なら {@code null}。 */
    @Nullable
    public Location wanderTarget() {
        return wanderTarget;
    }

    /**
     * WANDER 時の徘徊目的地を設定します。
     *
     * @param target 目的地（{@code null} で未設定）
     */
    public void wanderTarget(@Nullable Location target) {
        this.wanderTarget = target;
    }

    // ---------- 頭部の向き ----------

    /** 頭部の yaw（度）を返します。 */
    public float headYaw() {
        return headYaw;
    }

    /**
     * 頭部の yaw（度）を設定します。
     *
     * @param yaw 頭部 yaw
     */
    public void headYaw(float yaw) {
        this.headYaw = yaw;
    }

    /** 頭部の pitch（度）を返します。 */
    public float headPitch() {
        return headPitch;
    }

    /**
     * 頭部の pitch（度）を設定します。
     *
     * @param pitch 頭部 pitch
     */
    public void headPitch(float pitch) {
        this.headPitch = pitch;
    }
}
