package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 八秒間被ダメージを35%軽減するソードマンの堅城の構えです。 */
public final class SwordsmanFortressGuardExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_fortress_guard";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanFortressGuardExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        context.services().temporaryEffects().apply(
                context.player().getUniqueId(), ID, 160L, 0.65D, 1.0D, 0.50D);
        Location center = context.player().getLocation().add(0.0D, 0.15D, 0.0D);
        context.services().effects().ring(center, 1.25D, 14, SharedParticleDefinitions.SKILL_SWORD_GUARD_DUST);
        context.services().effects().sound(center, Sound.ITEM_SHIELD_BLOCK, 1.0F, 0.75F);
        return context.success();
    }
}
