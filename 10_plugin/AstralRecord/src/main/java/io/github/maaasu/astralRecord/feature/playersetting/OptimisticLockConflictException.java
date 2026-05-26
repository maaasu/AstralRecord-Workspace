package io.github.maaasu.astralRecord.feature.playersetting;

import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingModel;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * プレイヤー設定の楽観ロック競合を表す例外です。
 */
public final class OptimisticLockConflictException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient PlayerSettingModel current;

    public OptimisticLockConflictException(@NotNull PlayerSettingModel current) {
        super("Player setting update conflicted: " + current.getSettingKey());
        this.current = current;
    }

    public @NotNull PlayerSettingModel getCurrent() {
        return current;
    }
}
