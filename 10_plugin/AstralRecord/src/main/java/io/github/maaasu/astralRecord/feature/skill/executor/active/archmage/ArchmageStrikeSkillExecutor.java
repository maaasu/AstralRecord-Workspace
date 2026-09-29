package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 発動時に魔法陣数を固定する星術の共通部分です。 */
abstract class ArchmageStrikeSkillExecutor extends PlayerActiveSkillExecutor {
    /**
     * 星術スキルを初期化します。
     * @param id スキルID
     * @param services 共通発動サービス
     */
    protected ArchmageStrikeSkillExecutor(@NotNull String id, @NotNull ActiveSkillServices services) {
        super(id, services);
    }

    /** {@inheritDoc} */
    @Override
    public final void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : new String[]{"range", "radius", "power"}) {
            double value = p.getDouble(key, Double.NaN);
            if (!Double.isFinite(value) || value <= 0.0D) {
                throw new SkillParameterException(key, "星術の値は正数が必要です");
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected final @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader p = context.params();
        Location center = ArchmageBuildSupport.groundAtSight(context, p.getDouble("range", 16.0D));
        if (center == null) {
            return SkillCastResult.failure(null);
        }
        int circles = ArchmageBuildSupport.circleBonus(context);
        strike(context, center, p.getDouble("radius", 3.0D), p.getDouble("power", 1.0D), circles);
        return context.success();
    }

    /**
     * 陣数に応じた個別攻撃を実行します。
     * @param context 発動者と共有戦闘サービス
     * @param center 照準地点
     * @param radius 基本半径
     * @param power 基本魔法倍率
     * @param circles 発動時点の現存陣数（最大3）
     */
    protected abstract void strike(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                                   double radius, double power, int circles);
}
