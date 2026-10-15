package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * クサハウンドの「草走り突進」です。16tick詠唱後に直線を10tick予告し、
 * 詠唱開始方向へ最大5.25mを0.75m/2tickで進み、主対象へ近接・無属性90%を一度だけ与えます。
 * 対象のhitboxを水平0.2m・垂直0.3m拡張して区間判定し、壁・段差・崖で停止します。
 * paramsは受け付けません。既定の発動距離5.25m、再使用140tickです。
 * 全処理と構築はBukkitメインスレッド専用で、構築時にPlugin停止listenerを登録します。
 */
public final class ReedHoundMobSkillExecutor implements MobSkillExecutor, Listener {
    public static final String SKILL_ID = "mob_field_reed_hound_grass_charge";
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, Charge> actions = new HashMap<>();

    /**
     * Mob本体と共有戦闘処理を使用し、所有Pluginの停止listenerを一度登録します。
     * @param mobService Mob実体、移動停止、Schedulerの取得先
     * @param damageService 有効対象判定とダメージ適用先
     * @param conditionService 行動不能・移動不能の判定先
     * @param particles 閲覧者ごとの予告・命中描画先
     */
    public ReedHoundMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
                                    @NotNull ConditionService conditionService,
                                    @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
        mobService.plugin().getServer().getPluginManager().registerEvents(this, mobService.plugin());
    }

    /** @return Mobマスタから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーの日本語技名 */
    @Override public @NotNull String displayName() { return "草走り突進"; }
    /** @return 5.25m・140tick再使用・16tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(5.25D, 140L, 16L); }

    /**
     * 固定方向の予告と突進taskを開始します。終了・例外時は通常AIの停止を解除します。
     * @param context 詠唱開始時の方向と主対象
     * @return 有効な発動者が存在し、専用行動を開始した場合true
     */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = activeCaster(context);
        Vector direction = new Vector(context.direction().getX(), 0.0D, context.direction().getZ());
        if (entity == null || context.mob().scriptedAction() || direction.lengthSquared() < 0.01D) {
            return false;
        }
        Charge action = new Charge(context, entity.getLocation(), direction.normalize());
        actions.put(context.mob().instanceId(), action);
        context.mob().scriptedAction(true);
        try {
            mobService.stopHorizontalMovement(context.mob());
            action.warn();
            action.task = action.runTaskTimer(mobService.plugin(), 2L, 2L);
            return true;
        } catch (RuntimeException exception) {
            action.finish();
            throw exception;
        }
    }

    /**
     * 所有Pluginの停止時に、予告・突進taskと通常AI停止状態を全て解除します。
     * @param event 停止したPluginの通知。他Pluginの通知は無視します
     */
    @EventHandler
    public void onPluginDisable(@NotNull PluginDisableEvent event) {
        if (event.getPlugin() == mobService.plugin()) {
            stop();
        }
    }

    /**
     * Mob破棄時に、この個体の行動タスクと通常AI停止状態を回収します。
     *
     * @param mobInstanceId 破棄されたMobの個体ID
     * @implNote Bukkit同期スレッドから呼び出します。未開始・回収済みの個体は無視します。
     */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        Charge action = actions.get(mobInstanceId);
        if (action != null) action.finish();
    }

    /**
     * Plugin停止時、Mob破棄とScheduler一括停止より先に全行動を回収します。
     *
     * @implNote Bukkit同期スレッドから呼び出します。重複呼び出しは無害です。
     */
    public void stop() {
        List.copyOf(actions.values()).forEach(Charge::finish);
    }

    /** 同一戦闘・主対象・worldが継続し、移動・攻撃可能な発動者を返します。 */
    private @Nullable Entity activeCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())) {
            return null;
        }
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        if (entity == null || !entity.isValid() || entity.isDead()
                || entity.getWorld() != context.origin().getWorld()
                || entity.getWorld() != context.target().getWorld()
                || Math.abs(entity.getLocation().getY() - context.target().getLocation().getY()) > 1.25D
                || !conditionService.canRunAi(caster) || !conditionService.canCastSkill(caster)
                || !conditionService.canAttack(caster) || !conditionService.canMove(caster)) {
            return null;
        }
        return entity;
    }

    /** 0.15m以下の刻みで実体全体を確認し、未読込chunk・衝突形状への侵入を拒否します。 */
    private boolean isStepClear(@NotNull Entity entity, @NotNull Location from, @NotNull Location to) {
        World world = entity.getWorld();
        Vector delta = to.toVector().subtract(from.toVector());
        BoundingBox original = entity.getBoundingBox();
        var border = world.getWorldBorder();
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.15D));
        for (int sample = 1; sample <= samples; sample++) {
            Vector position = from.toVector().add(delta.clone().multiply((double) sample / samples));
            BoundingBox bounds = original.clone().shift(position.clone().subtract(entity.getLocation().toVector()));
            int minY = (int) Math.floor(bounds.getMinY());
            int maxY = (int) Math.floor(Math.nextDown(bounds.getMaxY()));
            if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) return false;
            // WorldBorderは水平の正方形なので、対角2点でhitbox全体の収まりを確認できます。
            if (!border.isInside(new Location(world, bounds.getMinX(), position.getY(), bounds.getMinZ()))
                    || !border.isInside(new Location(world, Math.nextDown(bounds.getMaxX()),
                    position.getY(), Math.nextDown(bounds.getMaxZ())))) return false;
            for (int x = (int) Math.floor(bounds.getMinX()); x <= (int) Math.floor(Math.nextDown(bounds.getMaxX())); x++) {
                for (int z = (int) Math.floor(bounds.getMinZ()); z <= (int) Math.floor(Math.nextDown(bounds.getMaxZ())); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                    for (int y = minY; y <= maxY; y++) {
                        if (world.getBlockAt(x, y, z).getCollisionShape().overlaps(bounds.clone().shift(-x, -y, -z))) {
                            return false;
                        }
                    }
                }
            }
            Location feet = new Location(world, position.getX(), position.getY() - 0.2D, position.getZ());
            if (feet.getBlock().isPassable()) return false;
        }
        return true;
    }

    /** 最大12回だけ動く、個体別の予告・突進状態です。 */
    private final class Charge extends BukkitRunnable {
        private final MobSkillContext context;
        private final Vector direction;
        private final List<Location> warning = new ArrayList<>(16);
        private Location position;
        private BukkitTask task;
        private int elapsed;
        private int steps;

        /** 固定照準と16点の予告線を保存します。 */
        private Charge(MobSkillContext context, Location start, Vector direction) {
            this.context = context;
            this.position = start.clone();
            this.direction = direction.clone();
            Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX()).multiply(0.4D);
            for (int row = 0; row <= 7; row++) {
                Location point = start.clone().add(direction.clone().multiply(row * 0.75D)).add(0, 0.1D, 0);
                warning.add(point.clone().add(side));
                warning.add(point.clone().subtract(side));
            }
        }

        /** 予告線を一括描画し、初回だけ唸り声を鳴らします。 */
        private void warn() {
            particles.spawnForNearbyViewers(position, warning, SharedParticleDefinitions.MOB_ERIVA_STARHORN_WARNING);
            position.getWorld().playSound(position, Sound.ENTITY_WOLF_GROWL, 0.7F, 0.8F);
        }

        /** 予告10tick後に最大7区間進み、空振り・障害物・主対象命中で終了します。 */
        @Override
        public void run() {
            try {
                Entity entity = activeCaster(context);
                if (entity == null || entity.getLocation().distanceSquared(position) > 0.16D) {
                    finish();
                    return;
                }
                elapsed += 2;
                if (elapsed < 10) {
                    mobService.stopHorizontalMovement(context.mob());
                    if (elapsed == 4 || elapsed == 8) {
                        particles.spawnForNearbyViewers(position, warning, SharedParticleDefinitions.MOB_ERIVA_STARHORN_WARNING);
                    }
                    return;
                }
                Location next = position.clone().add(direction.clone().multiply(0.75D));
                if (!isStepClear(entity, position, next)) {
                    finish();
                    return;
                }
                Location traceStart = position.clone().add(0, 0.45D, 0);
                BoundingBox targetBounds = context.target().getBoundingBox().expand(0.2D, 0.3D, 0.2D);
                if (targetBounds.contains(traceStart.toVector())
                        || targetBounds.rayTrace(traceStart.toVector(), direction, 0.75D) != null) {
                    var result = damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(context.target()),
                            AttackType.MELEE, List.of(new DamageComponent(DamageElement.NONE, 0.9D)), DamageSource.SKILL);
                    if (!result.evaded() && (result.finalDamage() > 0 || result.shieldDamage() > 0)) {
                        particles.spawnForNearbyViewers(context.target().getLocation().add(0, 0.5D, 0),
                                SharedParticleDefinitions.MOB_ERIVA_THORNFANG_HIT);
                        position.getWorld().playSound(position, Sound.ENTITY_WOLF_HURT, 0.65F, 1.25F);
                    }
                    finish();
                    return;
                }
                if (!entity.teleport(next)) {
                    finish();
                    return;
                }
                context.mob().currentLocation(next);
                position = next;
                if (++steps % 2 == 0) {
                    particles.spawnForNearbyViewers(next.clone().add(0, 0.35D, 0), SharedParticleDefinitions.MOB_ERIVA_THORNFANG_TRAIL);
                }
                if (steps >= 7) finish();
            } catch (RuntimeException exception) {
                finish();
                throw exception;
            }
        }

        /** taskと個体別参照を破棄し、通常AIを再開します。重複終了は無害です。 */
        private void finish() {
            if (task != null) task.cancel();
            if (actions.remove(context.mob().instanceId(), this)) context.mob().scriptedAction(false);
        }
    }
}
