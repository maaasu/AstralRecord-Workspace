package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

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
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** 敵シールドを破壊し、実際に削った量の一部を自身へ移すシールドドレインです。 */
public final class SwordsmanShieldDrainExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_shield_drain";
    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanShieldDrainExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "targetAngle");
        requirePositive(params, "damageRatio");
        requirePositive(params, "shieldBreakMultiplier");
        double absorbRatio = params.getDouble("shieldAbsorbRatio", 0.0D);
        if (!(absorbRatio > 0.0D && absorbRatio <= 1.0D)) {
            throw new SkillParameterException("shieldAbsorbRatio", "シールド吸収率は0より大きく1以下が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 6.0D);
        double targetAngle = params.getDouble("targetAngle", 40.0D);
        double damageRatio = params.getDouble("damageRatio", 0.65D);
        double shieldBreakMultiplier = params.getDouble("shieldBreakMultiplier", 3.0D);
        double absorbRatio = params.getDouble("shieldAbsorbRatio", 0.50D);

        Player player = context.player();
        AstEntity target = context.services().targeting()
                .inCone(player, range, targetAngle, 1, true)
                .stream()
                .findFirst()
                .orElse(null);
        Location origin = slashOrigin(context);
        Location end = target == null
                ? origin.clone().add(context.direction().multiply(range))
                : target.location().clone().add(0.0D, 0.9D, 0.0D);
        renderSlash(context, origin, end);
        if (target == null) {
            return context.success();
        }

        AstEntity attacker = context.attacker();
        DamageResult result = context.services().combat().hit(
                attacker,
                target,
                AttackType.MELEE,
                DamageElement.NONE,
                damageRatio,
                shieldBreakMultiplier
        );
        if (result.shieldDamage() <= 0.0D) {
            return context.success();
        }
        context.services().effects().point(end, SharedParticleDefinitions.SHIELD_BREAK_DUST);
        double recovered = context.services().combat().recoverShield(attacker, result.shieldDamage() * absorbRatio);
        if (recovered > 0.0D) {
            renderAbsorption(context, player, end);
        }
        return context.success();
    }

    private @NotNull Location slashOrigin(@NotNull PlayerActiveSkillContext context) {
        return context.eyeLocation().add(context.direction().multiply(0.35D)).subtract(0.0D, 0.25D, 0.0D);
    }

    /**
     * 発動者から攻撃終点へシールドドレインの斬撃演出を表示します。
     *
     * @param context 発動者と演出サービスを保持するコンテキスト
     * @param origin 斬撃の開始位置
     * @param end 斬撃の終了位置
     */
    private void renderSlash(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location origin,
            @NotNull Location end
    ) {
        context.services().effects().line(
                origin,
                end,
                0.24D,
                SharedParticleDefinitions.SHIELD_DRAIN_SLASH_DUST
        );
        context.services().effects().viewArcSegment(
                origin,
                context.direction(),
                1.0D,
                35.0D,
                -35.0D,
                9,
                SharedParticleDefinitions.SHIELD_DRAIN_SLASH_DUST
        );
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.1F);
    }

    private void renderAbsorption(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull Location targetCenter
    ) {
        Location start = targetCenter.clone();
        context.services().tasks().repeat(player.getUniqueId(), ID + ":absorb", 1L, 1L, 4, frame -> {
            if (!player.isOnline() || player.getWorld() != start.getWorld()) {
                return;
            }
            Location arrival = player.getLocation().clone().add(0.0D, 1.0D, 0.0D);
            Vector travel = arrival.toVector().subtract(start.toVector());
            Location particle = start.clone().add(travel.multiply((frame + 1.0D) / 4.0D));
            context.services().effects().point(
                    particle,
                    SharedParticleDefinitions.SHIELD_DRAIN_ABSORB_END_ROD
            );
            if (frame < 3) {
                return;
            }
            context.services().effects().ring(
                    player.getLocation().clone().add(0.0D, 0.95D, 0.0D),
                    0.85D,
                    18,
                    SharedParticleDefinitions.SHIELD_DRAIN_RING_DUST
            );
            context.services().effects().sound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 0.8F, 1.45F);
        });
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "シールドドレインの params[" + key + "] は正数が必要です");
        }
    }
}
