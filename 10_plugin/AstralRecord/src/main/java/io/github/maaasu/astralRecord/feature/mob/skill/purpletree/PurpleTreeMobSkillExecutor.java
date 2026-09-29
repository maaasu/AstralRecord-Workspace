package io.github.maaasu.astralRecord.feature.mob.skill.purpletree;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 紫蝕の樹守、ゴルム、メルザの独立した Mob 専用攻撃です。取り巻きはボス状態を参照しません。 */
public final class PurpleTreeMobSkillExecutor implements MobSkillExecutor {

    private static final int WARNING_TICKS = 20;
    private static final int WARNING_STEP_TICKS = 5;
    private static final int RING_POINTS = 32;
    private static final int PROJECTILE_LIFETIME_TICKS = 24;

    private final Kind kind;
    private final MobService mobService;
    private final DamageService damageService;
    private final ParticleDisplayService particles;

    /** 各攻撃を個別 ID の executor として構築します。 */
    public static @NotNull List<MobSkillExecutor> createAll(
            @NotNull MobService mobService,
            @NotNull DamageService damageService,
            @NotNull ParticleDisplayService particles
    ) {
        List<MobSkillExecutor> result = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            result.add(new PurpleTreeMobSkillExecutor(kind, mobService, damageService, particles));
        }
        return List.copyOf(result);
    }

    /** 依存先と担当する攻撃を固定します。 */
    private PurpleTreeMobSkillExecutor(Kind kind, MobService mobService,
                                       DamageService damageService, ParticleDisplayService particles) {
        this.kind = kind;
        this.mobService = mobService;
        this.damageService = damageService;
        this.particles = particles;
    }

    @Override public @NotNull String id() { return kind.id; }
    @Override public @NotNull String displayName() { return kind.displayName; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return kind.timing; }
    @Override public boolean allowsVerticalTargeting() { return kind == Kind.BOLT; }

    /** 発動者と対象が有効な場合に、担当する攻撃だけを開始します。 */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = activeCaster(context.mob(), context.target().getWorld());
        if (caster == null || !validTarget(context.target(), caster.getWorld())) {
            return false;
        }
        return switch (kind) {
            case SWEEP -> sweep(context, caster);
            case BIND -> bind(context);
            case CHARGE -> charge(context, caster);
            case SLAM -> slam(context, caster);
            case BOLT -> bolt(context);
            case CURSE -> curse(context);
        };
    }

    /** ヴェルグの前方扇形を固定して予告し、HP 半分以下なら射程を広げます。 */
    private boolean sweep(MobSkillContext context, Entity caster) {
        Location center = caster.getLocation();
        Vector direction = context.target().getLocation().toVector().subtract(center.toVector()).setY(0);
        if (direction.lengthSquared() < 0.01D) return false;
        double radius = phaseRadius(context.mob(), 5.5D);
        Vector facing = direction.normalize();
        List<Location> warning = fanPoints(center, facing, radius);
        scheduleArea(context.mob(), center, warning, () -> {
            burst(center, warning);
            for (Player player : playersNear(center, radius)) {
                Vector relative = player.getLocation().toVector().subtract(center.toVector()).setY(0);
                if (relative.lengthSquared() <= radius * radius
                        && (relative.lengthSquared() <= 0.01D || facing.dot(relative.normalize()) >= 0.5D)) {
                    damage(context.mob(), player, AttackType.MELEE, 0.95D);
                }
            }
        });
        center.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2F, 0.65F);
        return true;
    }

    /** 指定地点を遅れて拘束します。ボスの後半は判定半径だけ広がります。 */
    private boolean bind(MobSkillContext context) {
        Location center = context.target().getLocation().clone();
        double radius = phaseRadius(context.mob(), 2.1D);
        List<Location> warning = ringPoints(center, radius, 0.12D);
        scheduleArea(context.mob(), center, warning, () -> {
            burst(center, ringPoints(center, radius, 0.7D));
            for (Player player : playersNear(center, radius)) {
                if (horizontalDistanceSquared(player.getLocation(), center) <= radius * radius) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 10, false, true));
                }
            }
        });
        center.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, 0.6F);
        return true;
    }

    /** ゴルムを予告時点の方向へ直進させ、接触した対象だけを打撃します。 */
    private boolean charge(MobSkillContext context, Entity caster) {
        Location start = caster.getLocation();
        Vector direction = context.target().getLocation().toVector().subtract(start.toVector()).setY(0);
        if (direction.lengthSquared() < 0.25D) return false;
        direction.normalize();
        List<Location> warning = linePoints(start, direction, 8.0D);
        scheduleArea(context.mob(), start, warning, () -> startCharge(context.mob(), context.target(), direction));
        start.getWorld().playSound(start, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0F, 0.6F);
        return true;
    }

    /** 突進中は壁か対象に当たるまで移動し、発動者が消えたら即時終了します。 */
    private void startCharge(MobInstance mob, Player target, Vector direction) {
        Entity entity = activeCaster(mob, target.getWorld());
        if (entity == null || !validTarget(target, entity.getWorld())) return;
        mob.scriptedAction(true);
        new BukkitRunnable() {
            private int ticks;

            @Override public void run() {
                Entity active = activeCaster(mob, target.getWorld());
                if (active == null || !validTarget(target, active.getWorld()) || ticks++ >= 10) {
                    finish(); return;
                }
                Location from = active.getLocation();
                Vector movement = direction.clone().multiply(0.8D);
                Location rayOrigin = from.clone().add(0, 0.9D, 0);
                if (from.getWorld().rayTraceBlocks(rayOrigin, movement, 0.8D) != null) {
                    finish(); return;
                }
                Location next = from.clone().add(movement);
                active.teleport(next);
                mob.currentLocation(next);
                particles.spawnForNearbyViewers(next.clone().add(0, 0.7D, 0),
                        SharedParticleDefinitions.MOB_PURPLE_TREE_CHARGE);
                RayTraceResult hit = target.getBoundingBox().expand(0.35D)
                        .rayTrace(rayOrigin.toVector(), movement, movement.length());
                if (hit != null) {
                    damage(mob, target, AttackType.MELEE, 0.9D);
                    target.setVelocity(direction.clone().multiply(1.0D).setY(0.35D));
                    finish();
                }
            }

            private void finish() {
                mob.scriptedAction(false);
                cancel();
            }
        }.runTaskTimer(mobService.plugin(), 0L, 1L);
    }

    /** ゴルムの周囲に攻撃範囲を予告してから一度だけ攻撃します。 */
    private boolean slam(MobSkillContext context, Entity caster) {
        Location center = caster.getLocation();
        double radius = 3.0D;
        List<Location> warning = ringPoints(center, radius, 0.12D);
        scheduleArea(context.mob(), center, warning, () -> {
            burst(center, ringPoints(center, radius, 0.8D));
            for (Player player : playersNear(center, radius)) {
                if (horizontalDistanceSquared(player.getLocation(), center) <= radius * radius) {
                    damage(context.mob(), player, AttackType.MELEE, 0.75D);
                }
            }
        });
        center.getWorld().playSound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.1F, 0.5F);
        return true;
    }

    /** メルザの呪弾を発射し、直線上の壁または対象への衝突を判定します。 */
    private boolean bolt(MobSkillContext context) {
        Location origin = context.origin();
        Vector direction = context.direction();
        if (direction.lengthSquared() < 0.01D) return false;
        direction.normalize();
        origin.getWorld().playSound(origin, Sound.ENTITY_SKELETON_SHOOT, 1.0F, 0.6F);
        new BukkitRunnable() {
            private Location position = origin.clone();
            private int ticks;

            @Override public void run() {
                Entity caster = activeCaster(context.mob(), origin.getWorld());
                if (caster == null || ticks++ >= PROJECTILE_LIFETIME_TICKS) {
                    cancel(); return;
                }
                Vector movement = direction.clone().multiply(0.9D);
                RayTraceResult block = position.getWorld().rayTraceBlocks(position, movement, movement.length());
                RayTraceResult player = validTarget(context.target(), origin.getWorld())
                        ? context.target().getBoundingBox().expand(0.3D)
                            .rayTrace(position.toVector(), movement, movement.length()) : null;
                double blockDistance = block == null ? Double.POSITIVE_INFINITY
                        : block.getHitPosition().distance(position.toVector());
                double playerDistance = player == null ? Double.POSITIVE_INFINITY
                        : player.getHitPosition().distance(position.toVector());
                if (player != null && playerDistance <= blockDistance) {
                    position = player.getHitPosition().toLocation(origin.getWorld());
                    burst(position, ringPoints(position, 0.7D, 0));
                    damage(context.mob(), context.target(), AttackType.MAGIC, 0.7D);
                    cancel(); return;
                }
                if (block != null) {
                    burst(block.getHitPosition().toLocation(origin.getWorld()), List.of());
                    cancel(); return;
                }
                position.add(movement);
                particles.spawnForNearbyViewers(position, SharedParticleDefinitions.MOB_PURPLE_TREE_BOLT);
            }
        }.runTaskTimer(mobService.plugin(), 0L, 1L);
        return true;
    }

    /** メルザの対象地点攻撃を予告してから一度だけ判定します。 */
    private boolean curse(MobSkillContext context) {
        Location center = context.target().getLocation().clone();
        double radius = 2.6D;
        List<Location> warning = ringPoints(center, radius, 0.12D);
        scheduleArea(context.mob(), center, warning, () -> {
            burst(center, ringPoints(center, radius, 1.0D));
            for (Player player : playersNear(center, radius)) {
                if (horizontalDistanceSquared(player.getLocation(), center) <= radius * radius) {
                    damage(context.mob(), player, AttackType.MAGIC, 0.8D);
                }
            }
        });
        center.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, 0.75F);
        return true;
    }

    /** 予告を5 tick間隔で更新し、20 tick後に一度だけ効果を実行します。 */
    private void scheduleArea(MobInstance caster, Location center, List<Location> warning, Runnable impact) {
        particles.spawnForNearbyViewers(center, warning, SharedParticleDefinitions.MOB_PURPLE_TREE_WARNING);
        new BukkitRunnable() {
            private int elapsed;

            @Override public void run() {
                if (activeCaster(caster, center.getWorld()) == null) {
                    cancel(); return;
                }
                elapsed += WARNING_STEP_TICKS;
                if (elapsed >= WARNING_TICKS) {
                    impact.run(); cancel(); return;
                }
                particles.spawnForNearbyViewers(center, warning, SharedParticleDefinitions.MOB_PURPLE_TREE_WARNING);
            }
        }.runTaskTimer(mobService.plugin(), WARNING_STEP_TICKS, WARNING_STEP_TICKS);
    }

    /** 範囲着弾時の紫色の広がりと中心の閃光を表示します。 */
    private void burst(Location center, List<Location> points) {
        if (!points.isEmpty()) {
            particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_PURPLE_TREE_IMPACT);
        }
        particles.spawnForNearbyViewers(center, SharedParticleDefinitions.MOB_PURPLE_TREE_FLASH);
    }

    /** 指定半径の円周を、同じ閲覧者解決で描画できる地点群にします。 */
    private List<Location> ringPoints(Location center, double radius, double height) {
        List<Location> points = new ArrayList<>(RING_POINTS);
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = 2.0D * Math.PI * i / RING_POINTS;
            points.add(center.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius));
        }
        return points;
    }

    /** 前方扇形の境界と弧を描く地点群を返します。 */
    private List<Location> fanPoints(Location center, Vector facing, double radius) {
        List<Location> points = new ArrayList<>();
        double angle = Math.atan2(facing.getZ(), facing.getX());
        for (double distance = 1.5D; distance <= radius; distance += 1.5D) {
            for (int degree = -60; degree <= 60; degree += 10) {
                double direction = angle + Math.toRadians(degree);
                points.add(center.clone().add(Math.cos(direction) * distance, 0.15D,
                        Math.sin(direction) * distance));
            }
        }
        return points;
    }

    /** 突進方向の予告線を返します。 */
    private List<Location> linePoints(Location center, Vector direction, double length) {
        List<Location> points = new ArrayList<>();
        for (double distance = 0.5D; distance <= length; distance += 0.5D) {
            points.add(center.clone().add(direction.clone().multiply(distance)).add(0, 0.15D, 0));
        }
        return points;
    }

    /** 発動者の現在HPを参照し、ヴェルグ本人の攻撃範囲だけを切り替えます。 */
    private double phaseRadius(MobInstance mob, double base) {
        return mob.currentHealth() <= mob.maxHealth() * 0.5D ? base * 1.35D : base;
    }

    /** 同じワールドにいる有効な発動者の実体を返します。 */
    private @Nullable Entity activeCaster(MobInstance mob, World world) {
        MobInstance active = mobService.getInstance(mob.instanceId());
        Entity entity = active == mob ? mobService.entityController().getEntity(active) : null;
        return entity == null || entity.isDead() || entity.getWorld() != world ? null : entity;
    }

    /** 戦闘対象のプレイヤーだけを範囲攻撃の候補として返します。 */
    private List<Player> playersNear(Location center, double radius) {
        List<Player> result = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, 2.5D, radius)) {
            if (entity instanceof Player player && validTarget(player, center.getWorld())) result.add(player);
        }
        return result;
    }

    /** プレイヤーが現在この攻撃の対象になれるか確認します。 */
    private boolean validTarget(Player player, World world) {
        return player.isOnline() && !player.isDead() && player.getWorld() == world
                && AccountModeGuard.isGameplayPlayer(player);
    }

    /** 対象へ Mob スキルダメージを一度だけ与えます。 */
    private void damage(MobInstance mob, Player player, AttackType type, double ratio) {
        damageService.attack(AstEntity.mob(mob), damageService.resolveEntity(player), type,
                List.of(new DamageComponent(DamageElement.NONE, ratio)), DamageSource.SKILL);
    }

    /** 水平面での二点間距離の二乗を計算します。 */
    private double horizontalDistanceSquared(Location first, Location second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    private enum Kind {
        SWEEP("mob_purple_tree_sweep", "刈り払い", new MobSkillTiming(5.5D, 110L, 18L)),
        BIND("mob_purple_tree_bind", "根縛り", new MobSkillTiming(11.0D, 150L, 24L)),
        CHARGE("mob_purple_tree_charge", "突進", new MobSkillTiming(9.0D, 120L, 20L)),
        SLAM("mob_purple_tree_slam", "地砕き", new MobSkillTiming(3.5D, 100L, 16L)),
        BOLT("mob_purple_tree_bolt", "呪弾", new MobSkillTiming(16.0D, 50L, 12L)),
        CURSE("mob_purple_tree_curse", "呪域", new MobSkillTiming(12.0D, 110L, 20L));

        private final String id;
        private final String displayName;
        private final MobSkillTiming timing;

        Kind(String id, String displayName, MobSkillTiming timing) {
            this.id = id;
            this.displayName = displayName;
            this.timing = timing;
        }
    }
}
