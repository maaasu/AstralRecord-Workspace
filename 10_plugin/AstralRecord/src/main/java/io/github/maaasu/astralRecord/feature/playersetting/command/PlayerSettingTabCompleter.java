package io.github.maaasu.astralRecord.feature.playersetting.command;

import io.github.maaasu.astralRecord.AstralRecord;
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

    /**
     * @param player コマンドを入力中のプレイヤー
     * @param args 入力済みの引数
     * @return 現在のクライアントで設定できる項目、または値の候補
     */
    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new java.util.ArrayList<>(PlayerSettingKey.completionKeys());
            values.removeIf(value -> {
                PlayerSettingKey key = PlayerSettingKey.fromInput(value);
                return key == PlayerSettingKey.PERFORMANCE_INFO_DISPLAY || !canEdit(player, key);
            });
            values.add("gui");
            return values;
        }
        if (args.length == 2) {
            PlayerSettingKey key = PlayerSettingKey.fromInput(args[0]);
            if (canEdit(player, key)) {
                return key.completionValues();
            }
        }
        return List.of();
    }

    /**
     * @param player コマンドを入力中のプレイヤー
     * @param key 解決済みの設定キー。不明な場合は null
     * @return コマンドで編集可能な項目の候補なら true
     */
    private boolean canEdit(AstPlayer player, PlayerSettingKey key) {
        if (key == null) return false;
        if (!player.isBedrock() || !key.isHudDisplaySetting()) return true;
        var gui = AstralRecord.getInstance().getPlayerSettingGui();
        return gui != null && gui.canEdit(player, key);
    }
}
