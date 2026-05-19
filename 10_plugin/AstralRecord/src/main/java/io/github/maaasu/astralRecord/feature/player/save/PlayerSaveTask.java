package io.github.maaasu.astralRecord.feature.player.save;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーデータ保存処理の単位を表すインターフェース。
 */
public interface PlayerSaveTask {
    /**
     * ログ出力や識別に使用する保存タスク名を返します。
     *
     * @return 保存タスク名
     */
    @NotNull String getTaskName();

    /**
     * 指定プレイヤーのデータを保存します。
     *
     * @param player 保存対象プレイヤー
     * @param trigger 保存の発火契機
     */
    void save(@NotNull AstPlayer player, @NotNull PlayerSaveTrigger trigger);
}
