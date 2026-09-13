package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.executor.active.paladin.PaladinGuardRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import org.jetbrains.annotations.NotNull;

/** 被ダメージを専用リソース「ガード」へ変換するバインド必須パッシブです。 */
public final class PaladinGuardConvertSkillExecutor implements SkillExecutor {
    public static final String ID = "paladin_guard_convert";
    private final PaladinGuardRuntimeService guardRuntimeService;

    /** ガード状態サービスで初期化します。 */
    public PaladinGuardConvertSkillExecutor(@NotNull PaladinGuardRuntimeService guardRuntimeService) {
        this.guardRuntimeService = guardRuntimeService;
    }

    @Override
    public @NotNull String implementationId() {
        return ID;
    }

    @Override
    public @NotNull SkillKind kind() {
        return SkillKind.PASSIVE;
    }

    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }

    @Override
    public void onActivate(@NotNull PassiveSkillContext context) {
        int level = context.learnedSkill() == null ? 1 : context.learnedSkill().getLevel();
        java.util.UUID learnedSkillId = context.learnedSkill() == null
                ? context.player().getBukkit().getUniqueId()
                : context.learnedSkill().getLearnedSkillId();
        guardRuntimeService.activate(context.player(), learnedSkillId, level);
    }

    @Override
    public void onDeactivate(@NotNull PassiveSkillContext context) {
        java.util.UUID learnedSkillId = context.learnedSkill() == null
                ? context.player().getBukkit().getUniqueId()
                : context.learnedSkill().getLearnedSkillId();
        guardRuntimeService.deactivate(context.player(), learnedSkillId);
    }

    @Override
    public void onTick(@NotNull PassiveSkillContext context) {
        guardRuntimeService.advanceDecay(context.player());
    }

    @Override
    public boolean requiresPassiveTick() {
        return true;
    }

    @Override
    public long passiveTickIntervalTicks() {
        return 20L;
    }
}
