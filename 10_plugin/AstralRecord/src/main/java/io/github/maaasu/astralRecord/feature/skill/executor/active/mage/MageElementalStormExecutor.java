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
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 火・氷・雷の六波を交互に放つメイジの三相嵐です。 */
public final class MageElementalStormExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_elemental_storm";
    private static final DamageElement[] ELEMENTS = {
            DamageElement.FIRE,
            DamageElement.ICE,
            DamageElement.LIGHTNING
    };

    /** 共有発動スキルサービスで初期化します。 */
    public MageElementalStormExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        AstEntity attacker = context.attacker();
        Location center = context.services().targeting().groundTarget(player, 12.0D);
        World castWorld = center.getWorld();
        context.services().effects().ring(center, 4.0D, 22, SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST);
        context.services().tasks().repeat(player.getUniqueId(), ID, 0L, 10L, 6, pulse -> {
            if (!player.isOnline() || castWorld == null || player.getWorld() != castWorld) {
                context.services().tasks().cancel(player.getUniqueId(), ID);
                return;
            }
            DamageElement element = ELEMENTS[pulse % ELEMENTS.length];
            SharedParticleDefinition particle = particle(element);
            ActiveSkillCondition condition = condition(element);
            context.services().effects().ring(center, 4.0D, 18, particle);
            context.services().targeting().inRadius(player, center, 4.0D, 3.5D, 12, true)
                    .forEach(target -> context.services().combat().hit(
                            attacker,
                            target,
                            AttackType.MAGIC,
                            element,
                            0.32D,
                            condition
                    ));
            context.services().effects().sound(center, sound(element), 0.75F, 1.1F + pulse * 0.03F);
        });
        return context.success();
    }

    private static @NotNull SharedParticleDefinition particle(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> SharedParticleDefinitions.SKILL_MAGE_FIRE;
            case ICE -> SharedParticleDefinitions.SKILL_MAGE_ICE;
            case LIGHTNING -> SharedParticleDefinitions.SKILL_MAGE_LIGHTNING;
            default -> SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST;
        };
    }

    private static @NotNull ActiveSkillCondition condition(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> new ActiveSkillCondition(ConditionType.BURNING, 15.0D, 60L, 1.0D);
            case ICE -> new ActiveSkillCondition(ConditionType.CHILLED, 15.0D, 80L, 1.0D);
            case LIGHTNING -> new ActiveSkillCondition(ConditionType.SHOCKED, 15.0D, 40L, 1.0D);
            default -> throw new IllegalArgumentException("unsupported storm element: " + element);
        };
    }

    private static @NotNull Sound sound(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> Sound.ITEM_FIRECHARGE_USE;
            case ICE -> Sound.BLOCK_GLASS_BREAK;
            case LIGHTNING -> Sound.ENTITY_LIGHTNING_BOLT_IMPACT;
            default -> Sound.ENTITY_EVOKER_CAST_SPELL;
        };
    }
}
