package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 地表へプリズムを設置し、タグ付き魔法の吸収と追撃を有効にするウィザードスキルです。 */
public final class WizardElementalPrismExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "wizard_elemental_prism";
    private static final double DISPLAY_CLEARANCE = 1.35D;

    /** 共有発動スキルサービスで初期化します。 */
    public WizardElementalPrismExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "placementRange");
        requirePositive(params, "radius");
        requirePositive(params, "damageRatio");
        requirePositive(params, "projectileSpeed");
        requirePositiveInt(params, "maxTargets");
        requirePositiveInt(params, "projectileCount");
        requirePositiveInt(params, "durationTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        Location base = placement(context, params.getDouble("placementRange", 3.0D));
        if (base == null) {
            return SkillCastResult.failure(null);
        }
        context.services().prisms().place(
                context,
                base,
                params.getDouble("radius", 6.0D),
                params.getDouble("damageRatio", 0.60D),
                params.getInt("maxTargets", 5),
                params.getInt("projectileCount", 5),
                params.getInt("durationTicks", 7200),
                params.getDouble("projectileSpeed", 1.5D)
        );
        return context.success();
    }

    /**
     * 目線の水平3m先から壁の手前へ補正し、表示物を置ける地表を探します。
     *
     * @param context 発動者と地形判定サービス
     * @param range 水平方向の最大設置距離
     * @return 表示物を置ける地表。見つからなければnull
     */
    private static @Nullable Location placement(
            @NotNull PlayerActiveSkillContext context,
            double range
    ) {
        Location eye = context.eyeLocation();
        Vector horizontal = context.direction().setY(0.0D);
        if (horizontal.lengthSquared() <= 1.0E-8D) {
            horizontal = eye.getDirection().setY(0.0D);
        }
        if (horizontal.lengthSquared() <= 1.0E-8D) {
            horizontal = new Vector(0.0D, 0.0D, 1.0D);
        }
        horizontal.normalize();
        Location wall = context.services().targeting().blockImpact(eye, horizontal, range);
        double farthest = wall == null ? range : Math.max(0.0D, eye.distance(wall) - DISPLAY_CLEARANCE);
        for (double distance = farthest; distance >= 0.0D; distance -= 0.25D) {
            Location probe = eye.clone().add(horizontal.clone().multiply(distance));
            Location ground = context.services().targeting().groundAt(probe, 3, 32);
            Block floor = ground.clone().subtract(0.0D, 0.20D, 0.0D).getBlock();
            if (floor.isPassable() || !ground.getBlock().isPassable()
                    || !ground.clone().add(0.0D, 1.0D, 0.0D).getBlock().isPassable()
                    || !ground.clone().add(0.0D, 2.0D, 0.0D).getBlock().isPassable()
                    || !ground.clone().add(0.0D, 3.0D, 0.0D).getBlock().isPassable()) {
                continue;
            }
            if (context.services().targeting().hasLineOfSight(
                    eye, ground.clone().add(0.0D, 1.45D, 0.0D))) {
                return ground;
            }
        }
        return null;
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "エレメンタルプリズムの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "エレメンタルプリズムの params[" + key + "] は1以上の整数が必要です");
        }
    }
}
