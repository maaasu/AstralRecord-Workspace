package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
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

/** 指定地点へ四波の矢を降らせるハンターの矢雨です。 */
public final class HunterArrowRainExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_arrow_rain";

    /** 共有発動スキルサービスで初期化します。 */
    public HunterArrowRainExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        Location center = context.services().targeting().groundTarget(player, 14.0D);
        World castWorld = center.getWorld();
        context.services().effects().ring(center, 3.2D, 18, SharedParticleDefinitions.SKILL_HUNTER_TRAP_DUST);
        context.services().tasks().repeat(player.getUniqueId(), ID, 20L, 6L, 4, wave -> {
            if (!player.isOnline() || castWorld == null || player.getWorld() != castWorld) {
                context.services().tasks().cancel(player.getUniqueId(), ID);
                return;
            }
            context.services().effects().ring(center, 3.2D, 14, SharedParticleDefinitions.SKILL_HUNTER_ARROW);
            context.services().targeting().inRadius(player, center, 3.2D, 3.0D, 12, false)
                    .forEach(target -> {
                        context.services().combat().hit(
                                context.attacker(), target, AttackType.RANGED, DamageElement.NONE, 0.35D);
                        context.services().effects().point(
                                target.location().clone().add(0.0D, 1.0D, 0.0D),
                                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
                        );
                    });
            context.services().effects().sound(center, Sound.ENTITY_ARROW_HIT, 0.7F, 1.15F + wave * 0.05F);
        });
        return context.success();
    }
}
