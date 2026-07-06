package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTask;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTrigger;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーの現在クラス進行度を account API へ保存するタスクです。
 */
public final class AccountClassProgressSaveTask implements PlayerSaveTask {

    private final AccountService accountService;

    public AccountClassProgressSaveTask(@NotNull AccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    public @NotNull String getTaskName() {
        return "account-class-progress";
    }

    @Override
    public void save(@NotNull AstPlayer player, @NotNull PlayerSaveTrigger trigger) {
        accountService.saveClassProgressNow(player);
    }
}
