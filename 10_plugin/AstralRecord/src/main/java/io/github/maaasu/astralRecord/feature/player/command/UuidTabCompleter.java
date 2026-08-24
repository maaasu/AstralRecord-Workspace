package io.github.maaasu.astralRecord.feature.player.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code /uuid} のプレイヤー名補完を提供します。
 */
public final class UuidTabCompleter extends AstTabCompleter {

    /**
     * UUID コマンドのタブ補完を初期化します。
     */
    public UuidTabCompleter() {
        super();
    }

    /**
     * UUID コマンドのプレイヤー名補完候補を返します。
     *
     * @param sender コマンド送信者
     * @param args 入力済みのコマンド引数
     * @return 第1引数位置のオンラインプレイヤー名候補。それ以外は空リスト
     */
    @Override
    protected @NotNull List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return getOnlinePlayerNames();
    }
}
