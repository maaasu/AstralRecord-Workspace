package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 右から左へ薙ぎ払い、命中対象へ追撃の突きを加える冒険者のアストラルエッジです。 */
public final class AdventurerAstralEdgeExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "adventurer_astral_edge";
    private static final String SWEEP_SCOPE = ID + ":sweep";
    private static final String THRUST_SCOPE = ID + ":thrust";
    private static final double REACH = 5.5D;
    private static final double[] SWEEP_RADII = {2.4D, 3.9D, 5.4D};
    private static final double SWEEP_START_ANGLE = 55.0D;
    private static final double SWEEP_END_ANGLE = -55.0D;
    private static final int SWEEP_FRAMES = 6;
    private static final int MAX_TARGETS = 5;

    /** 共有発動スキルサービスで初期化します。 */
    public AdventurerAstralEdgeExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        AstEntity attacker = context.attacker();
        Location origin = context.eyeLocation();
        var direction = context.direction();
        World castWorld = player.getWorld();
        Set<UUID> hitTargetIds = new HashSet<>();

        context.services().tasks().repeat(
                player.getUniqueId(),
                SWEEP_SCOPE,
                0L,
                1L,
                SWEEP_FRAMES,
                frame -> {
                    if (!player.isOnline() || player.getWorld() != castWorld) {
                        context.services().tasks().cancel(player.getUniqueId(), SWEEP_SCOPE);
                        return;
                    }
                    double headStart = SWEEP_START_ANGLE
                            + (SWEEP_END_ANGLE - SWEEP_START_ANGLE) * frame / SWEEP_FRAMES;
                    double headEnd = SWEEP_START_ANGLE
                            + (SWEEP_END_ANGLE - SWEEP_START_ANGLE) * (frame + 1) / SWEEP_FRAMES;
                    for (double radius : SWEEP_RADII) {
                        context.services().effects().viewArcSegment(
                                origin,
                                direction,
                                radius,
                                headStart,
                                headEnd,
                                5,
                                SharedParticleDefinitions.ADVENTURER_ASTRAL_EDGE_CRIT
                        );
                        context.services().effects().viewArcSegment(
                                origin,
                                direction,
                                radius,
                                headStart,
                                Math.min(SWEEP_START_ANGLE, headStart + 18.0D),
                                4,
                                SharedParticleDefinitions.ADVENTURER_ASTRAL_EDGE_SPARK
                        );
                    }
                    List<AstEntity> frameTargets = context.services().targeting().inViewArcSegment(
                            player,
                            origin,
                            direction,
                            REACH,
                            headStart,
                            headEnd,
                            MAX_TARGETS,
                            true
                    );
                    for (AstEntity target : frameTargets) {
                        if (hitTargetIds.size() >= MAX_TARGETS || !hitTargetIds.add(target.id())) {
                            continue;
                        }
                        hitSweepTarget(context, attacker, player, castWorld, target);
                    }
                }
        );
        context.services().effects().sound(
                origin,
                Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                1.0F,
                1.05F
        );
        return context.success();
    }

    private void hitSweepTarget(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull Player player,
            @NotNull World castWorld,
            @NotNull AstEntity target
    ) {
        context.services().combat().hit(
                attacker,
                target,
                AttackType.MELEE,
                DamageElement.NONE,
                1.0D
        );
        Location targetLocation = target.location().add(0.0D, 0.9D, 0.0D);
        context.services().effects().sound(
                targetLocation,
                Sound.ENTITY_PLAYER_ATTACK_CRIT,
                0.85F,
                1.2F
        );
        String targetScope = THRUST_SCOPE + ":" + target.id();
        context.services().tasks().later(player.getUniqueId(), targetScope, 4L, () -> {
            if (!player.isOnline() || player.getWorld() != castWorld) {
                return;
            }
            Location currentTargetLocation = target.location().add(0.0D, 0.9D, 0.0D);
            context.services().effects().line(
                    player.getEyeLocation(),
                    currentTargetLocation,
                    0.38D,
                    SharedParticleDefinitions.SKILL_SWORD_EDGE
            );
            context.services().combat().hit(
                    attacker,
                    target,
                    AttackType.MELEE,
                    DamageElement.NONE,
                    0.5D
            );
            context.services().effects().point(
                    currentTargetLocation,
                    SharedParticleDefinitions.SKILL_SWORD_EDGE
            );
            context.services().effects().sound(
                    currentTargetLocation,
                    Sound.ENTITY_PLAYER_ATTACK_CRIT,
                    0.85F,
                    1.2F
            );
        });
    }
}
