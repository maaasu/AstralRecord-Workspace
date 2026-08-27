package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 現在MPを使い切り、命中時に自身のシールドを立て直すソードマン用近接スキルです。 */
public final class SwordsmanBastionStrikeExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_bastion_strike";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanBastionStrikeExecutor(@NotNull ActiveSkillServices services) {
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
        if (!Boolean.TRUE.equals(skill.getParams().get("consumeAllCurrentMana"))) {
            throw new SkillParameterException("consumeAllCurrentMana", "バスティオンストライクは現在MP全消費を指定してください");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 6.0D);
        double targetAngle = params.getDouble("targetAngle", 40.0D);
        double damageRatio = params.getDouble("damageRatio", 1.25D);
        Player player = context.player();
        Location origin = slashOrigin(context);
        AstEntity target = context.services().targeting()
                .inCone(player, range, targetAngle, 1, true)
                .stream()
                .findFirst()
                .orElse(null);
        Location end = target == null
                ? origin.clone().add(context.direction().multiply(range))
                : target.location().clone().add(0.0D, 0.9D, 0.0D);
        renderStrike(context, origin, end);
        consumeAllCurrentMana(context);
        double recovered = context.services().combat().recoverShield(
                context.attacker(),
                missingShield(context.source().statusSnapshot())
        );
        if (recovered > 0.0D) {
            renderShieldRecovery(context, player);
        }
        if (target == null) {
            return context.success();
        }

        context.services().combat().hit(
                context.attacker(), target, AttackType.MELEE, DamageElement.NONE, damageRatio
        );
        return context.success();
    }

    /** 現在のステータスから最大シールドまでに不足している量を求めます。 */
    private static double missingShield(@NotNull StatusSnapshot snapshot) {
        return Math.max(0.0D, snapshot.getMaxValue(StatusType.MAX_SHIELD) - snapshot.getCurrentShield());
    }

    /** 発動時点のMPをすべて消費します。 */
    private static void consumeAllCurrentMana(@NotNull PlayerActiveSkillContext context) {
        double currentMana = context.caster().currentMana();
        if (Double.isFinite(currentMana) && currentMana > 0.0D) {
            context.caster().consumeMana(currentMana);
        }
    }

    /** 斬撃エフェクトの始点をプレイヤーの視線方向から算出します。 */
    private @NotNull Location slashOrigin(@NotNull PlayerActiveSkillContext context) {
        return context.eyeLocation().add(context.direction().multiply(0.35D)).subtract(0.0D, 0.25D, 0.0D);
    }

    /** 前方単体攻撃の斬撃エフェクトと効果音を再生します。 */
    private void renderStrike(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location origin,
            @NotNull Location end
    ) {
        context.services().effects().line(origin, end, 0.20D, SharedParticleDefinitions.BASTION_STRIKE_SLASH_DUST);
        context.services().effects().viewArcSegment(
                origin,
                context.direction(),
                1.15D,
                -35.0D,
                35.0D,
                11,
                SharedParticleDefinitions.BASTION_STRIKE_SLASH_DUST
        );
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0F, 0.9F);
    }

    /** シールド回復が成立したときの防壁エフェクトと効果音を再生します。 */
    private void renderShieldRecovery(@NotNull PlayerActiveSkillContext context, @NotNull Player player) {
        Location center = player.getLocation().clone().add(0.0D, 0.95D, 0.0D);
        context.services().effects().ring(center, 0.90D, 20, SharedParticleDefinitions.BASTION_STRIKE_SHIELD_RING_DUST);
        context.services().effects().sound(center, Sound.ITEM_ARMOR_EQUIP_DIAMOND, 0.9F, 1.15F);
    }

    /** 指定パラメータが正数であることを検証します。 */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "バスティオンストライクの params[" + key + "] は正数が必要です");
        }
    }
}
