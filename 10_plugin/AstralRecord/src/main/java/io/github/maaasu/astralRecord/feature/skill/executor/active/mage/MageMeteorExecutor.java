package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 予兆の後に火属性の隕石を落とすメイジの落星です。 */
public final class MageMeteorExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_meteor";

    /** 共有発動スキルサービスで初期化します。 */
    public MageMeteorExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        AstEntity attacker = context.attacker();
        Location center = context.services().targeting().groundTarget(player, 14.0D);
        World castWorld = center.getWorld();
        context.services().effects().ring(center, 3.5D, 20, SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST);
        context.services().tasks().later(player.getUniqueId(), ID, 24L, () -> {
            if (!player.isOnline() || castWorld == null || player.getWorld() != castWorld) {
                return;
            }
            Location sky = center.clone().add(0.0D, 8.0D, 0.0D);
            context.services().effects().line(sky, center, 0.5D, SharedParticleDefinitions.SKILL_MAGE_FIRE);
            context.services().effects().ring(center, 3.5D, 22, SharedParticleDefinitions.SKILL_MAGE_FIRE);
            context.services().targeting().inRadius(player, center, 3.5D, 3.5D, 12, true)
                    .forEach(target -> context.services().combat().hit(
                            attacker,
                            target,
                            AttackType.MAGIC,
                            DamageElement.FIRE,
                            2.20D,
                            ActiveSkillCondition.certain(ConditionType.BURNING, 80L)
                    ));
            context.services().effects().sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.1F, 0.65F);
        });
        return context.success();
    }
}
