package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 発動中、周囲の敵を継続的に自身へ挑発するソードマンの咆哮です。 */
public final class SwordsmanChallengingRoarExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_challenging_roar";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanChallengingRoarExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "radius");
        requirePositive(params, "height");
        requirePositiveInt(params, "maxTargets");
        requirePositiveInt(params, "durationTicks");
        requirePositiveInt(params, "visualIntervalTicks");
        requirePositiveInt(params, "tauntIntervalTicks");
        requirePositiveInt(params, "tauntHoldTicks");
        int visualInterval = params.getInt("visualIntervalTicks", 0);
        int tauntInterval = params.getInt("tauntIntervalTicks", 0);
        if (tauntInterval % visualInterval != 0) {
            throw new SkillParameterException("tauntIntervalTicks", "挑発間隔は演出間隔の倍数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double radius = params.getDouble("radius", 8.0D);
        double height = params.getDouble("height", 8.0D);
        int maxTargets = params.getInt("maxTargets", 24);
        int durationTicks = params.getInt("durationTicks", 80);
        int visualIntervalTicks = params.getInt("visualIntervalTicks", 5);
        int tauntIntervalTicks = params.getInt("tauntIntervalTicks", 20);
        int tauntHoldTicks = params.getInt("tauntHoldTicks", 21);
        int executions = Math.max(1, (durationTicks + visualIntervalTicks - 1) / visualIntervalTicks);
        int tauntEveryFrames = tauntIntervalTicks / visualIntervalTicks;

        Player player = context.player();
        World castWorld = player.getWorld();
        AstEntity attacker = context.attacker();
        context.services().effects().sound(
                player.getLocation(),
                Sound.ENTITY_RAVAGER_ROAR,
                1.35F,
                0.85F
        );
        context.services().tasks().repeat(
                player.getUniqueId(),
                ID,
                0L,
                visualIntervalTicks,
                executions,
                frame -> tickAura(
                        context,
                        player,
                        castWorld,
                        attacker,
                        frame,
                        tauntEveryFrames,
                        radius,
                        height,
                        maxTargets,
                        tauntHoldTicks
                )
        );
        return context.success();
    }

    private void tickAura(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull World castWorld,
            @NotNull AstEntity attacker,
            int frame,
            int tauntEveryFrames,
            double radius,
            double height,
            int maxTargets,
            long tauntHoldTicks
    ) {
        if (!player.isOnline() || player.isDead() || player.getWorld() != castWorld) {
            context.services().tasks().cancel(player.getUniqueId(), ID);
            return;
        }
        Location center = player.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        context.services().effects().point(center, SharedParticleDefinitions.CHALLENGING_ROAR_WARPED_SPORE);
        if (frame % tauntEveryFrames != 0) {
            return;
        }
        context.services().targeting()
                .inRadius(player, center, radius, height, maxTargets, true)
                .forEach(target -> context.services().combat().taunt(attacker, target, tauntHoldTicks));
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "チャレンジングロアの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) <= 0) {
            throw new SkillParameterException(key, "チャレンジングロアの params[" + key + "] は正の整数が必要です");
        }
    }
}
