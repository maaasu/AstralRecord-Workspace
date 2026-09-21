package io.github.maaasu.astralRecord.feature.history.model;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 管理用履歴へ残す、発生時点のプレイヤー識別子と表示名です。 */
public record ActivityPlayerSnapshot(
    @NotNull UUID userUuid,
    @NotNull UUID accountId,
    @NotNull String mcid,
    @NotNull String accountName
) {
    /** 読み込み済みのゲームプレイヤーから表示名を含むスナップショットを作成します。 */
    public static @NotNull ActivityPlayerSnapshot from(@NotNull AstPlayer player) {
        return new ActivityPlayerSnapshot(
            player.getUser().getUuid(),
            player.getAccount().getUuid(),
            player.getUser().getMcid(),
            player.getAccount().getAccountName()
        );
    }
}
