package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 聖柱を維持し、範囲内のMobへ毎秒ランダムな断罪を落とすパラディンの発動スキルです。 */
public final class PaladinHolySmiteExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "paladin_holy_smite";
    private static final double DEFAULT_DAMAGE_RATIO = 0.50D;
    private static final double DEFAULT_RADIUS = 1.5D;
    private static final int DEFAULT_DURATION_TICKS = 20;
    private static final int DEFAULT_IMPACT_INTERVAL_TICKS = 20;
    private static final int DEFAULT_MAX_TARGETS = 1;
    private static final double DEFAULT_WEAKNESS_CHANCE = 5.0D;
    private static final int DEFAULT_WEAKNESS_DURATION_TICKS = 100;
    private static final double DEFAULT_WEAKNESS_STRENGTH = 1.0D;
    private static final double DEFAULT_PILLAR_HEIGHT = 5.0D;
    private static final double DEFAULT_SLASH_RING_RADIUS = 1.0D;
    private static final int CORE_DISPLAY_COUNT = 3;
    private static final int ALTAR_SUPPORT_COUNT = 4;
    private static final int LANTERN_UPDATE_INTERVAL_TICKS = 4;
    private static final double ALTAR_RADIUS = 1.15D;

    /** 共有発動スキルサービスで初期化します。 */
    public PaladinHolySmiteExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "damageRatio");
        requirePositive(params, "radius");
        requirePositiveInt(params, "durationTicks");
        requirePositiveInt(params, "impactIntervalTicks");
        requirePositiveInt(params, "maxTargets");
        requireRange(params, "weaknessChance", 0.0D, 100.0D);
        requirePositiveInt(params, "weaknessDurationTicks");
        requirePositive(params, "weaknessStrength");
        requirePositive(params, "pillarHeight");
        requirePositive(params, "slashRingRadius");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        double radius = params.getDouble("radius", DEFAULT_RADIUS);
        int durationTicks = params.getInt("durationTicks", DEFAULT_DURATION_TICKS);
        int impactIntervalTicks = params.getInt("impactIntervalTicks", DEFAULT_IMPACT_INTERVAL_TICKS);
        int maxTargets = params.getInt("maxTargets", DEFAULT_MAX_TARGETS);
        double pillarHeight = params.getDouble("pillarHeight", DEFAULT_PILLAR_HEIGHT);
        double slashRingRadius = params.getDouble("slashRingRadius", DEFAULT_SLASH_RING_RADIUS);
        ActiveSkillCondition weakness = new ActiveSkillCondition(
                ConditionType.WEAKNESS,
                params.getDouble("weaknessChance", DEFAULT_WEAKNESS_CHANCE),
                params.getInt("weaknessDurationTicks", DEFAULT_WEAKNESS_DURATION_TICKS),
                params.getDouble("weaknessStrength", DEFAULT_WEAKNESS_STRENGTH)
        );
        Location center = context.services().targeting().groundAt(context.player().getLocation(), 3, 16);
        HolyPillarState state = new HolyPillarState(center, pillarHeight);
        String scope = ID + ":" + UUID.randomUUID();
        try {
            state.spawnDisplays();
            context.services().tasks().repeat(
                    context.player().getUniqueId(),
                    scope,
                    0L,
                    1L,
                    durationTicks,
                    tick -> advance(
                            context,
                            state,
                            tick,
                            radius,
                            maxTargets,
                            damageRatio,
                            impactIntervalTicks,
                            weakness,
                            slashRingRadius
                    ),
                    state::destroy
            );
        } catch (RuntimeException exception) {
            state.destroy();
            throw exception;
        }
        return context.success();
    }

    private void advance(
            @NotNull PlayerActiveSkillContext context,
            @NotNull HolyPillarState state,
            int tick,
            double radius,
            int maxTargets,
            double damageRatio,
            int impactIntervalTicks,
            @NotNull ActiveSkillCondition weakness,
            double slashRingRadius
    ) {
        if (tick % LANTERN_UPDATE_INTERVAL_TICKS == 0) {
            state.updateDisplays(tick);
        }
        if ((tick & 1) == 0) {
            context.services().effects().line(
                    state.center(),
                    state.center().add(0.0D, state.pillarHeight(), 0.0D),
                    0.40D,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
            );
        }
        if (tick % 4 == 0) {
            context.services().effects().ring(
                    state.center().add(0.0D, 0.10D, 0.0D),
                    state.altarRadius() + 0.35D,
                    24,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
            );
        }
        if (tick % 6 == 0) {
            context.services().effects().ring(
                    state.center().add(0.0D, 1.65D, 0.0D),
                    state.altarRadius(),
                    16,
                    SharedParticleDefinitions.TELEPORTER_UNLOCK_RING_END_ROD
            );
        }
        if (tick % impactIntervalTicks != 0) {
            return;
        }
        List<AstEntity> candidates = new ArrayList<>(context.services().targeting().inSphere(
                context.player(), state.center(), radius, Integer.MAX_VALUE, true
        ));
        Collections.shuffle(candidates);
        int selectedCount = Math.min(maxTargets, candidates.size());
        for (int index = 0; index < selectedCount; index++) {
            AstEntity target = candidates.get(index);
            Location base = target.location();
            renderJudgement(context, state, base, slashRingRadius);
            context.services().combat().hit(
                    context.source().skill(),
                    context.attacker(), target, AttackType.MELEE, DamageElement.NONE, damageRatio, weakness
            );
        }
    }

    private void renderJudgement(
            @NotNull PlayerActiveSkillContext context,
            @NotNull HolyPillarState state,
            @NotNull Location base,
            double slashRingRadius
    ) {
        Location upperRing = base.clone().add(0.0D, state.pillarHeight(), 0.0D);
        context.services().effects().ring(
                upperRing,
                slashRingRadius,
                16,
                SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
        );
        context.services().effects().line(
                base.clone().add(0.0D, 0.10D, 0.0D),
                upperRing,
                0.30D,
                SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
        );
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ホーリースマイトの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "ホーリースマイトの params[" + key + "] は1以上の整数が必要です");
        }
    }

    private static void requireRange(@NotNull SkillParamReader params, @NotNull String key, double minimum, double maximum) {
        double value = params.getDouble(key, Double.NaN);
        if (!(value >= minimum && value <= maximum)) {
            throw new SkillParameterException(key, "ホーリースマイトの params[" + key + "] は"
                    + minimum + "以上" + maximum + "以下が必要です");
        }
    }

    /** 発動地点の小規模な祭壇・聖柱BlockDisplayを生成・破棄します。 */
    static final class HolyPillarState {
        private final Location center;
        private final double pillarHeight;
        private final List<BlockDisplay> displays = new ArrayList<>();
        private final List<BlockDisplay> floatingLanterns = new ArrayList<>();

        HolyPillarState(@NotNull Location center, double pillarHeight) {
            this.center = center.clone();
            this.pillarHeight = pillarHeight;
        }

        void spawnDisplays() {
            if (center.getWorld() == null) {
                return;
            }
            for (int index = 0; index < CORE_DISPLAY_COUNT; index++) {
                double height = pillarHeight * (index + 0.5D) / CORE_DISPLAY_COUNT;
                spawnDisplay(
                        center.clone().add(0.0D, height, 0.0D),
                        Material.WHITE_STAINED_GLASS,
                        0.32F,
                        (float) pillarHeight / CORE_DISPLAY_COUNT,
                        0.32F,
                        0.0F
                );
            }
            spawnDisplay(
                    center.clone().add(0.0D, pillarHeight * 0.52D, 0.0D),
                    Material.SEA_LANTERN,
                    0.60F,
                    0.60F,
                    0.60F,
                    0.0F
            );
            for (int index = 0; index < ALTAR_SUPPORT_COUNT; index++) {
                double angle = Math.PI * 2.0D * index / ALTAR_SUPPORT_COUNT;
                Location support = center.clone().add(
                        Math.cos(angle) * ALTAR_RADIUS,
                        0.58D,
                        Math.sin(angle) * ALTAR_RADIUS
                );
                spawnDisplay(support, Material.QUARTZ_PILLAR, 0.48F, 1.16F, 0.48F, (float) angle);
                BlockDisplay lantern = spawnDisplay(
                        support.clone().add(0.0D, 0.86D, 0.0D),
                        Material.SEA_LANTERN,
                        0.52F,
                        0.52F,
                        0.52F,
                        (float) angle
                );
                floatingLanterns.add(lantern);
            }
        }

        void updateDisplays(int tick) {
            for (int index = 0; index < floatingLanterns.size(); index++) {
                BlockDisplay lantern = floatingLanterns.get(index);
                if (!lantern.isValid()) {
                    continue;
                }
                double angle = Math.PI * 2.0D * index / ALTAR_SUPPORT_COUNT;
                double bob = Math.sin(tick * 0.14D + index * Math.PI * 0.5D) * 0.07D;
                lantern.teleport(center.clone().add(
                        Math.cos(angle) * ALTAR_RADIUS,
                        1.44D + bob,
                        Math.sin(angle) * ALTAR_RADIUS
                ));
                lantern.setTransformation(transformation(0.52F, 0.52F, 0.52F, tick * 0.05F + (float) angle));
            }
        }

        private @NotNull BlockDisplay spawnDisplay(
                @NotNull Location location,
                @NotNull Material material,
                float scaleX,
                float scaleY,
                float scaleZ,
                float yaw
        ) {
            BlockDisplay display = center.getWorld().spawn(location, BlockDisplay.class, entity -> {
                entity.setBlock(material.createBlockData());
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(false);
                entity.setTeleportDuration(LANTERN_UPDATE_INTERVAL_TICKS);
                entity.setInterpolationDuration(LANTERN_UPDATE_INTERVAL_TICKS);
                entity.setBrightness(new Display.Brightness(15, 15));
            });
            display.setTransformation(transformation(scaleX, scaleY, scaleZ, yaw));
            displays.add(display);
            return display;
        }

        private static @NotNull Transformation transformation(float scaleX, float scaleY, float scaleZ, float yaw) {
            return new Transformation(
                    new Vector3f(-scaleX / 2.0F, -scaleY / 2.0F, -scaleZ / 2.0F),
                    new Quaternionf().rotateY(yaw),
                    new Vector3f(scaleX, scaleY, scaleZ),
                    new Quaternionf()
            );
        }

        void destroy() {
            displays.stream().filter(Entity::isValid).forEach(Entity::remove);
            displays.clear();
            floatingLanterns.clear();
        }

        @NotNull Location center() {
            return center.clone();
        }

        double pillarHeight() {
            return pillarHeight;
        }

        double altarRadius() {
            return ALTAR_RADIUS;
        }
    }
}
