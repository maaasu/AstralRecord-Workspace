package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 小型の氷BlockDisplayと水色粒子をまとった高速矢を放つシャープシューターの氷属性スキルです。 */
public final class SharpshooterIceArrowExecutor extends SharpshooterElementalArrowExecutor {
    public static final String ID = "sharpshooter_ice_arrow";

    /** 共有発動スキルサービスで初期化します。 */
    public SharpshooterIceArrowExecutor(@NotNull ActiveSkillServices services) {
        super(
                ID,
                services,
                DamageElement.ICE,
                ConditionType.FROZEN,
                50.0D,
                50.0D,
                SharedParticleDefinitions.SHARPSHOOTER_ICE_ARROW_TRAIL,
                SharedParticleDefinitions.SHARPSHOOTER_ICE_ARROW_IMPACT,
                Sound.ENTITY_ARROW_SHOOT,
                true
        );
    }
}
