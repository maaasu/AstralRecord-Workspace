package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 炎をまとった高速矢を放つシャープシューターの火属性スキルです。 */
public final class SharpshooterFireArrowExecutor extends SharpshooterElementalArrowExecutor {
    public static final String ID = "sharpshooter_fire_arrow";

    /** 共有発動スキルサービスで初期化します。 */
    public SharpshooterFireArrowExecutor(@NotNull ActiveSkillServices services) {
        super(
                ID,
                services,
                DamageElement.FIRE,
                ConditionType.BURNING,
                24.5D,
                25.0D,
                SharedParticleDefinitions.SHARPSHOOTER_FIRE_ARROW_TRAIL,
                SharedParticleDefinitions.SHARPSHOOTER_FIRE_ARROW_IMPACT,
                Sound.ENTITY_BLAZE_SHOOT,
                false
        );
    }
}
