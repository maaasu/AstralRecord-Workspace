package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import java.util.List;

/** パラディンの範囲・入力検証・単発演出を共有します。 */
final class PaladinSkillSupport {
    private PaladinSkillSupport() { }

    /** 有限範囲の必須パラメータを検証します。 */
    static void bounded(SkillDefinition skill, String key, double minimum, double maximum) {
        double value = new SkillParamReader(skill.getId(), skill.getParams()).getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new SkillParameterException(key, "有限な許容範囲の値が必要です");
        }
    }

    /** ターゲット数と効果tickが整数であることを検証します。 */
    static void integer(SkillDefinition skill, String key, int maximum) {
        bounded(skill, key, 1.0D, maximum);
        double value = new SkillParamReader(skill.getId(), skill.getParams()).getDouble(key, Double.NaN);
        if (value != Math.rint(value)) throw new SkillParameterException(key, "整数が必要です");
    }

    /** 円柱支援または扇形攻撃の範囲を検証します。 */
    static void area(SkillDefinition skill, boolean attack) {
        bounded(skill, "radius", 0.1D, 16.0D);
        bounded(skill, "height", 0.1D, 8.0D);
        if (attack) integer(skill, "maxTargets", 24);
    }

    /** 視線に沿った120度の扇形から、近いMobを遮蔽判定付きで取得します。 */
    static List<AstEntity> targets(PlayerActiveSkillContext context) {
        return context.services().targeting().inCone(context.player(),
                context.params().getDouble("radius", 5.0D), 120.0D,
                context.params().getInt("maxTargets", 6), true,
                context.params().getDouble("height", 3.0D));
    }

    /** 発動時だけ24点の魔法陣を描き、対象探索を毎tick行いません。 */
    static void ring(PlayerActiveSkillContext context, SharedParticleDefinition particle) {
        context.services().effects().ring(context.player().getLocation().clone().add(0.0D, 0.15D, 0.0D),
                context.params().getDouble("radius", 5.0D), 24, particle);
    }
}
