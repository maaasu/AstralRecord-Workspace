package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 一定時間、周囲の敵を継続的に自身へ挑発するパラディンの防衛スキルです。 */
public final class PaladinFortressExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_fortress";

    /** 共有発動スキルサービスで初期化します。 */
    public PaladinFortressExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "radius");
        requirePositive(params, "height");
        requirePositiveInt(params, "maxTargets");
        requirePositiveInt(params, "durationTicks");
        requirePositiveInt(params, "pulseIntervalTicks");
        requirePositiveInt(params, "tauntHoldTicks");
        requirePositiveInt(params, "visualIntervalTicks");
        int pulseIntervalTicks = params.getInt("pulseIntervalTicks", 0);
        int visualIntervalTicks = params.getInt("visualIntervalTicks", 0);
        if (pulseIntervalTicks % visualIntervalTicks != 0) {
            throw new SkillParameterException("pulseIntervalTicks", "挑発間隔は演出間隔の倍数が必要です");
        }
    }

    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double radius = params.getDouble("radius", 8.0D);
        double height = params.getDouble("height", 8.0D);
        int maxTargets = params.getInt("maxTargets", 24);
        int durationTicks = params.getInt("durationTicks", 220);
        int intervalTicks = params.getInt("pulseIntervalTicks", 20);
        int tauntHoldTicks = params.getInt("tauntHoldTicks", 21);
        int visualIntervalTicks = params.getInt("visualIntervalTicks", 5);
        int executions = Math.max(1, (durationTicks + visualIntervalTicks - 1) / visualIntervalTicks);
        int tauntEveryFrames = intervalTicks / visualIntervalTicks;
        Player player = context.player();
        World world = player.getWorld();
        AstEntity attacker = context.attacker();
        context.services().tasks().repeat(
                player.getUniqueId(), ID, 0L, visualIntervalTicks, executions,
                frame -> pulse(
                        context, player, world, attacker, frame, visualIntervalTicks,
                        tauntEveryFrames, durationTicks, radius, height, maxTargets, tauntHoldTicks
                )
        );
        return context.success();
    }

    private void pulse(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull World world,
            @NotNull AstEntity attacker,
            int frame,
            int visualIntervalTicks,
            int tauntEveryFrames,
            int durationTicks,
            double radius,
            double height,
            int maxTargets,
            int tauntHoldTicks
    ) {
        if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
            context.services().tasks().cancel(player.getUniqueId(), ID);
            return;
        }
        Location center = player.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        context.services().effects().point(center, SharedParticleDefinitions.CHALLENGING_ROAR_WARPED_SPORE);
        if (frame % tauntEveryFrames != 0) {
            return;
        }
        long remainingTicks = durationTicks - (long) frame * visualIntervalTicks;
        if (remainingTicks <= 0L) {
            return;
        }
        int effectiveTauntHoldTicks = (int) Math.min((long) tauntHoldTicks, remainingTicks);
        context.services().effects().ring(
                player.getLocation().clone().add(0.0D, 0.10D, 0.0D),
                radius, 48, SharedParticleDefinitions.SKILL_PALADIN_FORTRESS_DUST
        );
        context.services().targeting().inRadius(
                player, center, radius, height, maxTargets, false
        ).forEach(target -> context.services().combat().taunt(attacker, target, effectiveTauntHoldTicks));
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "フォートレスの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "フォートレスの params[" + key + "] は1以上が必要です");
        }
    }
}
