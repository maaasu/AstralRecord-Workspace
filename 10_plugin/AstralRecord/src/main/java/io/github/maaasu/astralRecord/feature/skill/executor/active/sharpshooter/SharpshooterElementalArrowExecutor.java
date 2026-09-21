package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.InheritanceBuffService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/** シャープシューターの重力なし直線属性矢に共通する実行処理です。 */
abstract class SharpshooterElementalArrowExecutor extends PlayerActiveSkillExecutor {

    private final DamageElement element;
    private final ConditionType conditionType;
    private final double defaultConditionChance;
    private final double defaultInheritedConditionChance;
    private final SharedParticleDefinition trail;
    private final SharedParticleDefinition impact;
    private final Sound launchSound;
    private final boolean iceBlockDisplay;
    private InheritanceBuffService inheritanceBuffService;

    /** 属性矢固有の戦闘・演出値を受け取って初期化します。 */
    protected SharpshooterElementalArrowExecutor(
            @NotNull String implementationId,
            @NotNull ActiveSkillServices services,
            @NotNull DamageElement element,
            @NotNull ConditionType conditionType,
            double defaultConditionChance,
            double defaultInheritedConditionChance,
            @NotNull SharedParticleDefinition trail,
            @NotNull SharedParticleDefinition impact,
            @NotNull Sound launchSound,
            boolean iceBlockDisplay
    ) {
        super(implementationId, services);
        this.element = element;
        this.conditionType = conditionType;
        this.defaultConditionChance = defaultConditionChance;
        this.defaultInheritedConditionChance = defaultInheritedConditionChance;
        this.trail = trail;
        this.impact = impact;
        this.launchSound = launchSound;
        this.iceBlockDisplay = iceBlockDisplay;
    }

    /**
     * 継承バフを管理するサービスを設定します。
     *
     * @param service 継承バフサービス
     */
    public final void setInheritanceBuffService(@NotNull InheritanceBuffService service) {
        this.inheritanceBuffService = service;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "damageRatio");
        requirePositive(params, "projectileSpeed");
        requirePositive(params, "projectileHitRadius");
        requirePositiveInteger(params, "conditionDurationTicks");
        requirePercentage(params, "conditionChance");
        requirePercentage(params, "inheritedConditionChance");
    }

    /** {@inheritDoc} */
    @Override
    protected final @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 16.0D);
        double damageRatio = params.getDouble("damageRatio", 1.15D);
        double projectileSpeed = params.getDouble("projectileSpeed", 1.6D);
        double projectileHitRadius = params.getDouble("projectileHitRadius", 0.6D);
        double conditionChance = params.getDouble("conditionChance", defaultConditionChance);
        double inheritedConditionChance = params.getDouble(
                "inheritedConditionChance", defaultInheritedConditionChance
        );
        int conditionDurationTicks = params.getInt(
                "conditionDurationTicks", (int) conditionType.defaultDurationTicks()
        );
        ActiveSkillCondition directCondition = new ActiveSkillCondition(
                conditionType, conditionChance, conditionDurationTicks, 1.0D
        );
        ActiveSkillCondition inheritedCondition = new ActiveSkillCondition(
                conditionType, inheritedConditionChance, conditionDurationTicks, 1.0D
        );
        AstEntity attacker = context.attacker();
        Location origin = context.eyeLocation();
        Vector direction = context.direction();
        DisplayHandle display = iceBlockDisplay
                ? spawnIceDisplay(context, origin, direction, range, projectileSpeed)
                : null;

        context.services().projectiles().launchWithTermination(
                context.player(),
                origin,
                direction,
                new SkillProjectileSpec(
                        range, projectileSpeed, projectileHitRadius, false, 1, trail, impact
                ),
                (target, ignored) -> context.services().combat().hit(
                        context.source().skill(),
                        attacker,
                        target,
                        AttackType.RANGED,
                        element,
                        damageRatio,
                        directCondition
                ),
                ignored -> stopDisplay(context, display)
        );
        context.services().effects().sound(origin, launchSound, 1.05F, iceBlockDisplay ? 1.25F : 0.95F);
        if (inheritanceBuffService != null) {
            inheritanceBuffService.grantElementalAttack(context, element, inheritedCondition);
        }
        return context.success();
    }

    /** 小型の氷BlockDisplayを仮想飛翔体と同じ速度で移動させます。 */
    private @Nullable DisplayHandle spawnIceDisplay(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location origin,
            @NotNull Vector direction,
            double range,
            double speed
    ) {
        if (origin.getWorld() == null) {
            return null;
        }
        BlockDisplay display = origin.getWorld().spawn(origin, BlockDisplay.class, entity -> {
            entity.setBlock(Material.ICE.createBlockData());
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setSilent(true);
            entity.setTeleportDuration(1);
            entity.setInterpolationDuration(1);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setViewRange(32.0F);
            entity.setTransformation(new Transformation(
                    new Vector3f(-0.14F, -0.14F, -0.14F),
                    new Quaternionf(),
                    new Vector3f(0.28F, 0.28F, 0.28F),
                    new Quaternionf()
            ));
        });
        String scope = "sharpshooter-ice-arrow-display:" + UUID.randomUUID();
        Location current = origin.clone();
        Vector step = direction.clone().normalize().multiply(speed);
        int maxTicks = Math.max(1, (int) Math.ceil(range / speed) + 1);
        context.services().tasks().repeat(
                context.caster().casterId(),
                scope,
                0L,
                1L,
                maxTicks,
                ignored -> {
                    if (display.isValid()) {
                        current.add(step);
                        display.teleport(current);
                    }
                },
                () -> removeDisplay(display)
        );
        return new DisplayHandle(scope, display);
    }

    /** 飛翔体終了時に表示更新taskを止め、BlockDisplayを除去します。 */
    private void stopDisplay(@NotNull PlayerActiveSkillContext context, @Nullable DisplayHandle handle) {
        if (handle == null) {
            return;
        }
        context.services().tasks().cancel(context.caster().casterId(), handle.scope());
        removeDisplay(handle.display());
    }

    private static void removeDisplay(@NotNull BlockDisplay display) {
        if (display.isValid()) {
            display.remove();
        }
    }

    private void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, implementationId() + " の params[" + key + "] は正数が必要です");
        }
    }

    private void requirePositiveInteger(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, implementationId() + " の params[" + key + "] は1以上の整数が必要です");
        }
    }

    private void requirePercentage(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, -1.0D);
        if (!Double.isFinite(value) || value < 0.0D || value > 100.0D) {
            throw new SkillParameterException(key, implementationId() + " の params[" + key + "] は0以上100以下が必要です");
        }
    }

    /** 氷表示の追跡task識別子とEntityを保持します。 */
    private record DisplayHandle(@NotNull String scope, @NotNull BlockDisplay display) {
    }
}
