package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 前方の敵を上空から地面へ叩き落とし、着地点の敵を押し出す冒険者のスマッシュです。 */
public final class AdventurerSmashExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "adventurer_smash";
    /** 共有発動スキルサービスで初期化します。 */
    public AdventurerSmashExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "reach");
        requirePositive(params, "targetAngle");
        requirePositive(params, "impactRadius");
        requirePositive(params, "damageRatio");
        requirePositive(params, "secondaryRatio");
        requirePositive(params, "secondaryKnockback");
        if (params.getInt("impactDelayTicks", 0) < 0 || params.getInt("maxSecondaryTargets", 0) < 1) {
            throw new SkillParameterException("impactDelayTicks", "スマッシュの遅延tickと副対象数は正数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        AstEntity attacker = context.attacker();
        var params = context.params();
        double reach = params.getDouble("reach", 6.0D);
        double targetAngle = params.getDouble("targetAngle", 35.0D);
        double impactRadius = params.getDouble("impactRadius", 2.0D);
        double damageRatio = params.getDouble("damageRatio", 2.40D);
        double secondaryRatio = params.getDouble("secondaryRatio", 0.48D);
        double secondaryKnockback = params.getDouble("secondaryKnockback", 1.0D);
        int impactDelayTicks = params.getInt("impactDelayTicks", 8);
        int maxSecondaryTargets = params.getInt("maxSecondaryTargets", 8);
        AstEntity primary = context.services().targeting()
                .inCone(player, reach, targetAngle, 1, true)
                .stream()
                .findFirst()
                .orElse(null);
        if (primary == null) {
            return context.success();
        }

        Location impact = primary.location().clone().add(0.0D, 0.05D, 0.0D);
        World castWorld = impact.getWorld();
        context.services().effects().line(
                impact.clone().add(0.0D, 3.0D, 0.0D),
                impact,
                0.3D,
                SharedParticleDefinitions.SKILL_SWORD_EDGE
        );
        context.services().tasks().later(player.getUniqueId(), ID, impactDelayTicks, () -> {
            if (!player.isOnline() || castWorld == null || player.getWorld() != castWorld) {
                return;
            }
            DamageResult primaryResult = context.services().combat().hit(
                    attacker,
                    primary,
                    AttackType.MELEE,
                    DamageElement.NONE,
                    damageRatio
            );
            context.services().targeting()
                    .inRadius(player, impact, impactRadius, impactRadius, maxSecondaryTargets, true)
                    .stream()
                    .filter(target -> !target.id().equals(primary.id()))
                    .forEach(target -> hitSecondary(context, attacker, target, impact, secondaryRatio, secondaryKnockback));
            context.services().effects().ring(
                    impact,
                    impactRadius,
                    16,
                    SharedParticleDefinitions.SKILL_SWORD_EDGE
            );
            context.services().effects().sound(
                    impact,
                    Sound.ENTITY_PLAYER_ATTACK_STRONG,
                    primaryResult.evaded() ? 0.7F : 1.1F,
                    0.7F
            );
        });
        context.services().effects().sound(
                player.getLocation(),
                Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                1.0F,
                0.65F
        );
        return context.success();
    }

    private void hitSecondary(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull Location impact,
            double secondaryRatio,
            double secondaryKnockback
    ) {
        DamageResult result = context.services().combat().hit(
                attacker,
                target,
                AttackType.MELEE,
                DamageElement.NONE,
                secondaryRatio
        );
        if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
            context.services().combat().knockback(target, impact, secondaryKnockback, 0.25D);
        }
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "スマッシュの params[" + key + "] は正数が必要です");
        }
    }
}
