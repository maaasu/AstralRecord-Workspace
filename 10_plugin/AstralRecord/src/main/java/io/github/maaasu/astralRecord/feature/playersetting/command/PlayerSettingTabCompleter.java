package io.github.maaasu.astralRecord.feature.playersetting.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * プレイヤー設定コマンドのタブ補完です。
 */
public final class PlayerSettingTabCompleter extends AstTabCompleter {

    public PlayerSettingTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new java.util.ArrayList<>(PlayerSettingKey.completionKeys());
            values.add("gui");
            return values;
        }
        if (args.length == 2) {
            PlayerSettingKey key = PlayerSettingKey.fromInput(args[0]);
            if (key != null) {
                return key.completionValues();
            }
        }
        return List.of();
    }
}
