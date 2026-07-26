package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

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

import java.util.List;

/** 進入した最初の敵を凍らせるハンターの絡め罠です。 */
public final class HunterSnareTrapExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_snare_trap";

    /** 共有発動スキルサービスで初期化します。 */
    public HunterSnareTrapExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        AstEntity attacker = context.attacker();
        Location trap = context.services().targeting().groundTarget(player, 8.0D);
        World castWorld = trap.getWorld();
        context.services().effects().ring(trap, 2.2D, 14, SharedParticleDefinitions.SKILL_HUNTER_TRAP_DUST);
        context.services().effects().sound(trap, Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.8F, 0.85F);
        context.services().tasks().repeat(player.getUniqueId(), ID, 10L, 1L, 240, ignored -> {
            if (!player.isOnline() || castWorld == null || player.getWorld() != castWorld) {
                context.services().tasks().cancel(player.getUniqueId(), ID);
                return;
            }
            List<AstEntity> targets = context.services().targeting().inRadius(
                    player, trap, 2.2D, 2.5D, 1, false);
            if (targets.isEmpty()) {
                return;
            }
            AstEntity target = targets.getFirst();
            context.services().combat().hit(
                    attacker,
                    target,
                    AttackType.RANGED,
                    DamageElement.NONE,
                    0.45D,
                    ActiveSkillCondition.certain(ConditionType.FROZEN, 10L),
                    ActiveSkillCondition.certain(ConditionType.CHILLED, 70L)
            );
            context.services().effects().ring(trap, 2.2D, 14, SharedParticleDefinitions.SKILL_HUNTER_IMPACT);
            context.services().effects().sound(trap, Sound.BLOCK_CHAIN_BREAK, 0.9F, 1.1F);
            context.services().tasks().cancel(player.getUniqueId(), ID);
        });
        return context.success();
    }
}
