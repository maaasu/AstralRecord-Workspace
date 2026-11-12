package io.github.maaasu.astralRecord.feature.builder.command;

import io.github.maaasu.astralRecord.feature.builder.gui.BuilderGui;
import io.github.maaasu.astralRecord.feature.builder.service.BuilderService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** dev のビルダーに建築ファイル登録用の引数を補完します。 */
public final class BuilderTabCompleter extends AstTabCompleter {
    private final BuilderGui gui;

    /** @param gui 建築ファイル登録の有効状態を提供するビルダーGUI */
    public BuilderTabCompleter(BuilderGui gui) {
        super(true);
        this.gui = gui;
    }

    /**
     * 接続設定に関係なく、Dev のビルダーにだけ候補を返します。
     * @param player 入力中のプレイヤー
     * @param args 入力済みのコマンド引数
     * @return 最初の引数の候補。利用できない場合は空リスト
     */
    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length != 1 || !gui.schematicImportEnabled() || !BuilderService.hasAccess(player.getBukkit())) {
            return List.of();
        }
        return List.of(BuilderCommand.UPLOAD, BuilderCommand.LOAD);
    }
}
