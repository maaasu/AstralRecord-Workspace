package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileInterception;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileInterceptor;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** ウィザードのプリズム表示、飛翔体の吸収判定と属性別追撃を管理します。 */
public final class ElementalPrismRuntimeService {

    private static final String TASK_SCOPE = "wizard-elemental-prism";
    private static final double HIT_HEIGHT = 1.45D;
    private static final double HIT_RADIUS = 0.82D;
    private static final int DISPLAY_INTERVAL_TICKS = 8;
    private final Map<UUID, PrismState> prisms = new HashMap<>();

    /**
     * 設置者のプリズムを置き換え、持続時間中の表示と後始末を登録します。
     *
     * @param context 設置時の発動情報
     * @param base 地表の設置中心
     * @param radius 追撃対象の球形半径
     * @param damageRatio 追撃1発の魔法攻撃倍率
     * @param maxTargets 抽選候補に含める敵の最大数
     * @param projectileCount 1回の吸収で放つ追撃数
     * @param durationTicks 持続時間
     * @param projectileSpeed 追撃弾の1tickあたりの速度
     */
    public void place(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location base,
            double radius,
            double damageRatio,
            int maxTargets,
            int projectileCount,
            int durationTicks,
            double projectileSpeed
    ) {
        UUID ownerId = context.player().getUniqueId();
        PrismState state = new PrismState(
                base, context.source().skill(), radius, damageRatio,
                maxTargets, projectileCount, projectileSpeed
        );
        try {
            state.spawnDisplays();
        } catch (RuntimeException exception) {
            state.destroy();
            throw exception;
        }
        PrismState previous = prisms.put(ownerId, state);
        try {
            context.services().tasks().repeat(
                    ownerId, TASK_SCOPE, 0L, 1L, durationTicks,
                    tick -> {
                        if (tick % DISPLAY_INTERVAL_TICKS == 0) {
                            state.render(context.services().effects(), tick);
                        }
                    },
                    () -> {
                        prisms.remove(ownerId, state);
                        state.destroy();
                    }
            );
        } catch (RuntimeException exception) {
            if (previous == null) {
                prisms.remove(ownerId, state);
            } else {
                prisms.put(ownerId, previous);
            }
            state.destroy();
            throw exception;
        }
        context.services().effects().sound(state.hitCenter(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8F, 1.3F);
    }

    /**
     * タグ付き攻撃魔法にだけ、設置者本人のプリズムを調べる飛翔体判定を返します。
     *
     * @param context 攻撃魔法の発動情報
     * @param element 攻撃魔法の属性
     * @return 飛翔体判定。タグや属性が対象外ならnull
     */
    public @Nullable SkillProjectileInterceptor interceptor(
            @NotNull PlayerActiveSkillContext context,
            @NotNull DamageElement element
    ) {
        boolean tagged = context.source().skill().getTags().stream()
                .anyMatch(MasterTagIds.Activity.PRISM::equalsIgnoreCase);
        if (!tagged || !supportedElement(element)) {
            return null;
        }
        UUID ownerId = context.player().getUniqueId();
        return (origin, direction, maxDistance, projectileRadius) -> {
            PrismState state = prisms.get(ownerId);
            if (state == null || !state.active || state.base.getWorld() != origin.getWorld()) {
                return null;
            }
            double distance = firstSphereHit(
                    origin, direction, state.hitCenter(), HIT_RADIUS + projectileRadius, maxDistance
            );
            if (!Double.isFinite(distance)) {
                return null;
            }
            Location hit = origin.clone().add(direction.clone().multiply(distance));
            return new SkillProjectileInterception(hit, () -> refract(context, state, element));
        };
    }

    private static boolean supportedElement(@NotNull DamageElement element) {
        return element == DamageElement.NONE || element == DamageElement.FIRE
                || element == DamageElement.ICE || element == DamageElement.LIGHTNING;
    }

    private static double firstSphereHit(
            @NotNull Location origin,
            @NotNull Vector direction,
            @NotNull Location center,
            double radius,
            double maxDistance
    ) {
        if (maxDistance <= 0.0D) {
            return Double.NaN;
        }
        Vector offset = origin.toVector().subtract(center.toVector());
        double along = offset.dot(direction);
        double discriminant = along * along - (offset.lengthSquared() - radius * radius);
        if (discriminant < 0.0D) {
            return Double.NaN;
        }
        double root = Math.sqrt(discriminant);
        if (-along + root < 0.0D) {
            return Double.NaN;
        }
        double distance = Math.max(0.0D, -along - root);
        return distance < maxDistance ? distance : Double.NaN;
    }

    private void refract(
            @NotNull PlayerActiveSkillContext context,
            @NotNull PrismState state,
            @NotNull DamageElement element
    ) {
        UUID ownerId = context.player().getUniqueId();
        if (prisms.get(ownerId) != state || !state.active) {
            return;
        }
        ActiveSkillServices services = context.services();
        Location origin = state.hitCenter();
        services.effects().ring(origin, 1.0D, 18, SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST);
        services.effects().sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8F, 1.7F);
        List<AstEntity> candidates = new ArrayList<>(services.targeting().inSphere(
                context.player(), origin, state.radius, Integer.MAX_VALUE, true
        ));
        if (candidates.isEmpty()) {
            return;
        }
        Collections.shuffle(candidates);
        List<AstEntity> targets = candidates.subList(0, Math.min(state.maxTargets, candidates.size()));
        AstEntity attacker = context.attacker();
        Location previousLightningHit = origin;
        for (int index = 0; index < state.projectileCount; index++) {
            AstEntity target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
            Location targetCenter = target.location().add(0.0D, 1.0D, 0.0D);
            if (element == DamageElement.LIGHTNING) {
                Location from = previousLightningHit.distanceSquared(targetCenter) < 0.01D
                        ? origin : previousLightningHit;
                services.effects().line(from, targetCenter, 0.25D, SharedParticleDefinitions.SKILL_MAGE_LIGHTNING);
                services.effects().point(targetCenter, SharedParticleDefinitions.SKILL_MAGE_LIGHTNING);
                services.combat().hit(
                        state.skill, attacker, target, AttackType.MAGIC, element, state.damageRatio
                );
                previousLightningHit = targetCenter;
            } else {
                launchBolt(context, state, attacker, target, origin, targetCenter, element);
            }
        }
        if (element == DamageElement.LIGHTNING) {
            services.effects().sound(origin, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.65F, 1.55F);
        }
    }

    private static void launchBolt(
            @NotNull PlayerActiveSkillContext context,
            @NotNull PrismState state,
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull Location origin,
            @NotNull Location targetCenter,
            @NotNull DamageElement element
    ) {
        Vector direction = targetCenter.toVector().subtract(origin.toVector());
        if (direction.lengthSquared() <= 1.0E-8D) {
            direction = new Vector(0.0D, 1.0D, 0.0D);
        }
        SharedParticleDefinition trail = switch (element) {
            case FIRE -> SharedParticleDefinitions.WIZARD_PRISM_FIRE_DUST;
            case ICE -> SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST;
            default -> SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST;
        };
        SharedParticleDefinition impact = switch (element) {
            case FIRE -> SharedParticleDefinitions.SKILL_MAGE_FIRE;
            case ICE -> SharedParticleDefinitions.SKILL_MAGE_ICE;
            default -> SharedParticleDefinitions.MAGIC_IMPACT_DUST;
        };
        SkillProjectileSpec spec = new SkillProjectileSpec(
                state.radius + 2.0D, state.projectileSpeed, 0.35D,
                false, 1, trail, impact
        );
        context.services().projectiles().launchAtTarget(
                context.player(), origin, target.id(), direction.normalize(), spec,
                (hitTarget, ignoredImpact) -> context.services().combat().hit(
                        state.skill, attacker, hitTarget, AttackType.MAGIC, element, state.damageRatio
                ),
                ignored -> { }
        );
    }

    /** BlockDisplayによる結晶と粒子による逆三角錐・三属性の環を描画します。 */
    private static final class PrismState {
        private final Location base;
        private final SkillDefinition skill;
        private final double radius;
        private final double damageRatio;
        private final int maxTargets;
        private final int projectileCount;
        private final double projectileSpeed;
        private final List<BlockDisplay> displays = new ArrayList<>();
        private BlockDisplay topCrystal;
        private boolean active;

        private PrismState(
                @NotNull Location base,
                @NotNull SkillDefinition skill,
                double radius,
                double damageRatio,
                int maxTargets,
                int projectileCount,
                double projectileSpeed
        ) {
            this.base = base.clone();
            this.skill = skill;
            this.radius = radius;
            this.damageRatio = damageRatio;
            this.maxTargets = maxTargets;
            this.projectileCount = projectileCount;
            this.projectileSpeed = projectileSpeed;
        }

        private @NotNull Location hitCenter() {
            return base.clone().add(0.0D, HIT_HEIGHT, 0.0D);
        }

        private void spawnDisplays() {
            World world = base.getWorld();
            if (world == null) {
                return;
            }
            active = true;
            spawn(base.clone().add(0.0D, 0.14D, 0.0D), Material.AMETHYST_BLOCK,
                    0.62F, 0.14F, 0.62F, 0.0F, 0.0F);
            spawn(base.clone().add(0.0D, 1.90D, 0.0D), Material.PURPLE_STAINED_GLASS,
                    1.15F, 0.30F, 1.15F, 0.78F, 0.0F);
            spawn(base.clone().add(0.0D, 1.52D, 0.0D), Material.ORANGE_STAINED_GLASS,
                    0.82F, 0.46F, 0.82F, 0.78F, 0.0F);
            spawn(base.clone().add(0.0D, 1.16D, 0.0D), Material.LIGHT_BLUE_STAINED_GLASS,
                    0.52F, 0.42F, 0.52F, 0.78F, 0.0F);
            spawn(base.clone().add(0.0D, 0.86D, 0.0D), Material.PURPLE_STAINED_GLASS,
                    0.25F, 0.36F, 0.25F, 0.78F, 0.0F);
            topCrystal = spawn(base.clone().add(0.0D, 2.82D, 0.0D), Material.PURPLE_STAINED_GLASS,
                    0.40F, 0.40F, 0.40F, 0.0F, 0.78F);
            spawn(base.clone().add(0.0D, 0.30D, 0.0D), Material.PURPLE_STAINED_GLASS,
                    0.30F, 0.30F, 0.30F, 0.0F, 0.78F);
            for (int index = 0; index < 4; index++) {
                double angle = Math.PI * 2.0D * index / 4.0D;
                Material material = index == 0 ? Material.ORANGE_STAINED_GLASS
                        : index == 2 ? Material.LIGHT_BLUE_STAINED_GLASS
                        : Material.PURPLE_STAINED_GLASS;
                spawn(base.clone().add(Math.cos(angle) * 1.07D, 1.45D, Math.sin(angle) * 1.07D),
                        material, 0.18F, 0.95F, 0.42F, (float) angle, 0.18F);
            }
        }

        private @NotNull BlockDisplay spawn(
                @NotNull Location location,
                @NotNull Material material,
                float scaleX,
                float scaleY,
                float scaleZ,
                float yaw,
                float tilt
        ) {
            BlockDisplay display = base.getWorld().spawn(location, BlockDisplay.class, entity -> {
                entity.setBlock(material.createBlockData());
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(false);
                entity.setTeleportDuration(DISPLAY_INTERVAL_TICKS);
                entity.setInterpolationDuration(DISPLAY_INTERVAL_TICKS);
                entity.setBrightness(new Display.Brightness(15, 15));
            });
            display.setTransformation(transformation(scaleX, scaleY, scaleZ, yaw, tilt));
            displays.add(display);
            return display;
        }

        private static @NotNull Transformation transformation(
                float scaleX, float scaleY, float scaleZ, float yaw, float tilt
        ) {
            return new Transformation(
                    new Vector3f(-scaleX / 2.0F, -scaleY / 2.0F, -scaleZ / 2.0F),
                    new Quaternionf().rotateY(yaw).rotateZ(tilt),
                    new Vector3f(scaleX, scaleY, scaleZ),
                    new Quaternionf()
            );
        }

        private void render(@NotNull SkillEffectService effects, int tick) {
            if (!active || base.getWorld() == null || base.getWorld().getPlayers().stream()
                    .noneMatch(player -> player.getLocation().distanceSquared(base) <= 48.0D * 48.0D)) {
                return;
            }
            if (base.getWorld().isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)
                    && displays.stream().anyMatch(display -> !display.isValid())) {
                destroy();
                spawnDisplays();
            }
            if (topCrystal != null && topCrystal.isValid()) {
                topCrystal.setTransformation(transformation(
                        0.40F, 0.40F, 0.40F, tick * 0.025F, 0.78F
                ));
            }
            effects.ring(base.clone().add(0.0D, 0.57D, 0.0D), 1.20D, 20,
                    SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST);
            effects.ring(base.clone().add(0.0D, 2.30D, 0.0D), 1.20D, 20,
                    SharedParticleDefinitions.TELEPORTER_UNLOCK_RING_END_ROD);
            Location apex = base.clone().add(0.0D, 0.62D, 0.0D);
            List<SkillEffectLineSegment> outline = new ArrayList<>(6);
            Location[] top = new Location[3];
            for (int index = 0; index < 3; index++) {
                double angle = Math.PI * 2.0D * index / 3.0D + tick * 0.006D;
                top[index] = base.clone().add(
                        Math.cos(angle) * 1.05D, 2.08D, Math.sin(angle) * 1.05D
                );
                outline.add(new SkillEffectLineSegment(apex, top[index]));
            }
            for (int index = 0; index < 3; index++) {
                outline.add(new SkillEffectLineSegment(top[index], top[(index + 1) % 3]));
            }
            effects.lines(hitCenter(), outline, 0.32D, SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST);
            effects.points(hitCenter(), sideLights(-1.08D, tick),
                    SharedParticleDefinitions.WIZARD_PRISM_FIRE_DUST);
            effects.points(hitCenter(), sideLights(1.08D, tick),
                    SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST);
            effects.points(hitCenter(), sideSparks(tick), SharedParticleDefinitions.SKILL_MAGE_LIGHTNING);
        }

        private @NotNull List<Location> sideLights(double x, int tick) {
            List<Location> lights = new ArrayList<>(5);
            for (int index = 0; index < 5; index++) {
                lights.add(base.clone().add(x, 1.0D + index * 0.25D,
                        Math.sin(tick * 0.04D + index) * 0.10D));
            }
            return lights;
        }

        private @NotNull List<Location> sideSparks(int tick) {
            List<Location> sparks = new ArrayList<>(4);
            for (int index = 0; index < 4; index++) {
                double angle = tick * 0.035D + Math.PI * 2.0D * index / 4.0D;
                sparks.add(base.clone().add(Math.cos(angle) * 1.25D, 0.85D,
                        Math.sin(angle) * 1.25D));
            }
            return sparks;
        }

        private void destroy() {
            active = false;
            displays.stream().filter(Entity::isValid).forEach(Entity::remove);
            displays.clear();
        }
    }
}
