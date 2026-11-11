package io.github.maaasu.astralRecord.feature.builder.command;

import io.github.maaasu.astralRecord.feature.builder.gui.BuilderGui;
import io.github.maaasu.astralRecord.feature.builder.service.BuilderService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** 建築用WorldEdit GUIとdevの建築ファイル登録を開きます。 */
public final class BuilderCommand extends AstCommand {
    static final String UPLOAD = "upload";
    static final String LOAD = "load";
    private final BuilderGui gui;

    /** @param gui 表示するビルダーGUI */
    public BuilderCommand(BuilderGui gui) {
        super("builder", "建築GUIやdevの建築ファイル登録を開きます。",
                "/builder [" + UPLOAD + "|" + LOAD + "]", true, UserPermission.ADMIN.getValue());
        this.gui = gui;
    }

    /** GUIの専用権限を持つビルダーもコマンドを利用できます。 */
    @Override
    protected boolean hasPermissionOverride(@NotNull CommandSender sender) {
        return sender instanceof Player player && BuilderService.hasAccess(player);
    }

    /** 引数に応じて本人のホーム、登録リンク、または登録済み一覧を開きます。 */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        Player sender = player.getBukkit();
        if (args.length == 0) {
            gui.open(sender);
        } else if (args.length == 1) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case UPLOAD -> gui.requestSchematicUpload(sender);
                case LOAD -> gui.openRegisteredSchematics(sender);
                default -> sendUsage(sender);
            }
        } else {
            sendUsage(sender);
        }
    }
}
