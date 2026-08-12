package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * プレイヤー lifecycle に応じた詠唱・継続スキル・一時効果の破棄を一元化します。
 */
public final class ActiveSkillLifecycleService {

    private final SkillService skillService;
    private final SkillTaskService taskService;
    private final TemporarySkillEffectService temporaryEffectService;
    private Consumer<UUID> additionalClearer = playerId -> { };

    /** lifecycle に追従する runtime サービスで初期化します。 */
    public ActiveSkillLifecycleService(
            @NotNull SkillService skillService,
            @NotNull SkillTaskService taskService,
            @NotNull TemporarySkillEffectService temporaryEffectService
    ) {
        this.skillService = skillService;
        this.taskService = taskService;
        this.temporaryEffectService = temporaryEffectService;
    }

    /**
     * 共通一時効果以外のplayer runtimeを破棄する追加処理を設定します。
     *
     * @param additionalClearer player UUID単位の破棄処理
     */
    public void setAdditionalClearer(@NotNull Consumer<UUID> additionalClearer) {
        this.additionalClearer = additionalClearer;
    }

    /**
     * world 移動時に cooldown を保持し、進行中の効果だけを破棄します。
     *
     * @param playerId 対象プレイヤー UUID
     */
    public void clearTransient(@NotNull UUID playerId) {
        skillService.cancelCasting(playerId);
        taskService.clearCaster(playerId);
        temporaryEffectService.clear(playerId);
        additionalClearer.accept(playerId);
    }

    /**
     * 退出・死亡時に cooldown を含む全 runtime 状態を破棄します。
     *
     * @param playerId 対象プレイヤー UUID
     */
    public void clearAll(@NotNull UUID playerId) {
        skillService.clearCasterState(playerId);
        taskService.clearCaster(playerId);
        temporaryEffectService.clear(playerId);
        additionalClearer.accept(playerId);
    }
}
