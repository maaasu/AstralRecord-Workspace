package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.scheduler.BukkitRunnable;
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
 * 地鳴り突進。30tickの固定進路予告後、0.5m/tickで最大8mを直進します。
 * 主対象へ近接・無属性110%を一度だけ与え、壁・液体・段差・崖・境界で止まります。
 * 終端または命中後12tickは通常AIを止めます。paramsなし、castTimeTicksは0のみ、
 * activationRangeは0超～8m、cooldownTicksは120以上（既定240）。内部予告は短縮しません。
 */
public final class StonebackBisonMobSkillExecutor implements MobSkillExecutor, Listener {
    public static final String SKILL_ID = "mob_field_stoneback_bison_earth_charge";
    private static final int WARNING_TICKS = 30;
    private static final int MOVE_TICKS = 16;
    private static final int RECOVERY_TICKS = 12;
    private static final double MAX_DISTANCE = 8.0D;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, Charge> actions = new HashMap<>();

    /**
     * メインスレッドで構築し、停止イベントの回収Listenerも登録します。
     * @param mobService Mob実体・同期Scheduler・Listener登録先
     * @param damageService 有効なプレイヤー／召喚ペット判定とダメージ適用先
     * @param conditionService 行動不能・移動不能の確認先
     * @param particles 共通粒子の描画先
     */
    public StonebackBisonMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
        mobService.plugin().getServer().getPluginManager().registerEvents(this, mobService.plugin());
    }

    /** @return Mobマスターの専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上予告バーへ表示する技名 */
    @Override public @NotNull String displayName() { return "地鳴り突進"; }
    /** @return 8m・240tick再使用・内部予告を開始する即時発動 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(8.0D, 240L, 0L); }

    /**
     * 固定予告を二重に詠唱せず、連打を防ぐ設定だけを受け付けます。
     * @param binding マスター上の紐付け
     * @throws IllegalArgumentException params、射程外、短すぎるCD、外部詠唱がある場合
     */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        var timing = resolveTiming(binding);
        if (!Double.isFinite(timing.activationRange()) || timing.activationRange() <= 0.0D
                || timing.activationRange() > MAX_DISTANCE || timing.cooldownTicks() < 120L
                || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid fixed-warning timing: " + id());
        }
    }

    /**
     * 有効な戦闘対象への方向を固定し、有限の予告・移動・硬直を開始します。
     * @param context 発動開始時の主対象・方向。メインスレッドから呼び出す
     * @return 安全な地上から開始できた場合true。既存行動中はfalse
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        validate(context.binding());
        Entity entity = activeCaster(context);
        if (entity == null || context.mob().scriptedAction() || actions.containsKey(context.mob().instanceId())) return false;
        Vector direction = new Vector(context.direction().getX(), 0.0D, context.direction().getZ());
        if (direction.lengthSquared() < 0.01D) return false;
        direction.normalize();
        Location start = entity.getLocation();
        if (!clearStep(entity, start, start, true)) return false;
        Charge action = new Charge(context, entity, start, direction);
        try {
            actions.put(context.mob().instanceId(), action);
            context.mob().scriptedAction(true);
            context.mob().startSkillCasting(displayName(), WARNING_TICKS);
            mobService.stopPathfinding(context.mob());
            action.runTaskTimer(mobService.plugin(), 1L, 1L);
        } catch (RuntimeException ex) {
            action.finish();
            throw ex;
        }
        return true;
    }

    /**
     * 破棄・帰還・対象変更・異常状態・World変更では次tickに中断します。
     * @param context 発動文脈
     * @return 同一戦闘で行動できる実体。中断条件ならnull
     */
    private @Nullable Entity activeCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld() && entity.getWorld() == context.target().getWorld()
                && conditionService.canRunAi(caster) && conditionService.canCastSkill(caster)
                && conditionService.canAttack(caster) && conditionService.canMove(caster) ? entity : null;
    }

    /**
     * 実体幅・高さを0.15m以下の刻みで調べ、未読込chunkへの参照を防ぎます。
     * @param entity 発動実体
     * @param from 現在足元
     * @param to 次の足元
     * @param ground 全サンプルの足元支持も必要か
     * @return 衝突・液体・WorldBorder・World高さのすべてが安全な場合true
     */
    private static boolean clearStep(Entity entity, Location from, Location to, boolean ground) {
        World world = from.getWorld();
        if (world == null || world != to.getWorld() || world != entity.getWorld()) return false;
        Vector delta = to.toVector().subtract(from.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.15D));
        BoundingBox shape = entity.getBoundingBox();
        for (int sample = 0; sample <= samples; sample++) {
            Location foot = from.clone().add(delta.clone().multiply((double) sample / samples));
            BoundingBox bounds = shape.clone().shift(foot.toVector().subtract(entity.getLocation().toVector()));
            int minY = (int) Math.floor(bounds.getMinY());
            int maxY = (int) Math.floor(Math.nextDown(bounds.getMaxY()));
            if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) return false;
            for (double x : new double[]{bounds.getMinX(), bounds.getMaxX()}) {
                for (double z : new double[]{bounds.getMinZ(), bounds.getMaxZ()}) {
                    if (!world.getWorldBorder().isInside(new Location(world, x, foot.getY(), z))) return false;
                }
            }
            for (int x = (int) Math.floor(bounds.getMinX()); x <= (int) Math.floor(Math.nextDown(bounds.getMaxX())); x++) {
                for (int z = (int) Math.floor(bounds.getMinZ()); z <= (int) Math.floor(Math.nextDown(bounds.getMaxZ())); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                    for (int y = minY; y <= maxY; y++) {
                        var block = world.getBlockAt(x, y, z);
                        if (block.isLiquid() || block.getCollisionShape().overlaps(bounds.clone().shift(-x, -y, -z))) return false;
                    }
                }
            }
            if (ground && !supported(world, bounds)) return false;
        }
        return true;
    }

    /**
     * 中央と四隅が同じ足元高さで支持されるか確認し、薄い角・崖・液体を避けます。
     * @param world 読込確認するWorld
     * @param bounds 足元を含む実体の範囲
     * @return 5点が衝突形状上面から0.12m以内にある場合true
     */
    private static boolean supported(World world, BoundingBox bounds) {
        double footY = bounds.getMinY();
        int y = (int) Math.floor(footY - 0.05D);
        if (y < world.getMinHeight()) return false;
        double centerX = (bounds.getMinX() + bounds.getMaxX()) * 0.5D;
        double centerZ = (bounds.getMinZ() + bounds.getMaxZ()) * 0.5D;
        double[][] probes = {{centerX, centerZ}, {bounds.getMinX() + 0.05D, bounds.getMinZ() + 0.05D},
                {bounds.getMaxX() - 0.05D, bounds.getMinZ() + 0.05D},
                {bounds.getMinX() + 0.05D, bounds.getMaxZ() - 0.05D},
                {bounds.getMaxX() - 0.05D, bounds.getMaxZ() - 0.05D}};
        for (double[] probe : probes) {
            int x = (int) Math.floor(probe[0]);
            int z = (int) Math.floor(probe[1]);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            var block = world.getBlockAt(x, y, z);
            if (block.isLiquid()) return false;
            boolean found = false;
            for (BoundingBox support : block.getCollisionShape().getBoundingBoxes()) {
                double gap = footY - (y + support.getMaxY());
                if (gap >= -0.001D && gap <= 0.12D && probe[0] - x >= support.getMinX()
                        && probe[0] - x <= support.getMaxX() && probe[1] - z >= support.getMinZ()
                        && probe[1] - z <= support.getMaxZ()) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    /** 地鳴り突進一回分の有限状態。終了は冪等です。 */
    private final class Charge extends BukkitRunnable {
        private final MobSkillContext context;
        private final Entity entity;
        private final Vector direction;
        private final Location start;
        private final List<Location> warning;
        private Location position;
        private int ticks;
        private int moves;
        private int recovery = -1;
        private boolean finished;

        /** @param context 固定戦闘文脈 @param entity 実体 @param start 足元 @param direction 固定方向 */
        private Charge(MobSkillContext context, Entity entity, Location start, Vector direction) {
            this.context = context;
            this.entity = entity;
            this.start = start.clone();
            this.direction = direction.clone();
            this.position = start.clone();
            this.warning = new ArrayList<>();
            BoundingBox box = entity.getBoundingBox();
            BoundingBox targetBox = context.target().getBoundingBox();
            // 主対象の幅も含め、足元の中心を予告線より外へ出す回避を示します。
            double halfWidth = (Math.max(box.getWidthX(), box.getWidthZ())
                    + Math.max(targetBox.getWidthX(), targetBox.getWidthZ())) * 0.5D + 0.15D;
            Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(halfWidth);
            for (int point = 0; point <= 8; point++) {
                Location center = start.clone().add(direction.clone().multiply(point)).add(0, 0.08D, 0);
                warning.add(center.clone().add(side));
                warning.add(center.clone().subtract(side));
            }
        }

        /** 予告中は固定、突進中は連続区間で主対象を一度だけ判定します。 */
        @Override public void run() {
            try {
                if (activeCaster(context) != entity || entity.getLocation().distanceSquared(position) > 0.25D) { finish(); return; }
                if (recovery >= 0) { if (++recovery >= RECOVERY_TICKS) finish(); return; }
                if (ticks++ < WARNING_TICKS) {
                    context.mob().updateSkillCastingRemaining(WARNING_TICKS - ticks);
                    if ((ticks - 1) % 5 == 0) {
                        particles.spawnForNearbyViewers(start, warning, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING);
                        if (ticks == 1) start.getWorld().playSound(start, org.bukkit.Sound.ENTITY_COW_AMBIENT, 0.8F, 0.6F);
                    }
                    return;
                }
                context.mob().clearSkillCasting();
                Location next = position.clone().add(direction.clone().multiply(0.5D));
                if (!clearStep(entity, position, next, true)) { recovery = 0; return; }
                Location center = position.clone().add(0, entity.getHeight() * 0.5D, 0);
                BoundingBox box = entity.getBoundingBox();
                BoundingBox target = context.target().getBoundingBox().clone().expand(
                        box.getWidthX() * 0.5D + 0.15D, entity.getHeight() * 0.5D, box.getWidthZ() * 0.5D + 0.15D);
                boolean hit = target.contains(center.toVector()) || target.rayTrace(center.toVector(), direction, 0.5D) != null;
                if (hit && clearTargetPath(center)) {
                    var result = damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(context.target()),
                            AttackType.MELEE, List.of(new DamageComponent(DamageElement.NONE, 1.10D)), DamageSource.SKILL);
                    recovery = 0;
                    if (!result.evaded() && (result.finalDamage() > 0 || result.shieldDamage() > 0)) {
                        particles.spawnForNearbyViewers(center, SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT);
                        center.getWorld().playSound(center, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.45F, 1.5F);
                    }
                    return;
                }
                next.setYaw((float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ())));
                if (!entity.teleport(next)) { finish(); return; }
                context.mob().currentLocation(next);
                position = next;
                if (++moves % 4 == 0) particles.spawnForNearbyViewers(next.clone().add(0, 0.1D, 0), SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT);
                if (moves >= MOVE_TICKS) recovery = 0;
            } catch (RuntimeException ex) { finish(); throw ex; }
        }

        /** @param center 発動者の胴体位置 @return 主対象までの壁がない場合true */
        private boolean clearTargetPath(Location center) {
            Vector ray = context.target().getBoundingBox().getCenter().subtract(center.toVector());
            double length = ray.length();
            return length < 0.01D || center.getWorld().rayTraceBlocks(center, ray, length, FluidCollisionMode.NEVER, true) == null;
        }

        /** 全終了経路でタスク・バー・AI停止・保持参照を一度だけ回収します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            actions.remove(context.mob().instanceId(), this);
            context.mob().clearSkillCasting();
            context.mob().scriptedAction(false);
            try { cancel(); } catch (IllegalStateException ignored) { /* スケジュール失敗時は未登録です。 */ }
        }
    }

    /**
     * メインスレッドで全継続行動を回収します。中央onDisableからも呼び出せます。
     * @implNote 同じactionの重複終了は無害です。
     */
    public void stop() { for (Charge action : List.copyOf(actions.values())) action.finish(); }

    /** @param event 所有Plugin停止時だけ、Bukkitのタスク一括キャンセル前に状態を回収するイベント */
    @EventHandler public void onPluginDisable(@NotNull PluginDisableEvent event) {
        if (event.getPlugin() == mobService.plugin()) stop();
    }
}
