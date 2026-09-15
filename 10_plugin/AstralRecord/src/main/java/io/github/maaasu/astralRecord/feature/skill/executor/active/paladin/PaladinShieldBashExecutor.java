package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
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
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 水色の盾面を視点方向へ前進させ、接触した敵を攻撃・減速・ノックバックするスキルです。 */
public final class PaladinShieldBashExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_shield_bash";

    /** 共有発動スキルサービスで初期化します。 */
    public PaladinShieldBashExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "width");
        requirePositive(params, "height");
        requirePositiveInt(params, "travelTicks");
        requirePositiveInt(params, "hitCooldownTicks");
        requirePositive(params, "damageRatio");
        requireRange(params, "movementSpeedReductionRatio", 0.0D, 1.0D);
        requirePositiveInt(params, "slowDurationTicks");
        requirePositive(params, "knockbackStrength");
    }

    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 5.0D);
        double width = params.getDouble("width", 1.0D);
        double height = params.getDouble("height", 2.0D);
        int travelTicks = params.getInt("travelTicks", 15);
        int hitCooldownTicks = params.getInt("hitCooldownTicks", 3);
        double damageRatio = params.getDouble("damageRatio", 0.10D);
        double speedReductionRatio = params.getDouble("movementSpeedReductionRatio", 0.90D);
        int slowDurationTicks = params.getInt("slowDurationTicks", 60);
        double knockbackStrength = params.getDouble("knockbackStrength", 1.0D);

        Player player = context.player();
        World world = player.getWorld();
        Location origin = player.getLocation().clone().add(0.0D, height * 0.5D, 0.0D);
        Vector direction = context.direction();
        context.services().effects().sound(origin, Sound.ITEM_SHIELD_BLOCK, 0.95F, 1.35F);
        context.services().effects().sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.65F, 1.45F);
        Vector horizontal = direction.clone().setY(0.0D);
        if (horizontal.lengthSquared() <= 1.0E-8D) {
            horizontal.setZ(1.0D);
        }
        horizontal.normalize();
        Vector right = horizontal.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D)).normalize();
        Map<UUID, Integer> lastHitFrames = new HashMap<>();
        context.services().tasks().repeat(
                player.getUniqueId(), ID, 0L, 1L, travelTicks,
                frame -> advance(
                        context, player, world, origin, direction, right, frame,
                        range, width, height, travelTicks, hitCooldownTicks, damageRatio,
                        speedReductionRatio, slowDurationTicks, knockbackStrength, lastHitFrames
                )
        );
        return context.success();
    }

    private void advance(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull World world,
            @NotNull Location origin,
            @NotNull Vector direction,
            @NotNull Vector right,
            int frame,
            double range,
            double width,
            double height,
            int travelTicks,
            int hitCooldownTicks,
            double damageRatio,
            double speedReductionRatio,
            int slowDurationTicks,
            double knockbackStrength,
            @NotNull Map<UUID, Integer> lastHitFrames
    ) {
        if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
            context.services().tasks().cancel(player.getUniqueId(), ID);
            return;
        }
        double distance = range * (frame + 1.0D) / travelTicks;
        Location center = origin.clone().add(direction.clone().multiply(distance));
        renderShield(context, center, right, width, height);

        double hitRadius = Math.max(0.5D, width * 0.60D);
        List<AstEntity> targets = context.services().targeting().inRadius(
                player, center, hitRadius, height * 0.55D, Integer.MAX_VALUE, false
        );
        for (AstEntity target : targets) {
            Integer lastFrame = lastHitFrames.get(target.id());
            if (lastFrame != null && frame - lastFrame < hitCooldownTicks) {
                continue;
            }
            lastHitFrames.put(target.id(), frame);
            context.services().combat().hit(
                    context.source().skill(), context.attacker(), target,
                    AttackType.MELEE, DamageElement.NONE, damageRatio
            );
            double baseSpeed = target.statValue(StatusType.MOVEMENT_SPEED);
            if (!(baseSpeed > 0.0D)) {
                baseSpeed = 100.0D;
            }
            context.services().combat().applyTemporaryMovementSpeedReduction(
                    target, baseSpeed * speedReductionRatio, slowDurationTicks
            );
            context.services().combat().knockback(
                    target, center.clone().subtract(direction), knockbackStrength, 0.12D
            );
        }
    }

    private void renderShield(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location center,
            @NotNull Vector right,
            double width,
            double height
    ) {
        List<Location> points = new ArrayList<>();
        for (int horizontalIndex = -2; horizontalIndex <= 2; horizontalIndex++) {
            for (int verticalIndex = -4; verticalIndex <= 4; verticalIndex++) {
                double x = horizontalIndex * width / 4.0D;
                double y = verticalIndex * height / 8.0D;
                boolean border = Math.abs(horizontalIndex) == 2 || Math.abs(verticalIndex) == 4;
                if (border || (horizontalIndex == 0 && verticalIndex % 2 == 0)) {
                    points.add(center.clone().add(right.clone().multiply(x)).add(0.0D, y, 0.0D));
                }
            }
        }
        context.services().effects().points(center, points, SharedParticleDefinitions.SKILL_PALADIN_SHIELD_BASH_DUST);
        context.services().effects().points(center, points, SharedParticleDefinitions.SKILL_PALADIN_SHIELD_BASH_CRIT);
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "シールドバッシュの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "シールドバッシュの params[" + key + "] は1以上が必要です");
        }
    }

    private static void requireRange(
            @NotNull SkillParamReader params, @NotNull String key, double minimum, double maximum
    ) {
        double value = params.getDouble(key, Double.NaN);
        if (!(value >= minimum && value <= maximum)) {
            throw new SkillParameterException(key, "シールドバッシュの params[" + key + "] が範囲外です");
        }
    }
}
