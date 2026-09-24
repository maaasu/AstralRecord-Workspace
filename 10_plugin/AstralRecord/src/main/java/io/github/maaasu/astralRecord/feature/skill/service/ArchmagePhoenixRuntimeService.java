package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** アークメイジの不死鳥との共鳴の召喚状態と追従表示を管理します。 */
public final class ArchmagePhoenixRuntimeService {
    public static final double MAX_PHOENIX = 500.0D;
    private static final double DEFAULT_TARGET_RANGE = 50.0D;
    private static final int DEFAULT_DESPAWN_DELAY_TICKS = 600;
    private static final double MAX_FLIGHT_SPEED = 0.65D;
    private static final double MAX_ACCELERATION = 0.065D;
    private static final double RECOVERY_DISTANCE_SQUARED = 24.0D * 24.0D;
    private final ArchmagePhoenixVisuals visuals;
    private final Map<UUID, State> states = new HashMap<>();
    private final Set<UUID> summonedEntityIds = new HashSet<>();

    /**
     * 不死鳥の実行時状態を構築します。
     * @param particles 共通の近傍閲覧者向けパーティクル表示サービス
     */
    public ArchmagePhoenixRuntimeService(@NotNull ParticleDisplayService particles) {
        this.visuals = new ArchmagePhoenixVisuals(particles);
    }

    /**
     * 現在のログイン状態のバインド個体を記録し、未召喚リソースを公開します。
     * アカウント切り替え前のAstPlayerから遅れて届いた有効化は受け付けません。
     * @param context 有効化されたパッシブ個体と解決済みパラメーター
     */
    public void activate(@NotNull PassiveSkillContext context) {
        AstPlayer player = context.player();
        if (player.isPassiveSkillSessionClosed() || AstPlayerCache.get(player.getBukkit()) != player) return;
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        double maximum = params.getDouble("phoenixMax", MAX_PHOENIX);
        double range = params.getDouble("targetRange", DEFAULT_TARGET_RANGE);
        int delay = params.getInt("despawnDelayTicks", DEFAULT_DESPAWN_DELAY_TICKS);
        State state = currentState(player);
        if (state == null) {
            state = new State(player, maximum, range * range, delay);
            states.put(player.getBukkit().getUniqueId(), state);
        }
        state.bindings.add(bindingId(context));
    }

    /**
     * 最後のバインド個体が解除された場合、召喚体を除去します。
     * cache切替後でも自身の旧状態は除去し、別の所有セッションの状態には触りません。
     * @param context 無効化されたパッシブ個体
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        State state = states.get(playerId);
        if (state == null || state.owner != context.player()) return;
        state.bindings.remove(bindingId(context));
        if (state.bindings.isEmpty()) clearPlayer(playerId);
    }

    /**
     * 指定プレイヤーの召喚体と状態を破棄します。
     * @param playerId Bukkit プレイヤー UUID
     */
    public void clearPlayer(@NotNull UUID playerId) {
        State state = states.remove(playerId);
        if (state != null) despawn(state);
    }

    /**
     * 終了するセッションが所有する不死鳥だけを破棄します。遅れた旧セッションの通知は新状態を消しません。
     * @param player 終了対象のAstPlayer。現在のcacheから外れた後でも自身の状態は破棄できます
     */
    public void clearPlayer(@NotNull AstPlayer player) {
        UUID playerId = player.getBukkit().getUniqueId();
        State state = states.get(playerId);
        if (state != null && state.owner == player) clearPlayer(playerId);
    }

    /**
     * 戦闘不能時はバインドを維持したまま召喚体だけを除去します。
     * @param playerId Bukkit プレイヤー UUID
     */
    public void onPlayerDeath(@NotNull UUID playerId) {
        State state = states.get(playerId);
        if (state != null) despawn(state);
    }

    /** Plugin 停止時に全召喚体と状態を除去します。 */
    public void clearAll() {
        for (UUID playerId : List.copyOf(states.keySet())) clearPlayer(playerId);
    }

    /**
     * このサービスが現在所有する召喚体かをメインスレッド上で判定します。
     * ワールド追加前のspawn callbackでも登録するため、生成イベント時点から保護できます。
     * @param entity 管理外Mob抑止が検査する実体
     * @return 生成中または召喚中の不死鳥ならtrue。終了した実体は含みません
     */
    public boolean ownsSummon(@NotNull Entity entity) {
        return summonedEntityIds.contains(entity.getUniqueId());
    }

    /**
     * 通常攻撃またはスキルの確定した敵 Mob 命中を受け取ります。
     * 未召喚時は対象が生存する場合だけ召喚し、召喚中は致死命中も最新対象へ記録します。
     * @param attacker 攻撃者。対象パッシブが有効なプレイヤーだけを処理します
     * @param target HP と死亡状態が確定済みの敵 Mob
     */
    public void onDirectMobHit(@NotNull AstPlayer attacker, @NotNull MobInstance target) {
        State state = currentState(attacker);
        if (state == null || state.bindings.isEmpty()) return;
        boolean alive = target.state() != MobState.DEAD
                && target.currentHealth() > 0.0D
                && targetEntityPresent(target) != null;
        if (state.parrot == null) {
            if (!alive) return;
            spawn(attacker.getBukkit(), state);
            if (state.parrot == null) return;
        }
        state.target = target;
        state.lastInRangeTick = Bukkit.getCurrentTick();
    }

    /**
     * 現在選択中のアカウントに属する不死鳥の値だけをHUDへ渡します。
     * メインスレッドで呼び、旧アカウントの状態が残っていれば召喚体ごと破棄します。
     * @param player 表示対象プレイヤー
     * @return 有効性、現在値、最大値
     */
    public @NotNull PhoenixSnapshot snapshot(@NotNull AstPlayer player) {
        State state = currentState(player);
        return state == null ? new PhoenixSnapshot(false, 0.0D, 0.0D)
                : new PhoenixSnapshot(true, state.parrot == null ? 0.0D : state.maximum, state.maximum);
    }

    /**
     * 有効パッシブの定期処理で、最新対象と召喚体を更新します。
     * @param context 有効なパッシブ個体
     */
    public void tick(@NotNull PassiveSkillContext context) {
        Player player = context.player().getBukkit();
        State state = currentState(context.player());
        if (state == null || state.parrot == null) return;
        long now = Bukkit.getCurrentTick();
        if (state.lastTick == now) return;
        state.lastTick = now;
        if (!player.isOnline()) {
            clearPlayer(player.getUniqueId());
            return;
        }
        if (player.isDead()) {
            onPlayerDeath(player.getUniqueId());
            return;
        }
        if (targetInRange(player, state.target, state.rangeSquared)) state.lastInRangeTick = now;
        if (now - state.lastInRangeTick >= state.delayTicks) {
            despawn(state);
            return;
        }
        if (!state.parrot.isValid()) {
            despawn(state);
            return;
        }
        Location location = follow(player, state, now);
        // 通常移動は毎tickの速度制御、描画は位相を分散した2tick周期に分離する。
        if (location != null && (now + state.visualPhase) % 2L == 0L) {
            visuals.render(player, state.parrot, location, now, state.visualPhase);
        }
    }

    /** 現在のAstPlayerとアカウントUUIDに一致する状態だけを返します。旧callbackは現状態を触りません。 */
    private State currentState(AstPlayer player) {
        Player bukkit = player.getBukkit();
        if (AstPlayerCache.get(bukkit) != player) return null;
        State state = states.get(bukkit.getUniqueId());
        if (state != null && (state.owner != player || !state.accountId.equals(player.getAccount().getUuid())
                || player.isPassiveSkillSessionClosed())) {
            clearPlayer(bukkit.getUniqueId());
            return null;
        }
        return state;
    }

    private boolean targetInRange(Player player, MobInstance target, double rangeSquared) {
        if (target == null || target.state() == MobState.DEAD || target.currentHealth() <= 0.0D) return false;
        Entity entity = targetEntityPresent(target);
        if (entity == null) return false;
        Location location = entity.getLocation();
        return location.getWorld() == player.getWorld()
                && location.distanceSquared(player.getLocation()) <= rangeSquared;
    }

    private Entity targetEntityPresent(MobInstance target) {
        UUID entityId = target.bukkitEntityId();
        Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
        return entity != null && entity.isValid() ? entity : null;
    }

    private void spawn(Player player, State state) {
        Location location = followLocation(player);
        if (location.getWorld() == null) return;
        try {
            Parrot spawned = location.getWorld().spawn(
                    location, Parrot.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, parrot -> {
                        // イベントが発火するワールド追加より前に、所有する実体だけを登録する。
                        state.parrot = parrot;
                        summonedEntityIds.add(parrot.getUniqueId());
                        parrot.setVariant(Parrot.Variant.RED);
                        // NoAIは物理移動自体を止めるため、自律行動だけをawareで止める。
                        parrot.setAI(true);
                        parrot.setAware(false);
                        parrot.setGravity(false);
                        parrot.setSilent(true);
                        parrot.setInvulnerable(true);
                        parrot.setCollidable(false);
                        parrot.setPersistent(false);
                    });
            if (!spawned.isValid()) {
                // 他のイベントハンドラに生成を取り消された場合、保護登録とHP表示を残さない。
                despawn(state);
            } else {
                state.flightVelocity.zero();
                state.flightYaw = location.getYaw();
                state.previousLocation = location.clone();
                state.previousOwnerLocation = player.getLocation();
                state.visualPhase = Math.floorMod(player.getUniqueId().hashCode(), 6);
                state.stalledTicks = 0;
            }
        } catch (RuntimeException exception) {
            despawn(state);
            throw exception;
        }
    }

    /** 視線の上下に影響されない背後上空の追従点を、壁の手前へ補正します。 */
    private Location followLocation(Player player) {
        Location location = player.getLocation();
        location.setPitch(0.0F);
        location.add(location.getDirection().multiply(-1.9D)).add(0.0D, 2.4D, 0.0D);
        Location eye = player.getEyeLocation();
        Vector offset = location.toVector().subtract(eye.toVector());
        double distance = offset.length();
        if (distance > 0.01D) {
            Vector direction = offset.multiply(1.0D / distance);
            var hit = player.getWorld().rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true);
            if (hit != null) {
                double safeDistance = Math.max(0.0D, hit.getHitPosition().distance(eye.toVector()) - 0.6D);
                location = eye.clone().add(direction.multiply(safeDistance));
                location.setYaw(player.getLocation().getYaw());
                location.setPitch(0.0F);
            }
        }
        return location;
    }

    /** 加減速と旋回を制限し、実際の物理座標へ翼を追従させます。 */
    private Location follow(Player player, State state, long now) {
        Location current = state.parrot.getLocation();
        Location anchor = followLocation(player);
        Location ownerLocation = player.getLocation();
        Vector ownerVelocity = state.previousOwnerLocation != null
                && state.previousOwnerLocation.getWorld() == ownerLocation.getWorld()
                ? ownerLocation.toVector().subtract(state.previousOwnerLocation.toVector()) : new Vector();
        state.previousOwnerLocation = ownerLocation.clone();
        limit(ownerVelocity, MAX_FLIGHT_SPEED);
        boolean worldChanged = current.getWorld() != anchor.getWorld();
        double distanceSquared = worldChanged ? Double.POSITIVE_INFINITY : current.distanceSquared(anchor);
        if (!worldChanged && state.previousLocation != null && state.previousLocation.getWorld() == current.getWorld()
                && distanceSquared > 1.0D && state.flightVelocity.lengthSquared() > 0.01D
                && state.previousLocation.distanceSquared(anchor) - distanceSquared < 0.02D) {
            state.stalledTicks++;
        } else {
            state.stalledTicks = 0;
        }
        state.previousLocation = current.clone();
        boolean discontinuity = worldChanged || distanceSquared > RECOVERY_DISTANCE_SQUARED;
        if (discontinuity || state.stalledTicks >= 40) {
            long retryDelay = discontinuity ? 20L : 100L;
            if (now - state.lastRecoveryTick >= retryDelay) {
                state.lastRecoveryTick = now;
                if (state.parrot.teleport(anchor)) {
                    state.flightVelocity.zero();
                    state.parrot.setVelocity(new Vector());
                    state.flightYaw = anchor.getYaw();
                    state.stalledTicks = 0;
                    state.previousLocation = anchor.clone();
                    return anchor;
                }
            }
            if (worldChanged) return null;
        }
        // 周期的な小さい浮沈と、プレイヤーの移動速度を加味した追従。
        anchor.add(0.0D, Math.sin((now + state.visualPhase) * 0.12D) * 0.12D, 0.0D);
        Vector desired = anchor.toVector().subtract(current.toVector()).multiply(0.18D)
                .add(ownerVelocity.multiply(0.8D));
        limit(desired, MAX_FLIGHT_SPEED);
        Vector acceleration = desired.subtract(state.flightVelocity).multiply(0.32D);
        limit(acceleration, MAX_ACCELERATION);
        state.flightVelocity.add(acceleration);
        limit(state.flightVelocity, MAX_FLIGHT_SPEED);
        state.parrot.setVelocity(state.flightVelocity.clone());
        Vector horizontal = state.flightVelocity.clone().setY(0.0D);
        float targetYaw = horizontal.lengthSquared() > 0.0064D
                ? (float) Math.toDegrees(Math.atan2(-horizontal.getX(), horizontal.getZ())) : anchor.getYaw();
        float turn = Math.floorMod((int) Math.round((targetYaw - state.flightYaw) * 1000.0D) + 180000, 360000)
                / 1000.0F - 180.0F;
        state.flightYaw += Math.max(-8.0F, Math.min(8.0F, turn));
        state.parrot.setRotation(state.flightYaw, 0.0F);
        current.setYaw(state.flightYaw);
        current.setPitch(0.0F);
        return current;
    }

    /** 非有限値を無効化し、ベクトルの長さを指定上限に制限します。 */
    private static void limit(Vector vector, double maximum) {
        double lengthSquared = vector.lengthSquared();
        if (!Double.isFinite(lengthSquared)) vector.zero();
        else if (lengthSquared > maximum * maximum) vector.multiply(maximum / Math.sqrt(lengthSquared));
    }

    private void despawn(State state) {
        if (state.parrot != null) {
            summonedEntityIds.remove(state.parrot.getUniqueId());
            state.parrot.remove();
        }
        state.parrot = null;
        state.target = null;
    }

    private UUID bindingId(PassiveSkillContext context) {
        return context.learnedSkill() == null ? UUID.nameUUIDFromBytes(context.skill().getId().getBytes())
                : context.learnedSkill().getLearnedSkillId();
    }

    /** HUD 表示に使う不死鳥リソースです。 */
    public record PhoenixSnapshot(boolean active, double current, double maximum) { }

    private static final class State {
        private final AstPlayer owner;
        private final UUID accountId;
        private final Set<UUID> bindings = new HashSet<>();
        private final double maximum;
        private final double rangeSquared;
        private final long delayTicks;
        private Parrot parrot;
        private MobInstance target;
        private long lastInRangeTick;
        private long lastTick;
        private final Vector flightVelocity = new Vector();
        private float flightYaw;
        private Location previousLocation;
        private Location previousOwnerLocation;
        private int stalledTicks;
        private int visualPhase;
        private long lastRecoveryTick = -1000L;

        private State(AstPlayer owner, double maximum, double rangeSquared, long delayTicks) {
            this.owner = owner;
            this.accountId = owner.getAccount().getUuid();
            this.maximum = maximum;
            this.rangeSquared = rangeSquared;
            this.delayTicks = delayTicks;
        }
    }
}
