package io.github.maaasu.astralRecord.feature.mob.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
}
