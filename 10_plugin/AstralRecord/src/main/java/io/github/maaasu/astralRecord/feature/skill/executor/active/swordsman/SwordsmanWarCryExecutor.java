package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 周囲の敵の注意を一身に集めるソードマンの戦士の咆哮です。 */
public final class SwordsmanWarCryExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_war_cry";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanWarCryExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location center = context.player().getLocation().add(0.0D, 1.0D, 0.0D);
        context.services().targeting().inRadius(
                        context.player(), center, 7.0D, 4.0D, Integer.MAX_VALUE, true)
                .forEach(target -> context.services().combat().provoke(context.attacker(), target, 100.0D));
        context.services().effects().ring(center, 7.0D, 20, SharedParticleDefinitions.SKILL_SWORD_GUARD_DUST);
        context.services().effects().sound(center, Sound.ENTITY_RAVAGER_ROAR, 1.0F, 1.15F);
        return context.success();
    }
}
