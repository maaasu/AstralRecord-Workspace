package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 周囲の全Mobへ上向きに膨らむ鎖を伸ばし、3回に分けて自身へ引き寄せるスキルです。 */
public final class PaladinGuardianChainExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_guardian_chain";

    /** 共有発動スキルサービスで初期化します。 */
    public PaladinGuardianChainExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "radius");
        requirePositiveInt(params, "pullCount");
        requirePositiveInt(params, "pullIntervalTicks");
        requirePositive(params, "minimumPullStrength");
        requirePositive(params, "maximumPullStrength");
        requirePositive(params, "verticalStrength");
        requirePositive(params, "curveHeight");
        requirePositiveInt(params, "curvePoints");
    }

    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double radius = params.getDouble("radius", 15.0D);
        int pullCount = params.getInt("pullCount", 3);
        int pullIntervalTicks = params.getInt("pullIntervalTicks", 20);
        double minimumStrength = params.getDouble("minimumPullStrength", 0.35D);
        double maximumStrength = params.getDouble("maximumPullStrength", 1.35D);
        double verticalStrength = params.getDouble("verticalStrength", 0.35D);
        double curveHeight = params.getDouble("curveHeight", 2.5D);
        int curvePoints = params.getInt("curvePoints", 20);
        Player player = context.player();
        World world = player.getWorld();
        context.services().tasks().repeat(
                player.getUniqueId(), ID, pullIntervalTicks, pullIntervalTicks, pullCount,
                pulse -> pull(
                        context, player, world, radius, minimumStrength,
                        maximumStrength, verticalStrength, curveHeight, curvePoints
                )
        );
        return context.success();
    }

    private void pull(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull World world,
            double radius,
            double minimumStrength,
            double maximumStrength,
            double verticalStrength,
            double curveHeight,
            int curvePoints
    ) {
        if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
            context.services().tasks().cancel(player.getUniqueId(), ID);
            return;
        }
        Location casterCenter = player.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        for (AstEntity target : context.services().targeting().inRadius(
                player, casterCenter, radius, radius, Integer.MAX_VALUE, false
        )) {
            Location targetCenter = target.location().clone().add(0.0D, 1.0D, 0.0D);
            renderChain(context, targetCenter, casterCenter, curveHeight, curvePoints);
            Vector horizontal = casterCenter.toVector().subtract(targetCenter.toVector()).setY(0.0D);
            double distance = Math.min(radius, horizontal.length());
            if (horizontal.lengthSquared() <= 1.0E-8D) {
                continue;
            }
            double strength = minimumStrength
                    + (maximumStrength - minimumStrength) * Math.clamp(distance / radius, 0.0D, 1.0D);
            Vector velocity = horizontal.normalize().multiply(strength).setY(verticalStrength);
            context.services().combat().velocity(target, velocity);
        }
    }

    private void renderChain(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location start,
            @NotNull Location end,
            double curveHeight,
            int curvePoints
    ) {
        Vector first = start.toVector();
        Vector third = end.toVector();
        Vector control = first.clone().add(third).multiply(0.5D).add(new Vector(0.0D, curveHeight, 0.0D));
        List<Location> points = new ArrayList<>(curvePoints);
        for (int index = 0; index < curvePoints; index++) {
            double t = curvePoints == 1 ? 1.0D : (double) index / (curvePoints - 1);
            double inverse = 1.0D - t;
            Vector point = first.clone().multiply(inverse * inverse)
                    .add(control.clone().multiply(2.0D * inverse * t))
                    .add(third.clone().multiply(t * t));
            points.add(point.toLocation(start.getWorld()));
        }
        context.services().effects().points(
                start, points, SharedParticleDefinitions.SKILL_PALADIN_GUARDIAN_CHAIN_LINE
        );
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ガーディアンチェインの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "ガーディアンチェインの params[" + key + "] は1以上が必要です");
        }
    }
}
