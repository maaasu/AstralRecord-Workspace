package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;
import java.util.UUID;

/** 聖域に留まって撃つほど強くなる、遮蔽を貫かない聖槍です。 */
public final class PaladinBlessedLanceExecutor extends PaladinBuildSkillSupport {
    public static final String ID = "paladin_blessed_lance";
    private final PaladinHolyFieldRuntimeService field;

    /**
     * 聖域の内外を判定する発動処理を作成します。
     * @param services 共通戦闘サービス
     * @param field 発動者のホーリーフィールド
     */
    public PaladinBlessedLanceExecutor(@NotNull ActiveSkillServices services,
                                       @NotNull PaladinHolyFieldRuntimeService field) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio", "fieldMultiplier");
        this.field = field;
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        double ratio = value(context, "damageRatio")
                * (field.isCasterWithinField(context.player().getUniqueId()) ? value(context, "fieldMultiplier") : 1.0D);
        var origin = context.eyeLocation();
        var direction = context.direction();
        // 共通処理が本体MPを確定してから、MPを追加消費するディバインチェイサーを通知する。
        context.services().tasks().later(context.player().getUniqueId(), ID + ":" + UUID.randomUUID(), 1L, () -> {
            if (!context.player().isOnline() || context.player().isDead()
                    || context.player().getWorld() != origin.getWorld()
                    || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) return;
            var end = context.services().targeting().clippedEnd(origin, direction, value(context, "range"));
            context.services().effects().line(origin, end, 0.25D,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMASH_END_ROD);
            context.services().effects().ring(end, 0.65D, 20,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST);
            for (var target : context.services().targeting().inLineBeforeBlock(context.player(), origin,
                    direction, origin.distance(end), value(context, "hitRadius"),
                    (int) value(context, "maxTargets"))) {
                context.services().combat().hit(context.source().skill(), context.attacker(), target,
                        AttackType.MELEE, DamageElement.NONE, ratio);
            }
        });
        return context.success();
    }
}
