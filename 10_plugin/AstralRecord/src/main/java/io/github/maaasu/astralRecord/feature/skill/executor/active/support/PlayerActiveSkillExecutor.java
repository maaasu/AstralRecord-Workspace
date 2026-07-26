package io.github.maaasu.astralRecord.feature.skill.executor.active.support;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

/**
 * 1スキル1クラスのプレイヤー発動スキルに共通する変換と検証を提供します。
 */
public abstract class PlayerActiveSkillExecutor implements SkillExecutor {

    private final String implementationId;
    private final ActiveSkillServices services;

    /**
     * implementation ID と共有サービスで初期化します。
     *
     * @param implementationId skill ID と一致する実装 ID
     * @param services 発動スキル共有サービス
     */
    protected PlayerActiveSkillExecutor(
            @NotNull String implementationId,
            @NotNull ActiveSkillServices services
    ) {
        this.implementationId = implementationId;
        this.services = services;
    }

    /** {@inheritDoc} */
    @Override
    public final @NotNull String implementationId() {
        return implementationId;
    }

    /** {@inheritDoc} */
    @Override
    public final @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        if (!(context.caster() instanceof PlayerSkillCaster caster)) {
            return SkillCastResult.failure(PlayerMsgId.P_5805);
        }
        return castPlayer(new PlayerActiveSkillContext(context, caster, services));
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        if (!implementationId.equals(skill.getId())) {
            throw new SkillParameterException("id", "skillId と implementationId を一致させてください");
        }
        if (!skill.getParams().isEmpty()) {
            throw new SkillParameterException("params", "コード定義スキルでは params を使用しません");
        }
    }

    /**
     * プレイヤー専用の個別スキル処理を実行します。
     *
     * @param context プレイヤー発動コンテキスト
     * @return 発動結果
     */
    protected abstract @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context);
}
