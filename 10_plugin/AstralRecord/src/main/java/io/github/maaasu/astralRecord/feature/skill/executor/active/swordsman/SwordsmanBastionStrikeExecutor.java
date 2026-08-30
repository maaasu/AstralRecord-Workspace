package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 現在MPを使い切り、自己シールドを立て直して放つソードマン用近接スキルです。 */
public final class SwordsmanBastionStrikeExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_bastion_strike";
    private static final int LEVEL_FIVE = 5;
    private static final int BASTION_PULSE_COUNT = 4;
    private static final long BASTION_PULSE_PERIOD_TICKS = 2L;

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
        double levelFiveRequiredManaRatio = params.getDouble("levelFiveRequiredManaRatio", 0.0D);
        if (!(levelFiveRequiredManaRatio > 0.0D && levelFiveRequiredManaRatio <= 1.0D)) {
            throw new SkillParameterException(
                    "levelFiveRequiredManaRatio",
                    "バスティオンストライクのLv.5必要MP比率は0より大きく1以下で指定してください"
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 6.0D);
        double targetAngle = params.getDouble("targetAngle", 40.0D);
        double damageRatio = params.getDouble("damageRatio", 2.50D);
        if (!hasRequiredMana(context, params)) {
            return SkillCastResult.failure(PlayerMsgId.P_5801);
        }
        Player player = context.player();
        AstEntity target = context.services().targeting()
                .inCone(player, range, targetAngle, 1, true)
                .stream()
                .findFirst()
                .orElse(null);
        Location end = target == null
                ? context.eyeLocation().add(context.direction().multiply(range))
                : target.location().clone().add(0.0D, 0.9D, 0.0D);
        renderBastionAwakening(context, player, end);
        consumeAllCurrentMana(context);
        context.services().combat().recoverShield(
                context.attacker(),
                missingShield(context.source().statusSnapshot())
        );
        if (target == null) {
            return context.success();
        }

        context.services().combat().hit(
                context.attacker(), target, AttackType.MELEE, DamageElement.NONE, damageRatio
        );
        return context.success();
    }

    /** 習得レベルごとの必要MP条件を満たすか判定します。 */
    private static boolean hasRequiredMana(@NotNull PlayerActiveSkillContext context, @NotNull SkillParamReader params) {
        StatusSnapshot snapshot = context.source().statusSnapshot();
        double maxMana = snapshot.getMaxValue(StatusType.MAX_MANA);
        double currentMana = context.caster().currentMana();
        if (!(Double.isFinite(maxMana) && maxMana > 0.0D && Double.isFinite(currentMana))) {
            return false;
        }
        LearnedSkillInstance learnedSkill = context.source().learnedSkill();
        int learnedLevel = learnedSkill == null ? 1 : learnedSkill.getLevel();
        if (learnedLevel < LEVEL_FIVE) {
            return Math.abs(currentMana - maxMana) <= 1.0E-6D;
        }
        return currentMana + 1.0E-6D >= maxMana * params.getDouble("levelFiveRequiredManaRatio", 0.80D);
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

    /** ソウルファイアの防壁を四段展開し、着弾地点へ閃光を重ねます。 */
    private void renderBastionAwakening(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull Location end
    ) {
        Location center = player.getLocation().clone().add(0.0D, 0.95D, 0.0D);
        context.services().effects().line(
                center.clone().subtract(0.0D, 0.85D, 0.0D),
                center.clone().add(0.0D, 1.35D, 0.0D),
                0.14D,
                SharedParticleDefinitions.BASTION_STRIKE_SOUL_FIRE
        );
        context.services().effects().point(center, SharedParticleDefinitions.BASTION_STRIKE_RUNE_DUST);
        context.services().effects().sound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.1F, 0.85F);

        var castWorld = player.getWorld();
        context.services().tasks().repeat(
                player.getUniqueId(),
                ID + ":soul-bastion",
                0L,
                BASTION_PULSE_PERIOD_TICKS,
                BASTION_PULSE_COUNT,
                frame -> renderBastionPulse(context, player, castWorld, center, end, frame)
        );
    }

    /** 一段の拡張ルーンと最終着弾の火花を表示します。 */
    private void renderBastionPulse(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull org.bukkit.World castWorld,
            @NotNull Location center,
            @NotNull Location end,
            int frame
    ) {
        if (!player.isOnline() || player.getWorld() != castWorld) {
            return;
        }
        Location pulseCenter = center.clone().add(0.0D, frame * 0.18D, 0.0D);
        context.services().effects().ring(
                pulseCenter,
                0.65D + frame * 0.28D,
                24,
                SharedParticleDefinitions.BASTION_STRIKE_SOUL_FIRE
        );
        context.services().effects().ring(
                pulseCenter,
                0.42D + frame * 0.20D,
                16,
                SharedParticleDefinitions.BASTION_STRIKE_RUNE_DUST
        );
        if (frame == BASTION_PULSE_COUNT - 1) {
            context.services().effects().point(end, SharedParticleDefinitions.BASTION_STRIKE_IMPACT_FLASH);
            context.services().effects().point(end, SharedParticleDefinitions.BASTION_STRIKE_IMPACT_SPARK);
            context.services().effects().sound(end, Sound.ITEM_TRIDENT_THUNDER, 1.0F, 1.25F);
        }
    }

    /** 指定パラメータが正数であることを検証します。 */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "バスティオンストライクの params[" + key + "] は正数が必要です");
        }
    }
}
