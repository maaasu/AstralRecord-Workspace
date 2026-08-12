package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** 敵シールドを破壊し、実際に削った量の一部を自身へ移すシールドドレインです。 */
public final class SwordsmanShieldDrainExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_shield_drain";
    private static final double FULL_SHIELD_EPSILON = 1.0E-6D;

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanShieldDrainExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "targetAngle");
        requirePositive(params, "damageRatio");
        requirePositive(params, "shieldBreakMultiplier");
        double absorbRatio = params.getDouble("shieldAbsorbRatio", 0.0D);
        double fullBonus = params.getDouble("fullShieldDamageBonus", 0.0D);
        if (!(absorbRatio > 0.0D && absorbRatio <= 1.0D)) {
            throw new SkillParameterException("shieldAbsorbRatio", "シールド吸収率は0より大きく1以下が必要です");
        }
        if (!(fullBonus >= 0.0D)) {
            throw new SkillParameterException("fullShieldDamageBonus", "最大時ダメージ加算率は0以上が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 6.0D);
        double targetAngle = params.getDouble("targetAngle", 40.0D);
        double damageRatio = params.getDouble("damageRatio", 0.65D);
        double shieldBreakMultiplier = params.getDouble("shieldBreakMultiplier", 3.0D);
        double absorbRatio = params.getDouble("shieldAbsorbRatio", 0.50D);
        double fullBonus = params.getDouble("fullShieldDamageBonus", 1.0D);
        boolean fullShieldAtCast = isFullShieldAtCast(context);
        double effectiveRatio = damageRatio * (1.0D + (fullShieldAtCast ? fullBonus : 0.0D));

        Player player = context.player();
        AstEntity target = context.services().targeting()
                .inCone(player, range, targetAngle, 1, true)
                .stream()
                .findFirst()
                .orElse(null);
        Location origin = slashOrigin(context);
        Location end = target == null
                ? origin.clone().add(context.direction().multiply(range))
                : target.location().clone().add(0.0D, 0.9D, 0.0D);
        renderSlash(context, origin, end, fullShieldAtCast);
        if (target == null) {
            return context.success();
        }

        AstEntity attacker = context.attacker();
        DamageResult result = context.services().combat().hit(
                attacker,
                target,
                AttackType.MELEE,
                DamageElement.NONE,
                effectiveRatio,
                shieldBreakMultiplier
        );
        if (result.shieldDamage() <= 0.0D) {
            return context.success();
        }
        context.services().effects().point(end, SharedParticleDefinitions.SHIELD_BREAK_DUST);
        if (fullShieldAtCast) {
            return context.success();
        }

        double recovered = context.services().combat().recoverShield(attacker, result.shieldDamage() * absorbRatio);
        if (recovered > 0.0D) {
            renderAbsorption(context, player, end);
        }
        return context.success();
    }

    private boolean isFullShieldAtCast(@NotNull PlayerActiveSkillContext context) {
        double maximum = context.source().statusSnapshot().getMaxValue(StatusType.MAX_SHIELD);
        double current = context.source().statusSnapshot().getCurrentShield();
        return maximum > 0.0D && current + FULL_SHIELD_EPSILON >= maximum;
    }

    private @NotNull Location slashOrigin(@NotNull PlayerActiveSkillContext context) {
        return context.eyeLocation().add(context.direction().multiply(0.35D)).subtract(0.0D, 0.25D, 0.0D);
    }

    private void renderSlash(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location origin,
            @NotNull Location end,
            boolean emphasized
    ) {
        context.services().effects().line(
                origin,
                end,
                emphasized ? 0.16D : 0.24D,
                SharedParticleDefinitions.SHIELD_DRAIN_SLASH_DUST
        );
        context.services().effects().viewArcSegment(
                origin,
                context.direction(),
                emphasized ? 1.35D : 1.0D,
                35.0D,
                -35.0D,
                emphasized ? 13 : 9,
                emphasized
                        ? SharedParticleDefinitions.SHIELD_DRAIN_FOCUS_SPARK
                        : SharedParticleDefinitions.SHIELD_DRAIN_SLASH_DUST
        );
        if (emphasized) {
            context.services().effects().ring(
                    origin.clone().subtract(0.0D, 0.25D, 0.0D),
                    0.65D,
                    14,
                    SharedParticleDefinitions.SHIELD_DRAIN_FOCUS_SPARK
            );
        }
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, emphasized ? 1.35F : 1.1F);
    }

    private void renderAbsorption(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull Location targetCenter
    ) {
        Location start = targetCenter.clone();
        context.services().tasks().repeat(player.getUniqueId(), ID + ":absorb", 1L, 1L, 4, frame -> {
            if (!player.isOnline() || player.getWorld() != start.getWorld()) {
                return;
            }
            Location arrival = player.getLocation().clone().add(0.0D, 1.0D, 0.0D);
            Vector travel = arrival.toVector().subtract(start.toVector());
            Location particle = start.clone().add(travel.multiply((frame + 1.0D) / 4.0D));
            context.services().effects().point(
                    particle,
                    SharedParticleDefinitions.SHIELD_DRAIN_ABSORB_END_ROD
            );
            if (frame < 3) {
                return;
            }
            context.services().effects().ring(
                    player.getLocation().clone().add(0.0D, 0.95D, 0.0D),
                    0.85D,
                    18,
                    SharedParticleDefinitions.SHIELD_DRAIN_RING_DUST
            );
            context.services().effects().sound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 0.8F, 1.45F);
        });
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "シールドドレインの params[" + key + "] は正数が必要です");
        }
    }
}
