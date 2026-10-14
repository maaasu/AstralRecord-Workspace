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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ヤブガニの「横ばさみ」です。12tick詠唱後、固定した左右のはさみ軌道を20tick予告します。
 * 足元から左右1.5m・前方0.6mを始点、前方2.7mを終点とする二本の斜線で挟み、
 * hitboxを水平0.3m・垂直0.4m拡張して近接・無属性105%を最大4対象へ一度だけ与えます。
 * 背後、高低差1.25m超、壁の向こうは対象外です。paramsは受け付けません。
 * 既定の発動距離3m、再使用160tickです。全処理と構築はBukkitメインスレッド専用です。
 */
public final class BrushCrabMobSkillExecutor implements MobSkillExecutor, Listener {
    public static final String SKILL_ID = "mob_field_brush_crab_side_pincer";
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, Pincer> actions = new HashMap<>();

    /**
     * 共有戦闘処理を使用し、所有Pluginの停止listenerを一度登録します。
     * @param mobService Mob実体、移動停止、Schedulerの取得先
     * @param damageService 範囲候補とダメージ適用先
     * @param conditionService 行動不能・詠唱禁止の判定先
     * @param particles 左右の予告線と命中の描画先
     */
    public BrushCrabMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
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
    @Override public @NotNull String displayName() { return "横ばさみ"; }
    /** @return 3m・160tick再使用・12tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(3.0D, 160L, 12L); }

    /**
     * 左右二本の固定予告を開始し、その間の通常AIと水平移動を止めます。
     * @param context 詠唱開始時の照準と主対象
     * @return 有効な発動者が存在し、専用行動を開始した場合true
     */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = activeCaster(context);
        Vector direction = new Vector(context.direction().getX(), 0.0D, context.direction().getZ());
        if (entity == null || context.mob().scriptedAction() || direction.lengthSquared() < 0.01D) return false;
        Pincer action = new Pincer(context, entity.getLocation(), direction.normalize());
        actions.put(context.mob().instanceId(), action);
        context.mob().scriptedAction(true);
        try {
            mobService.stopHorizontalMovement(context.mob());
            particles.spawnForNearbyViewers(action.center, action.points, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING);
            action.center.getWorld().playSound(action.center, Sound.ENTITY_SILVERFISH_AMBIENT, 0.7F, 0.6F);
            action.task = action.runTaskTimer(mobService.plugin(), 5L, 5L);
            return true;
        } catch (RuntimeException exception) {
            action.finish();
            throw exception;
        }
    }

    /**
     * 所有Pluginの停止時に、全予告taskと通常AI停止状態を解除します。
     * @param event 停止したPluginの通知。他Pluginの通知は無視します
     */
    @EventHandler
    public void onPluginDisable(@NotNull PluginDisableEvent event) {
        if (event.getPlugin() == mobService.plugin()) List.copyOf(actions.values()).forEach(Pincer::finish);
    }

    /** 同一戦闘・主対象・worldが継続し、攻撃可能な発動者だけを返します。 */
    private @Nullable Entity activeCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        if (entity == null || !entity.isValid() || entity.isDead()
                || entity.getWorld() != context.origin().getWorld()
                || entity.getWorld() != context.target().getWorld()
                || Math.abs(entity.getLocation().getY() - context.target().getLocation().getY()) > 1.25D
                || !conditionService.canRunAi(caster) || !conditionService.canCastSkill(caster)
                || !conditionService.canAttack(caster)) return null;
        return entity;
    }

    /** 胴体への直線が遮蔽物に遮られていないか判定します。 */
    private boolean hasClearPath(@NotNull Location center, @NotNull LivingEntity target) {
        Location from = center.clone().add(0, 0.5D, 0);
        Location to = target.getLocation().add(0, Math.min(1.0D, target.getHeight() * 0.5D), 0);
        Vector delta = to.toVector().subtract(from.toVector());
        return delta.lengthSquared() < 0.0001D || from.getWorld().rayTraceBlocks(from, delta, delta.length()) == null;
    }

    /** 左右両方へ重なる場合も一度だけ攻撃する、個体別の固定予告です。 */
    private final class Pincer extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location center;
        private final List<Location> starts = new ArrayList<>(2);
        private final Location end;
        private final List<Location> points = new ArrayList<>(14);
        private BukkitTask task;
        private int elapsed;

        /** 固定した足元と、左右のはさみの斜線を保存します。 */
        private Pincer(MobSkillContext context, Location center, Vector direction) {
            this.context = context;
            this.center = center.clone();
            Vector side = new Vector(-direction.getZ(), 0, direction.getX());
            end = center.clone().add(direction.clone().multiply(2.7D)).add(0, 0.5D, 0);
            for (int sign : new int[]{-1, 1}) {
                Location start = center.clone().add(direction.clone().multiply(0.6D))
                        .add(side.clone().multiply(1.5D * sign)).add(0, 0.5D, 0);
                starts.add(start);
                Vector delta = end.toVector().subtract(start.toVector());
                for (int point = 0; point <= 6; point++) {
                    points.add(start.clone().add(delta.clone().multiply(point / 6.0D)).add(0, -0.4D, 0));
                }
            }
        }

        /** 5tick間隔で継続可否を確認し、20tick後に固定した二本の軌道へ一度だけ攻撃します。 */
        @Override
        public void run() {
            try {
                Entity entity = activeCaster(context);
                if (entity == null || entity.getLocation().distanceSquared(center) > 0.5625D) {
                    finish();
                    return;
                }
                mobService.stopHorizontalMovement(context.mob());
                elapsed += 5;
                if (elapsed < 20) {
                    particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING);
                    return;
                }
                try {
                    impact();
                } finally {
                    finish();
                }
            } catch (RuntimeException exception) {
                finish();
                throw exception;
            }
        }

        /** 有効対象を距離順で最大4体まで選び、両はさみへの重複命中を抑止します。 */
        private void impact() {
            particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT);
            center.getWorld().playSound(center, Sound.ENTITY_SILVERFISH_HURT, 0.8F, 0.8F);
            List<LivingEntity> targets = damageService.mobCombatTargets(center, 3.5D, 1.25D).stream()
                    .filter(target -> Math.abs(target.getLocation().getY() - center.getY()) <= 1.25D)
                    .filter(this::intersectsClaw)
                    .filter(target -> hasClearPath(center, target))
                    .sorted(Comparator.comparingDouble((LivingEntity target) -> target.getLocation().distanceSquared(center))
                            .thenComparing(Entity::getUniqueId))
                    .limit(4).toList();
            for (LivingEntity target : targets) {
                if (!damageService.isMobCombatTarget(target)) continue;
                damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(target), AttackType.MELEE,
                        List.of(new DamageComponent(DamageElement.NONE, 1.05D)), DamageSource.SKILL);
            }
        }

        /** 拡張hitboxと二本の有限線分を照合します。距離だけの円形攻撃にはしません。 */
        private boolean intersectsClaw(@NotNull LivingEntity target) {
            BoundingBox bounds = target.getBoundingBox().expand(0.3D, 0.4D, 0.3D);
            for (Location start : starts) {
                Vector delta = end.toVector().subtract(start.toVector());
                if (bounds.contains(start.toVector()) || bounds.rayTrace(start.toVector(), delta.clone().normalize(), delta.length()) != null) {
                    return true;
                }
            }
            return false;
        }

        /** taskと個体別参照を破棄し、通常AIを再開します。重複終了は無害です。 */
        private void finish() {
            if (task != null) task.cancel();
            if (actions.remove(context.mob().instanceId(), this)) context.mob().scriptedAction(false);
        }
    }
}
