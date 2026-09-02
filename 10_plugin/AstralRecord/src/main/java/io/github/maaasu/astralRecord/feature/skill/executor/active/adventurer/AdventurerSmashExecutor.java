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
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 発動者の周囲の敵を上空から地面へ叩き落とし、着地点の敵を押し出す冒険者のスマッシュです。 */
public final class AdventurerSmashExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "adventurer_smash";
    private static final int SHOCKWAVE_FRAMES = 6;
    /** 共有発動スキルサービスで初期化します。 */
    public AdventurerSmashExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "primaryRadius");
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
        double primaryRadius = params.getDouble("primaryRadius", 4.0D);
        double impactRadius = params.getDouble("impactRadius", 2.0D);
        double damageRatio = params.getDouble("damageRatio", 3.60D);
        double secondaryRatio = params.getDouble("secondaryRatio", 0.576D);
        double secondaryKnockback = params.getDouble("secondaryKnockback", 1.0D);
        int impactDelayTicks = params.getInt("impactDelayTicks", 8);
        int maxSecondaryTargets = params.getInt("maxSecondaryTargets", 8);
        Location eye = context.eyeLocation();
        int radiusCandidateLimit = maxSecondaryTargets == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : maxSecondaryTargets + 1;
        List<AstEntity> primaryCandidates = context.services().targeting().inRadius(
                player,
                eye,
                primaryRadius,
                primaryRadius,
                Integer.MAX_VALUE,
                true
        );
        AstEntity selectedPrimary = primaryCandidates.isEmpty() ? null : primaryCandidates.get(0);
        if (primaryCandidates.size() > 1) {
            Set<UUID> viewLineTargetIds = new HashSet<>();
            for (AstEntity target : context.services().targeting().inLine(
                    player,
                    eye,
                    context.direction(),
                    primaryRadius,
                    0.0D,
                    Integer.MAX_VALUE
            )) {
                viewLineTargetIds.add(target.id());
            }
            selectedPrimary = primaryCandidates.stream()
                    .filter(target -> viewLineTargetIds.contains(target.id()))
                    .findFirst()
                    .orElse(selectedPrimary);
        }
        final AstEntity primary = selectedPrimary;
        if (primary == null) {
            return context.success();
        }

        Location impact = primary.location().clone().add(0.0D, 0.05D, 0.0D);
        World castWorld = impact.getWorld();
        Vector right = context.direction().clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        if (right.lengthSquared() <= 1.0E-8D) {
            right.setX(1.0D);
        } else {
            right.normalize();
        }
        Location strikeStart = impact.clone().add(right.clone().multiply(1.35D)).add(0.0D, 3.2D, 0.0D);
        context.services().effects().line(
                strikeStart,
                impact,
                0.18D,
                SharedParticleDefinitions.ADVENTURER_SMASH_CRIT
        );
        context.services().effects().line(
                strikeStart.clone().add(right.clone().multiply(0.22D)).add(0.0D, 0.28D, 0.0D),
                impact.clone().add(right.clone().multiply(0.12D)).add(0.0D, 0.12D, 0.0D),
                0.22D,
                SharedParticleDefinitions.ADVENTURER_SMASH_SPARK
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
                    .inRadius(player, impact, impactRadius, impactRadius, radiusCandidateLimit, true)
                    .stream()
                    .filter(target -> !target.id().equals(primary.id()))
                    .limit(maxSecondaryTargets)
                    .forEach(target -> hitSecondary(context, attacker, target, impact, secondaryRatio, secondaryKnockback));
            Location floor = impact.clone().subtract(0.0D, 0.15D, 0.0D);
            context.services().effects().blockDust(floor, floor.getBlock().getBlockData());
            context.services().effects().point(
                    impact.clone().add(0.0D, 0.18D, 0.0D),
                    SharedParticleDefinitions.ADVENTURER_SMASH_SWEEP
            );
            context.services().tasks().repeat(
                    player.getUniqueId(),
                    ID + ":shockwave",
                    0L,
                    1L,
                    SHOCKWAVE_FRAMES,
                    frame -> {
                        double radius = impactRadius * (frame + 1.0D) / SHOCKWAVE_FRAMES;
                        context.services().effects().ring(
                                impact,
                                radius,
                                18 + frame * 3,
                                frame % 2 == 0
                                        ? SharedParticleDefinitions.ADVENTURER_SMASH_SWEEP
                                        : SharedParticleDefinitions.ADVENTURER_SMASH_SPARK
                        );
                    }
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
