package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.mob.service.MobTauntService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * プレイヤー lifecycle に応じた詠唱・継続スキル・一時効果の破棄を一元化します。
 */
public final class ActiveSkillLifecycleService {

    private final SkillService skillService;
    private final SkillTaskService taskService;
    private final TemporarySkillEffectService temporaryEffectService;
    private final MobTauntService tauntService;
    private Consumer<UUID> additionalCleanup = ignored -> { };

    /** lifecycle に追従する runtime サービスで初期化します。 */
    public ActiveSkillLifecycleService(
            @NotNull SkillService skillService,
            @NotNull SkillTaskService taskService,
            @NotNull TemporarySkillEffectService temporaryEffectService
    ) {
        this(skillService, taskService, temporaryEffectService, null);
    }

    /** lifecycle に追従する runtime サービスで初期化します。 */
    public ActiveSkillLifecycleService(
            @NotNull SkillService skillService,
            @NotNull SkillTaskService taskService,
            @NotNull TemporarySkillEffectService temporaryEffectService,
            @Nullable MobTauntService tauntService
    ) {
        this.skillService = skillService;
        this.taskService = taskService;
        this.temporaryEffectService = temporaryEffectService;
        this.tauntService = tauntService;
    }

    /**
     * 共通の破棄に続けて呼ぶ、職業固有状態の破棄処理を設定します。
     * @param cleanup 世界移動・死亡・退出で対象UUIDの状態を解除する処理
     */
    public void setAdditionalCleanup(@NotNull Consumer<UUID> cleanup) {
        this.additionalCleanup = cleanup;
    }

    /**
     * world 移動時に cooldown を保持し、進行中の効果だけを破棄します。
     * 共通解除が例外になっても、追加の職業状態解除を実行します。
     *
     * @param playerId 対象プレイヤー UUID
     */
    public void clearTransient(@NotNull UUID playerId) {
        try {
            skillService.cancelCasting(playerId);
            taskService.clearCaster(playerId);
            temporaryEffectService.clear(playerId);
            if (tauntService != null) {
                tauntService.clearByTaunter(playerId);
            }
        } finally {
            additionalCleanup.accept(playerId);
        }
    }

    /**
     * 退出・死亡時に cooldown を含む全 runtime 状態を破棄します。
     * 共通解除が例外になっても、追加の職業状態解除を実行します。
     *
     * @param playerId 対象プレイヤー UUID
     */
    public void clearAll(@NotNull UUID playerId) {
        try {
            skillService.clearCasterState(playerId);
            taskService.clearCaster(playerId);
            temporaryEffectService.clear(playerId);
            if (tauntService != null) {
                tauntService.clearByTaunter(playerId);
            }
        } finally {
            additionalCleanup.accept(playerId);
        }
    }
}
