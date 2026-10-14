package io.github.maaasu.astralRecord.feature.mob.skill.pond;

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
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 池の雑魚3種は「過去の足元」「戻る水筋」「鏡位置」をボス前に教えます。
 * 各Executorは1つのIDを担当し、paramsなし・castTimeTicks=0のみを許可します。
 * 警告を含め同期5tickで進み、通常AIを停止し、1回の発動全体で同じ対象へ一度しか命中しません。
 * 状態異常や地形変更は行いません。帰還・死亡・CC・主対象喪失・Plugin停止でタスクを回収します。
 */
public final class PondMobSkillExecutor implements MobSkillExecutor {
    private final Kind kind;
    private final MobService mobs;
    private final DamageService damage;
    private final ConditionService conditions;
    private final ParticleDisplayService particles;
    private final Map<UUID, Cast> casts = new HashMap<>();
    private Predicate<UUID> bound = ignored -> false;

    /** 1つの固有IDを所有するExecutorを構築します。 */
    private PondMobSkillExecutor(Kind kind, MobService mobs, DamageService damage,
                                 ConditionService conditions, ParticleDisplayService particles) {
        this.kind = kind; this.mobs = mobs; this.damage = damage; this.conditions = conditions; this.particles = particles;
    }

    /**
     * 3種の個別Executorを作成します。返した各個体はRegistry・destroy hook・stopへ登録してください。
     * @param mobs Mob存続・同期schedulerの取得先
     * @param damage 戦闘対象・ダメージ適用先
     * @param conditions 行動不能状態の判定先
     * @param particles 予告・命中描画先
     * @return skipper/reed/lanternの3Executor
     */
    public static @NotNull List<PondMobSkillExecutor> createAll(@NotNull MobService mobs, @NotNull DamageService damage,
            @NotNull ConditionService conditions, @NotNull ParticleDisplayService particles) {
        return List.of(new PondMobSkillExecutor(Kind.SKIPPER, mobs, damage, conditions, particles),
            new PondMobSkillExecutor(Kind.REED, mobs, damage, conditions, particles),
            new PondMobSkillExecutor(Kind.LANTERN, mobs, damage, conditions, particles));
    }

    /** @return マスタから参照する一意な固有ID */
    @Override public @NotNull String id() { return kind.id; }
    /** @param bound BindCircleの専用拘束状態。適用中は既に開始した専用再演も中断します */
    public void setBoundResolver(@NotNull Predicate<UUID> bound) { this.bound = bound; }
    /** @return 正本リソースの頭上詠唱表示名 */
    @Override public @NotNull String displayName() { return PlayerMsgResource.getMessage(kind.message.name()); }
    /** @return 発動距離10/12/11m、再使用180/120/140tick、専用予告のため事前詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(kind.range, kind.cooldown, 0L);
    }

    /** @param binding マスタ紐付け @throws IllegalArgumentException params・二重詠唱が指定された場合 */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        if (binding.castTimeTicks() != null && binding.castTimeTicks() != 0L)
            throw new IllegalArgumentException("Pond warning requires castTimeTicks=0: " + id());
    }

    /** @param context 発動開始文脈 @return 浅水・岸・射線・生存を確認し専用予告を開始できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = active(context);
        if (entity == null || context.mob().scriptedAction()) return false;
        Location center = PondSurface.at(context.target().getLocation());
        Location origin = PondSurface.at(context.origin());
        if (center == null || origin == null || !PondSurface.clear(origin, center)) return false;
        clearCasterState(context.mob().instanceId());
        Cast cast = new Cast(context, origin, center);
        if (kind == Kind.REED) {
            Vector aim = center.toVector().subtract(origin.toVector()).setY(0);
            if (aim.lengthSquared() < 0.01D) aim = context.direction().clone().setY(0);
            if (aim.lengthSquared() < 0.01D) return false;
            aim.normalize();
            cast.points.clear(); cast.points.add(origin);
            for (int i = 1; i <= 20; i++) {
                Location p = PondSurface.at(origin.clone().add(aim.clone().multiply(i * 0.5D)));
                if (p == null || !PondSurface.walkable(cast.points.getLast(), p)) break;
                cast.points.add(p);
            }
            if (cast.points.size() < 3) return false;
        } else if (kind == Kind.LANTERN) {
            Location mirror = PondSurface.at(new Location(origin.getWorld(), 2 * origin.getX() - center.getX(),
                origin.getY(), 2 * origin.getZ() - center.getZ()));
            if (mirror != null && PondSurface.clear(origin, mirror)) cast.points.add(mirror);
        }
        context.mob().scriptedAction(true);
        mobs.stopPathfinding(context.mob());
        context.mob().startSkillCasting(displayName(), kind.warning + (kind == Kind.SKIPPER ? 20L : 0L));
        BukkitRunnable runner = new BukkitRunnable() {
            /** 生存・状態を確認し、予告・記録・有限再演を同期で進めます。 */
            @Override public void run() {
                try {
                    if (active(context) == null || casts.get(context.mob().instanceId()) != cast) { clearCasterState(context.mob().instanceId()); return; }
                    cast.elapsed += 5;
                    int record = kind == Kind.SKIPPER ? 20 : 0;
                    if (kind == Kind.SKIPPER && cast.elapsed <= record && cast.elapsed % 10 == 0) {
                        Location p = PondSurface.at(context.target().getLocation());
                        if (p != null && PondSurface.clear(origin, p) && PondSurface.squared(origin, p) <= kind.range * kind.range)
                            cast.points.add(p);
                    }
                    long remaining = Math.max(0L, record + kind.warning - cast.elapsed);
                    context.mob().updateSkillCastingRemaining(remaining);
                    if (cast.elapsed % 10 == 0 && cast.elapsed < record + kind.warning) drawWarning(cast);
                    if (cast.elapsed >= record + kind.warning) {
                        context.mob().clearSkillCasting();
                        int elapsed = cast.elapsed - record - kind.warning;
                        if (kind == Kind.SKIPPER) {
                            if (elapsed % 10 == 0 && elapsed / 10 < cast.points.size()) impactCircle(cast, cast.points.get(elapsed / 10), 1.35D);
                            if (elapsed >= 20) clearCasterState(context.mob().instanceId());
                        } else if (kind == Kind.LANTERN) {
                            for (Location p : cast.points) impactCircle(cast, p, 1.75D);
                            clearCasterState(context.mob().instanceId());
                        } else {
                            int step = elapsed <= 20 ? elapsed : 40 - elapsed;
                            int old = elapsed <= 20 ? Math.max(0, step - 5) : Math.min(20, step + 5);
                            Location from = lineAt(cast, old), to = lineAt(cast, Math.clamp(step, 0, 20));
                            impactLine(cast, from, to);
                            if (elapsed >= 40) clearCasterState(context.mob().instanceId());
                        }
                    }
                } catch (RuntimeException exception) {
                    Logger.log(LogId.E_6511, exception, context.mob().instanceId());
                    clearCasterState(context.mob().instanceId());
                }
            }
        };
        cast.task = runner.runTaskTimer(mobs.plugin(), 5L, 5L);
        casts.put(context.mob().instanceId(), cast);
        drawWarning(cast);
        origin.getWorld().playSound(origin, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, 0.6F, 1.2F);
        return true;
    }

    /** 記録点を水輪、往復線を平行の2本線で表示します。最大72点・10tick間隔です。 */
    private void drawWarning(Cast cast) {
        List<Location> points = new ArrayList<>();
        if (kind == Kind.REED) {
            Vector direction = cast.points.getLast().toVector().subtract(cast.origin.toVector()).setY(0).normalize();
            Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(0.75D);
            for (Location p : cast.points) { points.add(p.clone().add(side)); points.add(p.clone().subtract(side)); }
        } else for (Location center : cast.points) for (int i = 0; i < 20; i++) {
            double angle = Math.PI * 2 * i / 20, radius = kind == Kind.LANTERN ? 1.75D : 1.35D;
            points.add(center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        }
        particles.spawnForNearbyViewers(cast.origin, points, kind == Kind.SKIPPER && cast.elapsed <= 20
            ? SharedParticleDefinitions.POND_RECORD : SharedParticleDefinitions.POND_WARNING);
    }

    /** 水上輪を一度破裂させ、同階・射線内の戦闘対象へ共通ダメージを適用します。 */
    private void impactCircle(Cast cast, Location center, double radius) {
        particles.spawnForNearbyViewers(center, SharedParticleDefinitions.POND_IMPACT);
        center.getWorld().playSound(center, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.8F, 0.9F);
        for (LivingEntity target : damage.mobCombatTargets(center, radius, 2.0D)) {
            if (PondSurface.squared(target.getLocation(), center) <= radius * radius && PondSurface.clear(center, target.getLocation())) hit(cast, target);
        }
    }

    /** 前進・後退する水筋を線分掃引し、壁越しや同一castの2回目を除きます。 */
    private void impactLine(Cast cast, Location from, Location to) {
        if (!PondSurface.clear(from, to)) return;
        List<Location> front = new ArrayList<>();
        Vector direction = cast.points.getLast().toVector().subtract(cast.origin.toVector()).setY(0).normalize();
        Vector side = new Vector(-direction.getZ(), 0, direction.getX());
        for (int i = -2; i <= 2; i++) front.add(to.clone().add(side.clone().multiply(i * 0.35D)));
        particles.spawnForNearbyViewers(cast.origin, front, SharedParticleDefinitions.POND_IMPACT);
        double radius = Math.sqrt(PondSurface.squared(from, to)) + 0.9D;
        for (LivingEntity target : damage.mobCombatTargets(to, radius, 2.0D)) if (
            PondSurface.segmentSquared(target.getLocation(), from, to) <= 0.75D * 0.75D
                && PondSurface.clear(to, target.getLocation())) hit(cast, target);
    }

    /** @return 20tickの前進中の水筋位置。水面へ再投影して段差へ沿わせます */
    private static Location lineAt(Cast cast, int step) {
        double progress = Math.clamp(step / 20.0D, 0, 1) * (cast.points.size() - 1);
        int index = (int) progress, next = Math.min(index + 1, cast.points.size() - 1);
        return cast.points.get(index).clone().add(cast.points.get(next).toVector()
            .subtract(cast.points.get(index).toVector()).multiply(progress - index));
    }

    /** 同一対象のダメージ要求は一度だけとし、回避された場合も重ねません。 */
    private void hit(Cast cast, LivingEntity target) {
        if (active(cast.context) == null || !cast.hit.add(target.getUniqueId())) return;
        damage.attack(AstEntity.mob(cast.context.mob()), damage.resolveEntity(target), kind.attack,
            List.of(new DamageComponent(DamageElement.ICE, kind.ratio)), DamageSource.SKILL);
    }

    /** 記録から再演完了まで、同じ戦闘個体・主対象・worldと行動可能性を確認します。 */
    private @Nullable Entity active(MobSkillContext context) {
        var mob = context.mob();
        if (!mobs.plugin().isEnabled() || mobs.getInstance(mob.instanceId()) != mob || mob.currentHealth() <= 0
            || bound.test(mob.instanceId())
            || mob.state() != MobState.COMBAT && mob.state() != MobState.AGGRO
            || !context.target().getUniqueId().equals(mob.targetId()) || !damage.isMobCombatTarget(context.target())) return null;
        Entity entity = mobs.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead() && entity.getWorld() == context.origin().getWorld()
            && entity.getWorld() == context.target().getWorld() && conditions.canRunAi(caster)
            && conditions.canCastSkill(caster) && conditions.canAttack(caster) ? entity : null;
    }

    /** @param casterId 死亡・帰還・Plugin停止で回収する発動者。通常AIと頭上詠唱も戻します */
    public void clearCasterState(@NotNull UUID casterId) {
        Cast cast = casts.remove(casterId);
        if (cast == null) return;
        if (cast.task != null) cast.task.cancel();
        cast.context.mob().scriptedAction(false);
        cast.context.mob().clearSkillCasting();
    }

    /** Plugin停止時に全タスクと発動者参照を回収します。 */
    public void stop() { for (UUID id : List.copyOf(casts.keySet())) clearCasterState(id); }

    private enum Kind {
        SKIPPER("mob_eriva_pond_skipper_wake", PlayerMsgId.P_7747, 10.0D, 180L, 30, 0.65D, AttackType.MELEE),
        REED("mob_eriva_pond_reed_return", PlayerMsgId.P_7748, 12.0D, 120L, 30, 0.65D, AttackType.RANGED),
        LANTERN("mob_eriva_pond_lantern_echo", PlayerMsgId.P_7749, 11.0D, 140L, 40, 0.70D, AttackType.MAGIC);
        private final String id;
        private final PlayerMsgId message;
        private final double range, ratio;
        private final long cooldown;
        private final int warning;
        private final AttackType attack;
        Kind(String id, PlayerMsgId message, double range, long cooldown, int warning, double ratio, AttackType attack) {
            this.id = id; this.message = message; this.range = range; this.cooldown = cooldown;
            this.warning = warning; this.ratio = ratio; this.attack = attack;
        }
    }
    private static final class Cast {
        private final MobSkillContext context;
        private final Location origin;
        private final List<Location> points = new ArrayList<>();
        private final Set<UUID> hit = new HashSet<>();
        private int elapsed;
        private @Nullable BukkitTask task;
        private Cast(MobSkillContext context, Location origin, Location center) {
            this.context = context; this.origin = origin; points.add(center);
        }
    }
}
