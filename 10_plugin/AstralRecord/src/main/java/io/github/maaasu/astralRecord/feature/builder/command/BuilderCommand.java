package io.github.maaasu.astralRecord.feature.builder.command;

import io.github.maaasu.astralRecord.feature.builder.gui.BuilderGui;
import io.github.maaasu.astralRecord.feature.builder.service.BuilderService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 建築用WorldEdit GUIの入口です。 */
public final class BuilderCommand extends AstCommand {
    private final BuilderGui gui;

    /** @param gui 表示するビルダーGUI */
    public BuilderCommand(BuilderGui gui) {
        super("builder", "日本語の案内付き建築GUIを開きます。", "/builder", true, UserPermission.ADMIN.getValue());
        this.gui = gui;
    }

    /** GUIの専用権限を持つビルダーもコマンドを利用できます。 */
    @Override
    protected boolean hasPermissionOverride(@NotNull CommandSender sender) {
        return sender instanceof Player player && BuilderService.hasAccess(player);
    }

    /** プレイヤー自身のGUIを開きます。引数は受け付けません。 */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length != 0) { sendUsage(player.getBukkit()); return; }
        gui.open(player.getBukkit());
    }
}
