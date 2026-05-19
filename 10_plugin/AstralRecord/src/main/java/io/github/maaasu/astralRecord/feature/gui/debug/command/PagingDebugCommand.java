package io.github.maaasu.astralRecord.feature.gui.debug.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * ページング確認用のダミー GUI を開くコマンド。
 */
public final class PagingDebugCommand extends AstCommand {

    /**
     * /pagingdummy コマンドを生成します。
     */
    public PagingDebugCommand() {
        super("pagingdummy", "Open paging dummy GUI.", "/pagingdummy", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        AstralRecord.getInstance().getPagingDebugGui().open(player.getBukkit(), 0);
    }
}
