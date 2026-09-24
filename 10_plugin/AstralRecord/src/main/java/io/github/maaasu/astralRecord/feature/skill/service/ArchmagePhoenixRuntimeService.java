package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
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
    private static final long VISUAL_INTERVAL_TICKS = 10L;
    private static final int MAX_VISUAL_POINTS = 100;
    private final ParticleDisplayService particles;
    private final Map<UUID, State> states = new HashMap<>();
    private final Set<UUID> summonedEntityIds = new HashSet<>();

    /**
     * 不死鳥の実行時状態を構築します。
     * @param particles 共通の近傍閲覧者向けパーティクル表示サービス
     */
    public ArchmagePhoenixRuntimeService(@NotNull ParticleDisplayService particles) {
        this.particles = particles;
    }

    /**
     * 有効なバインド個体を記録し、未召喚リソースを公開します。
     * @param context 有効化されたパッシブ個体と解決済みパラメーター
     */
    public void activate(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        double maximum = params.getDouble("phoenixMax", MAX_PHOENIX);
        double range = params.getDouble("targetRange", DEFAULT_TARGET_RANGE);
        int delay = params.getInt("despawnDelayTicks", DEFAULT_DESPAWN_DELAY_TICKS);
        states.computeIfAbsent(context.player().getBukkit().getUniqueId(),
                ignored -> new State(maximum, range * range, delay))
                .bindings.add(bindingId(context));
    }

    /**
     * 最後のバインド個体が解除された場合、召喚体を除去します。
     * @param context 無効化されたパッシブ個体
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        State state = states.get(playerId);
        if (state == null) return;
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
        State state = states.get(attacker.getBukkit().getUniqueId());
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
     * HUD に有効時 0/最大値、召喚中 最大値/最大値 を渡します。
     * @param player 表示対象プレイヤー
     * @return 有効性、現在値、最大値
     */
    public @NotNull PhoenixSnapshot snapshot(@NotNull AstPlayer player) {
        State state = states.get(player.getBukkit().getUniqueId());
        return state == null ? new PhoenixSnapshot(false, 0.0D, 0.0D)
                : new PhoenixSnapshot(true, state.parrot == null ? 0.0D : state.maximum, state.maximum);
    }

    /**
     * 有効パッシブの定期処理で、最新対象と召喚体を更新します。
     * @param context 有効なパッシブ個体
     */
    public void tick(@NotNull PassiveSkillContext context) {
        Player player = context.player().getBukkit();
        State state = states.get(player.getUniqueId());
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
        Location location = followLocation(player);
        state.parrot.teleport(location);
        if (now - state.lastVisualTick >= VISUAL_INTERVAL_TICKS) {
            state.lastVisualTick = now;
            renderWingsAndTail(location, player.getLocation().getDirection(), now);
        }
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
                        parrot.setAI(false);
                        parrot.setGravity(false);
                        parrot.setSilent(true);
                        parrot.setInvulnerable(true);
                        parrot.setCollidable(false);
                        parrot.setPersistent(false);
                    });
            if (!spawned.isValid()) {
                // 他のイベントハンドラに生成を取り消された場合、保護登録とHP表示を残さない。
                despawn(state);
            }
        } catch (RuntimeException exception) {
            despawn(state);
            throw exception;
        }
    }

    private Location followLocation(Player player) {
        Location location = player.getLocation();
        Vector forward = location.getDirection().setY(0.0D);
        if (forward.lengthSquared() > 1.0E-6D) forward.normalize();
        return location.add(forward.multiply(-1.5D)).add(0.0D, 2.4D, 0.0D);
    }

    /** 左右5枚の扇状翼と3本の尾を最大86点で描画します。 */
    private void renderWingsAndTail(Location body, Vector direction, long tick) {
        Vector forward = direction.clone().setY(0.0D);
        if (forward.lengthSquared() < 1.0E-6D) forward.setZ(1.0D);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
        double flutter = Math.sin(tick * 0.12D) * 0.16D;
        List<Location> gold = new ArrayList<>(32);
        List<Location> orange = new ArrayList<>(32);
        List<Location> red = new ArrayList<>(32);
        List<Location> blue = new ArrayList<>(2);
        List<Location> flame = new ArrayList<>(10);
        for (int side : new int[] {-1, 1}) {
            for (int feather = 0; feather < 5; feather++) {
                double span = 0.95D + feather * 0.23D;
                double trail = 0.18D + feather * 0.24D;
                double rise = 0.42D + (4 - feather) * 0.13D + flutter;
                for (int segment = 1; segment <= 5; segment++) {
                    double progress = segment / 5.0D;
                    Location point = body.clone()
                            .add(right.clone().multiply(side * (0.20D + span * progress)))
                            .add(forward.clone().multiply(-trail * progress))
                            .add(0.0D, rise * progress + 0.10D * Math.sin(progress * Math.PI), 0.0D);
                    (feather <= 1 ? gold : feather <= 3 ? orange : red).add(point);
                    if (segment == 5) flame.add(point.clone());
                }
            }
            blue.add(body.clone().add(right.clone().multiply(side * 0.26D)).add(0.0D, -0.08D, 0.0D));
        }
        for (int strand = -1; strand <= 1; strand++) {
            for (int segment = 1; segment <= 8; segment++) {
                double progress = segment / 8.0D;
                Location point = body.clone()
                        .add(forward.clone().multiply(-2.3D * progress))
                        .add(right.clone().multiply(strand * (0.12D + 0.52D * progress)))
                        .add(0.0D, -1.45D * progress + Math.sin(tick * 0.1D + segment * 0.45D + strand) * 0.06D, 0.0D);
                (segment <= 3 ? gold : segment <= 6 ? orange : red).add(point);
            }
        }
        // 翼50点 + 尾24点 + 火炎先端10点 + 青炎2点 = 86点/描画。
        if (gold.size() + orange.size() + red.size() + blue.size() + flame.size() > MAX_VISUAL_POINTS) return;
        particles.spawnForNearbyViewers(gold, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_GOLD);
        particles.spawnForNearbyViewers(orange, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_ORANGE);
        particles.spawnForNearbyViewers(red, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_RED);
        particles.spawnForNearbyViewers(blue, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_BLUE);
        particles.spawnForNearbyViewers(flame, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_FLAME);
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
        private final Set<UUID> bindings = new HashSet<>();
        private final double maximum;
        private final double rangeSquared;
        private final long delayTicks;
        private Parrot parrot;
        private MobInstance target;
        private long lastInRangeTick;
        private long lastTick;
        private long lastVisualTick;

        private State(double maximum, double rangeSquared, long delayTicks) {
            this.maximum = maximum;
            this.rangeSquared = rangeSquared;
            this.delayTicks = delayTicks;
        }
    }
}
