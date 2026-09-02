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
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 敵シールドを破壊し、実際に削った量の一部を自身へ移すシールドドレインです。 */
public final class SwordsmanShieldDrainExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_shield_drain";
    private static final double DEFAULT_RANGE = 8.0D;
    private static final double DEFAULT_TARGET_ANGLE = 110.0D;
    private static final int DEFAULT_MAX_TARGETS = 8;
    private static final int FAN_ARC_POINTS = 18;
    private static final double[] FAN_ARC_RADIUS_RATIOS = {0.25D, 0.50D, 0.75D, 1.0D};
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
        double targetAngle = params.getDouble("targetAngle", 0.0D);
        if (!(targetAngle > 0.0D && targetAngle <= 180.0D)) {
            throw new SkillParameterException("targetAngle", "シールドドレインの対象角度は0より大きく180以下が必要です");
        }
        if (params.getInt("maxTargets", 0) < 1) {
            throw new SkillParameterException("maxTargets", "シールドドレインの最大対象数は1以上が必要です");
        }
        requirePositive(params, "damageRatio");
        requirePositive(params, "shieldBreakMultiplier");
        double absorbRatio = params.getDouble("shieldAbsorbRatio", 0.0D);
        if (!(absorbRatio > 0.0D && absorbRatio <= 1.0D)) {
            throw new SkillParameterException("shieldAbsorbRatio", "シールド吸収率は0より大きく1以下が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", DEFAULT_RANGE);
        double targetAngle = params.getDouble("targetAngle", DEFAULT_TARGET_ANGLE);
        int maxTargets = params.getInt("maxTargets", DEFAULT_MAX_TARGETS);
        double damageRatio = params.getDouble("damageRatio", 0.975D);
        double shieldBreakMultiplier = params.getDouble("shieldBreakMultiplier", 3.0D);
        double absorbRatio = params.getDouble("shieldAbsorbRatio", 0.50D);

        Player player = context.player();
        List<AstEntity> targets = context.services().targeting()
                .inCone(player, range, targetAngle, maxTargets, true);
        Location origin = slashOrigin(context);
        renderSlash(context, origin, range, targetAngle);

        AstEntity attacker = context.attacker();
        List<Location> absorptionStarts = new ArrayList<>();
        for (AstEntity target : targets) {
            Location end = target.location().clone().add(0.0D, 0.9D, 0.0D);
            DamageResult result = context.services().combat().hit(
                    attacker,
                    target,
                    AttackType.MELEE,
                    DamageElement.NONE,
                    damageRatio,
                    shieldBreakMultiplier
            );
            if (result.shieldDamage() <= 0.0D) {
                continue;
            }
            context.services().effects().point(end, SharedParticleDefinitions.SHIELD_BREAK_DUST);
            double recovered = context.services().combat().recoverShield(attacker, result.shieldDamage() * absorbRatio);
            if (recovered > 0.0D) {
                absorptionStarts.add(end);
            }
        }
        if (!absorptionStarts.isEmpty()) {
            renderAbsorption(context, player, absorptionStarts);
        }
        return context.success();
    }

    private @NotNull Location slashOrigin(@NotNull PlayerActiveSkillContext context) {
        return context.eyeLocation().add(context.direction().multiply(0.35D)).subtract(0.0D, 0.25D, 0.0D);
    }

    /**
     * 発動者の視線方向へシールドドレインの扇形斬撃演出を表示します。
     *
     * @param context 発動者と演出サービスを保持するコンテキスト
     * @param origin 斬撃の開始位置
     * @param range 扇形の射程
     * @param targetAngle 扇形の全角
     */
    private void renderSlash(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location origin,
            double range,
            double targetAngle
    ) {
        double halfAngle = targetAngle / 2.0D;
        double previousRadius = 0.0D;
        for (double ratio : FAN_ARC_RADIUS_RATIOS) {
            double radius = Math.min(range, range * ratio);
            if (radius <= previousRadius + 1.0E-6D) {
                continue;
            }
            context.services().effects().viewArcSegment(
                    origin,
                    context.direction(),
                    radius,
                    -halfAngle,
                    halfAngle,
                    FAN_ARC_POINTS,
                    SharedParticleDefinitions.SHIELD_DRAIN_SLASH_DUST
            );
            previousRadius = radius;
        }
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.1F);
    }

    private void renderAbsorption(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull List<Location> targetCenters
    ) {
        List<Location> starts = targetCenters.stream().map(Location::clone).toList();
        context.services().tasks().repeat(player.getUniqueId(), ID + ":absorb", 1L, 1L, 4, frame -> {
            if (!player.isOnline()
                    || starts.stream().anyMatch(start -> player.getWorld() != start.getWorld())) {
                return;
            }
            Location arrival = player.getLocation().clone().add(0.0D, 1.0D, 0.0D);
            List<Location> particles = new ArrayList<>(starts.size());
            for (Location start : starts) {
                Vector travel = arrival.toVector().subtract(start.toVector());
                particles.add(start.clone().add(travel.multiply((frame + 1.0D) / 4.0D)));
            }
            context.services().effects().points(
                    arrival,
                    particles,
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
