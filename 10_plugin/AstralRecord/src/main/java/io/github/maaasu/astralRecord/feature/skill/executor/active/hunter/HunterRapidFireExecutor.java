package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
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

/** 四tick間隔で三本の矢を放つハンターの連矢です。 */
public final class HunterRapidFireExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_rapid_fire";

    /** 共有発動スキルサービスで初期化します。 */
    public HunterRapidFireExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        World castWorld = player.getWorld();
        AstEntity attacker = context.attacker();
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                15.0D, 1.6D, 0.45D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        context.services().tasks().repeat(player.getUniqueId(), ID, 0L, 4L, 3, ignored -> {
            if (!player.isOnline() || player.getWorld() != castWorld) {
                context.services().tasks().cancel(player.getUniqueId(), ID);
                return;
            }
            Location origin = player.getEyeLocation();
            context.services().projectiles().launch(
                    player, origin, origin.getDirection(), projectile,
                    (target, impact) -> context.services().combat().hit(
                            attacker, target, AttackType.RANGED, DamageElement.NONE, 0.55D),
                    end -> { }
            );
            context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.8F, 1.25F);
        });
        return context.success();
    }
}
