package io.github.maaasu.astralRecord.feature.player.save;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * プレイヤーデータ保存タスクをまとめて実行する調整クラス。
 */
public class PlayerSaveCoordinator {

    private final List<PlayerSaveTask> tasks;

    /**
     * 保存タスク一覧を受け取り、実行順を固定したコーディネーターを生成します。
     *
     * @param tasks 実行する保存タスク一覧
     */
    public PlayerSaveCoordinator(@NotNull List<PlayerSaveTask> tasks) {
        this.tasks = new ArrayList<>(tasks);
    }

    /**
     * 指定プレイヤーの保存タスクを順に実行します。
     * <p>
     * 個別タスクで例外が発生しても残りのタスク実行は継続します。
     *
     * @param player 保存対象プレイヤー
     * @param trigger 保存の発火契機
     */
    public void save(@NotNull AstPlayer player, @NotNull PlayerSaveTrigger trigger) {
        for (PlayerSaveTask task : tasks) {
            try {
                task.save(player, trigger);
            } catch (Exception e) {
                Logger.log(
                    LogId.W_5071,
                    task.getTaskName(),
                    trigger.name(),
                    player.getBukkit().getName(),
                    e.getMessage()
                );
            }
        }
    }
}
