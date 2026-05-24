package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * /test コマンドの実装クラス。
 * <p>
 * AstPlayer を使用したコマンド実装のサンプルです。
 * プレイヤー限定コマンドとして動作し、実行したプレイヤーの情報を表示します。
 * <p>
 * 使用方法:
 * <pre>
 * /test           - プレイヤー情報を表示
 * /test info      - プレイヤー情報を表示
 * /test permission - ユーザー権限レベルを表示
 * </pre>
 */
public class TestCommand extends AstCommand {

    /**
     * TestCommand を初期化します。
     */
    public TestCommand() {
        super("test", "テストコマンド（AstPlayer 使用例）", "/test [info|permission]", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            showPlayerInfo(player);
            return;
        }

        if (args[0].equalsIgnoreCase("permission")) {
            showPermission(player);
            return;
        }

        sendUsage(player.getBukkit());
    }

    /**
     * プレイヤー情報を表示します。
     *
     * @param player 対象の {@link AstPlayer}
     */
    private void showPlayerInfo(@NotNull AstPlayer player) {
        player.sendMessage(
                PlayerMsgId.P_5050,
                player.getUser().getMcid(),
                player.getBukkit().getUniqueId()
        );
    }

    /**
     * ユーザー権限レベルを表示します。
     *
     * @param player 対象の {@link AstPlayer}
     */
    private void showPermission(@NotNull AstPlayer player) {
        player.sendMessage(
                PlayerMsgId.P_5051,
                player.getUser().getPermission()
        );
    }
}

