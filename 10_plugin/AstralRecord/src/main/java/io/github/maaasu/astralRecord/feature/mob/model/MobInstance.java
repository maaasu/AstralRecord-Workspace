package io.github.maaasu.astralRecord.feature.mob.model;

import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
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
    private final MobTemplate template;
    private final Location spawnLocation;
    private final Location wanderAnchor;
    private final MobThreatTable threatTable = new MobThreatTable();

    private UUID bukkitEntityId;
    private int entityId = -1;
    private UUID displayEntityId;
    private int displayEntityNumericId = -1;
    private Location currentLocation;
    private double maxHealth;
    private double currentHealth;
    private double currentShield;
    private double shieldDisplayCapacity;
    private ShieldRechargeState shieldRechargeState;
    private double outgoingDamageMultiplier = 1.0D;
    /** 検証用 Mob など、HP が 0 になっても死亡させない実行時フラグ。 */
    private boolean nonLethal;
    /** 描画範囲にプレイヤーがいなくても破棄しない実行時フラグ。 */
    private boolean keepWhenUnobserved;
    /** ガイド等の一時表示で発光させる実行時フラグ。 */
    private boolean glowing;
    private MobState state = MobState.IDLE;
    private UUID targetId;
    private UUID lastAttackerUuid;
    private long lastAttackTick;
    private int nextCombatSkillIndex;
    private String castingSkillName;
    private long castingStartedAtMs;
    private long castingDurationTicks;
    private long castingRemainingTicks;
    /** 専用スキルが移動・停止を直接制御している間は true。 */
    private boolean scriptedAction;

    // ナビゲーション状態
    /** 現在の経路ウェイポイントリスト。null の場合は未計算。 */
    private List<Location> navPath;
    /** 次に向かうウェイポイントのインデックス。 */
    private int navPathIndex;
    /** 最後に経路計算したターゲットの X 座標。 */
    private double navTargetX;
    /** 最後に経路計算したターゲットの Y 座標。 */
    private double navTargetY;
    /** 最後に経路計算したターゲットの Z 座標。 */
    private double navTargetZ;
    /** 最後に経路を再計算した内部 tick（レート制限用）。 */
    private long navRecomputeTick = -1000L;
    /** 移動が詰まり始めた内部 tick。詰まりなしは -1。 */
    private long navBlockedSinceTick = -1L;
    /** 移動進捗を最後に観測した位置。 */
    private Location navLastObservedLocation;
    /** Vex が現在の経路を追従するときの 1 tick あたり速度。 */
    private double navFlightSpeed;
    /** Vex の直接速度を次の経路追従 tick まで保護するか。 */
    private boolean navDirectVelocityOverride;
    /** WANDER 行動時の現在の徘徊目的地。 */
    private Location wanderTarget;
    /** WANDER 停止を解除する内部 tick。 */
    private long wanderPauseUntilTick;
    /** WANDER の緊急テレポートを最後に実行した内部 tick。未実行は -1。 */
    private long lastWanderTeleportTick = -1L;

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
     * @param template      元テンプレート
     * @param spawnLocation スポーン位置（leash 判定の基準）
     */
    public MobInstance(@NotNull UUID instanceId, @NotNull MobTemplate template, @NotNull Location spawnLocation) {
        this.instanceId = instanceId;
        this.template = template;
        this.spawnLocation = spawnLocation.clone();
        this.wanderAnchor = spawnLocation.clone();
        this.currentLocation = spawnLocation.clone();
        this.maxHealth = Math.max(1.0D, template.statValue("MAX_HEALTH", 1.0D));
        this.currentHealth = maxHealth;
        this.currentShield = template.shield().active() ? template.shield().max() : 0.0D;
        this.shieldDisplayCapacity = this.currentShield;
    }

    /** インスタンス ID を返します。 */
    @NotNull
    public UUID instanceId() {
        return instanceId;
    }

    /** Bukkit Entity ID を返します。未紐付けの場合は {@code -1}。 */
    public int entityId() {
        return entityId;
    }

    /** Bukkit Entity UUID を返します。未紐付けの場合は {@code null}。 */
    @Nullable
    public UUID bukkitEntityId() {
        return bukkitEntityId;
    }

    /**
     * 実体 Mob とインスタンスを紐付けます。
     *
     * @param bukkitEntityId Bukkit Entity UUID
     * @param entityId       Bukkit Entity ID
     * @param location       紐付け時点の実体位置
     */
    public void bindEntity(@NotNull UUID bukkitEntityId, int entityId, @NotNull Location location) {
        this.bukkitEntityId = bukkitEntityId;
        this.entityId = entityId;
        this.currentLocation = location.clone();
        this.headYaw = location.getYaw();
        this.headPitch = location.getPitch();
    }

    /** BlockDisplay などの補助 Entity UUID を返します。未使用時は {@code null} です。 */
    @Nullable
    public UUID displayEntityId() {
        return displayEntityId;
    }

    /** BlockDisplay などの補助 Entity ID を返します。未使用時は {@code -1} です。 */
    public int displayEntityNumericId() {
        return displayEntityNumericId;
    }

    /**
     * 補助表示 Entity を紐付けます。
     *
     * @param displayEntityId      補助 Entity UUID
     * @param displayEntityNumericId 補助 Entity ID
     */
    public void bindDisplayEntity(@NotNull UUID displayEntityId, int displayEntityNumericId) {
        this.displayEntityId = displayEntityId;
        this.displayEntityNumericId = displayEntityNumericId;
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
     * 指定量の HP を上限まで回復します。
     *
     * @param amount 回復量。正の有限値以外は無視
     * @return 実際に増加した HP
     */
    public double recoverHealth(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0D || state == MobState.DEAD || !Double.isFinite(currentHealth)) {
            return 0.0D;
        }
        double before = Math.max(0.0D, Math.min(currentHealth, maxHealth));
        double after = Math.min(maxHealth, before + amount);
        double recovered = after - before;
        currentHealth = after;
        return recovered;
    }

    /** この個体に適用済みの実効最大 HP を返します。 */
    public double maxHealth() {
        return maxHealth;
    }

    /**
     * この個体の実効最大 HP を更新します。
     *
     * @param value 新しい実効最大 HP
     */
    public void maxHealth(double value) {
        this.maxHealth = Math.max(1.0D, value);
        this.currentHealth = Math.min(currentHealth, maxHealth);
    }

    /**
     * 現在 HP を更新します。
     *
     * @param value 新しい HP 値
     */
    public void currentHealth(double value) {
        this.currentHealth = value;
    }

    /**
     * 攻撃側ダメージ倍率を返します。
     *
     * @return 攻撃側ダメージ倍率
     */
    public double outgoingDamageMultiplier() {
        return outgoingDamageMultiplier;
    }

    /**
     * 攻撃側ダメージ倍率を更新します。
     *
     * @param value 新しい倍率。0 未満は 0 に丸める
     */
    public void outgoingDamageMultiplier(double value) {
        this.outgoingDamageMultiplier = Math.max(0.0D, value);
    }

    /**
     * この個体を非致死に扱うか返します。
     *
     * @return 非致死なら true
     */
    public boolean nonLethal() {
        return nonLethal;
    }

    /**
     * この個体の非致死扱いを設定します。
     *
     * @param value HP を 1 未満にしない場合は true
     */
    public void nonLethal(boolean value) {
        this.nonLethal = value;
    }

    /**
     * 視認者がいない場合にも維持するか返します。
     *
     * @return 維持対象なら true
     */
    public boolean keepWhenUnobserved() {
        return keepWhenUnobserved;
    }

    /**
     * 視認者がいない場合にも維持するか設定します。
     *
     * @param value 維持対象なら true
     */
    public void keepWhenUnobserved(boolean value) {
        this.keepWhenUnobserved = value;
    }

    /** この個体を発光表示するか返します。 */
    public boolean glowing() {
        return glowing;
    }

    /**
     * この個体の発光表示を設定します。
     *
     * @param value 発光させる場合は {@code true}
     */
    public void glowing(boolean value) {
        this.glowing = value;
    }

    /** 現在シールド値を返します。 */
    public double currentShield() {
        return currentShield;
    }

    /**
     * 現在シールド値を更新します。
     *
     * @param value 新しいシールド値
     * @param currentTimeMs 更新時刻。互換引数であり値は保持しない
     */
    public void currentShield(double value, long currentTimeMs) {
        this.currentShield = template.shield().active()
                ? Math.clamp(value, 0.0D, Math.max(0.0D, shieldDisplayCapacity))
                : 0.0D;
    }

    /**
     * 現在周期の通常シールドバーで満タンとして扱う値を返します。
     *
     * @return 0 以上の表示基準値
     */
    public double shieldDisplayCapacity() {
        return shieldDisplayCapacity;
    }

    /**
     * 現在のリチャージ状態を返します。
     *
     * @return リチャージ中の状態。通常時は {@code null}
     */
    public @Nullable ShieldRechargeState shieldRechargeState() {
        return shieldRechargeState;
    }

    /**
     * シールド破壊時点の時間を固定してリチャージを開始します。
     *
     * @param nowMs 開始時刻（epoch milliseconds）
     * @param durationMs 短縮適用済みの待機ミリ秒
     * @return リチャージを開始した場合は {@code true}
     */
    public boolean startShieldRecharge(long nowMs, long durationMs) {
        if (!template.shield().rechargeable() || currentShield > 0.0D || shieldRechargeState != null) {
            return false;
        }
        shieldRechargeState = new ShieldRechargeState(
                nowMs,
                saturatingAdd(nowMs, Math.max(0L, durationMs)),
                template.shield().resolvedRechargeAmount()
        );
        return true;
    }

    /**
     * 進行中のリチャージへ待機時間を追加します。
     *
     * @param additionalMs 短縮適用済みの追加ミリ秒
     * @return リチャージ中に追加できた場合は {@code true}
     */
    public boolean extendShieldRecharge(long additionalMs) {
        if (shieldRechargeState == null || additionalMs <= 0L) {
            return false;
        }
        shieldRechargeState = shieldRechargeState.extendedBy(additionalMs);
        return true;
    }

    /**
     * 完了時刻を過ぎていれば設定量を一括回復し、通常シールドへ戻します。
     *
     * @param nowMs 判定時刻（epoch milliseconds）
     * @return 今回完了した場合は {@code true}
     */
    public boolean completeShieldRechargeIfReady(long nowMs) {
        ShieldRechargeState state = shieldRechargeState;
        if (state == null || state.remainingMs(nowMs) > 0L) {
            return false;
        }
        currentShield = Math.max(0.0D, state.rechargeAmount());
        shieldDisplayCapacity = currentShield;
        shieldRechargeState = null;
        return true;
    }

    private long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
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

    /** 専用スキルが移動・停止を直接制御している間は {@code true} を返します。 */
    public boolean scriptedAction() {
        return scriptedAction;
    }

    /**
     * 専用スキルによる移動・停止制御の有効状態を設定します。
     *
     * @param value 有効にする場合は {@code true}
     */
    public void scriptedAction(boolean value) {
        this.scriptedAction = value;
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

    /** 最後にこの Mob へ有効ダメージを与えたプレイヤー UUID を返します。 */
    @Nullable
    public UUID lastAttackerUuid() {
        return lastAttackerUuid;
    }

    /**
     * 最後にこの Mob へ有効ダメージを与えたプレイヤー UUID を更新します。
     *
     * @param lastAttackerUuid 攻撃者 UUID
     */
    public void lastAttackerUuid(@Nullable UUID lastAttackerUuid) {
        this.lastAttackerUuid = lastAttackerUuid;
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

    /** 次に発動する戦闘スキルのインデックスを返します。 */
    public int nextCombatSkillIndex() {
        return nextCombatSkillIndex;
    }

    /**
     * 次に発動する戦闘スキルのインデックスを更新します。
     *
     * @param index 新しいインデックス
     */
    public void nextCombatSkillIndex(int index) {
        this.nextCombatSkillIndex = Math.max(0, index);
    }

    /** 詠唱中かどうかを返します。 */
    public boolean isSkillCasting() {
        return castingSkillName != null && castingDurationTicks > 0L && castingRemainingTicks > 0L;
    }

    /** 詠唱中スキル名を返します。詠唱中でなければ {@code null}。 */
    @Nullable
    public String castingSkillName() {
        return castingSkillName;
    }

    /** 詠唱開始時刻（ミリ秒）を返します。 */
    public long castingStartedAtMs() {
        return castingStartedAtMs;
    }

    /** 詠唱総 tick 数を返します。 */
    public long castingDurationTicks() {
        return castingDurationTicks;
    }

    /** 詠唱残り tick 数を返します。 */
    public long castingRemainingTicks() {
        return castingRemainingTicks;
    }

    /**
     * Mob の詠唱表示状態を開始します。
     *
     * @param skillName     表示するスキル名
     * @param durationTicks 詠唱総 tick 数
     */
    public void startSkillCasting(@NotNull String skillName, long durationTicks) {
        this.castingSkillName = skillName;
        this.castingStartedAtMs = System.currentTimeMillis();
        this.castingDurationTicks = Math.max(0L, durationTicks);
        this.castingRemainingTicks = this.castingDurationTicks;
    }

    /**
     * Mob の詠唱残り tick 数を更新します。
     *
     * @param remainingTicks 残り tick 数
     */
    public void updateSkillCastingRemaining(long remainingTicks) {
        this.castingRemainingTicks = Math.max(0L, remainingTicks);
    }

    /** Mob の詠唱表示状態を解除します。 */
    public void clearSkillCasting() {
        this.castingSkillName = null;
        this.castingStartedAtMs = 0L;
        this.castingDurationTicks = 0L;
        this.castingRemainingTicks = 0L;
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

    /** 最後に経路計算したターゲットの Y 座標を返します。 */
    public double navTargetY() {
        return navTargetY;
    }

    /**
     * 最後に経路計算したターゲットの Y 座標を設定します。
     *
     * @param y Y 座標
     */
    public void navTargetY(double y) {
        this.navTargetY = y;
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

    /** 移動が詰まり始めた内部 tick を返します。 */
    public long navBlockedSinceTick() {
        return navBlockedSinceTick;
    }

    /**
     * 移動が詰まり始めた内部 tick を設定します。
     *
     * @param tick 詰まり始めた tick。詰まりなしは -1
     */
    public void navBlockedSinceTick(long tick) {
        this.navBlockedSinceTick = tick;
    }

    /**
     * 移動進捗を最後に観測した位置を返します。
     *
     * @return 最後に観測した位置。未観測の場合は {@code null}
     */
    @Nullable
    public Location navLastObservedLocation() {
        return navLastObservedLocation == null ? null : navLastObservedLocation.clone();
    }

    /**
     * 移動進捗を最後に観測した位置を設定します。
     *
     * @param location 観測位置。{@code null} で未観測状態に戻す
     */
    public void navLastObservedLocation(@Nullable Location location) {
        this.navLastObservedLocation = location == null ? null : location.clone();
    }

    /** Vex の現在の経路追従速度を返します。 */
    public double navFlightSpeed() {
        return navFlightSpeed;
    }

    /**
     * Vex の現在の経路追従速度を設定します。
     *
     * @param speed 1 tick あたりの速度。負値は 0 に丸める
     */
    public void navFlightSpeed(double speed) {
        this.navFlightSpeed = Math.max(0.0D, speed);
    }

    /** Vex の直接速度が経路追従より優先されている場合は {@code true}。 */
    public boolean navDirectVelocityOverride() {
        return navDirectVelocityOverride;
    }

    /**
     * Vex の直接速度を次の経路追従 tick まで優先するか設定します。
     *
     * @param override 直接速度を優先する場合は {@code true}
     */
    public void navDirectVelocityOverride(boolean override) {
        this.navDirectVelocityOverride = override;
    }

    /**
     * Vex の飛行経路だけをリセットします。
     *
     * <p>WANDER NPC の詰まり検知状態は維持し、部分経路の完了や衝突によって
     * 配置アンカーへの復帰判定が先送りされないようにします。</p>
     */
    public void clearVexFlightPath() {
        this.navPath = null;
        this.navPathIndex = 0;
        this.navFlightSpeed = 0.0D;
        this.navDirectVelocityOverride = false;
    }

    /** 現在の経路をリセットします（次 tick で再計算される）。 */
    public void clearNavPath() {
        clearVexFlightPath();
        this.navBlockedSinceTick = -1L;
        this.navLastObservedLocation = null;
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

    /** WANDER 停止を解除する内部 tick を返します。 */
    public long wanderPauseUntilTick() {
        return wanderPauseUntilTick;
    }

    /**
     * WANDER 停止を解除する内部 tick を設定します。
     *
     * @param tick 停止解除 tick
     */
    public void wanderPauseUntilTick(long tick) {
        this.wanderPauseUntilTick = tick;
    }

    /** WANDER の緊急テレポートを最後に実行した内部 tick を返します。 */
    public long lastWanderTeleportTick() {
        return lastWanderTeleportTick;
    }

    /**
     * WANDER の緊急テレポートを最後に実行した内部 tick を設定します。
     *
     * @param tick 実行した内部 tick。未実行状態は {@code -1}
     */
    public void lastWanderTeleportTick(long tick) {
        this.lastWanderTeleportTick = tick;
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
