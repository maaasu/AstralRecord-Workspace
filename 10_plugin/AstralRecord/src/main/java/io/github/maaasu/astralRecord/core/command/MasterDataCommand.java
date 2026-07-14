package io.github.maaasu.astralRecord.core.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Administrative master-data operations that do not require a plugin restart. */
public final class MasterDataCommand extends AstCommand {
    private static final int REQUIRED_PERMISSION = 99;
    private final AstralRecord plugin;

    public MasterDataCommand(AstralRecord plugin) {
        super("masterdata", "Reload AstralRecord master data.", "/masterdata reload", false, REQUIRED_PERMISSION);
        this.plugin = plugin;
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player player) {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || !astPlayer.hasPermissionLevel(REQUIRED_PERMISSION)) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return;
            }
        }
        if (args.length != 1 || !"reload".equalsIgnoreCase(args[0])) {
            sendError(sender, "Usage: /masterdata reload");
            return;
        }

        try {
            int loaded = plugin.reloadMasterData();
            sendSuccess(sender, "Master data reloaded: " + loaded + " definitions.");
        } catch (Exception exception) {
            sendError(sender, "Master data reload failed: " + exception.getMessage());
        }
    }
}
