package io.github.maaasu.astralRecord.feature.textdisplay.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import io.github.maaasu.astralRecord.feature.textdisplay.service.TextDisplayPlacementService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * /textdisplay コマンドです。
 */
public final class TextDisplayCommand extends AstCommand {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private final TextDisplayPlacementService placementService;

    /**
     * TextDisplayCommand を初期化します。
     *
     * @param placementService 固定 TextDisplay 配置サービス
     */
    public TextDisplayCommand(@NotNull TextDisplayPlacementService placementService) {
        super("textdisplay", "Manage fixed TextDisplays.", "/textdisplay <place|remove|list|reload> ...",
                true, UserPermission.ADMIN.getValue());
        this.placementService = placementService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "place" -> handlePlace(player, args);
            case "remove" -> handleRemove(player, args);
            case "list" -> handleList(player);
            case "reload" -> handleReload(player);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handlePlace(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 3) {
            sendUsage(player.getBukkit());
            return;
        }

        String id = args[1];
        if (!ID_PATTERN.matcher(id).matches()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5774.getId()));
            return;
        }

        String text = joinArgs(args, 2).replace("\\n", "\n");
        TextDisplayPlacement placement = placementService.place(id, text, player.getBukkit().getLocation());
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5770.getId(),
                placement.id(),
                placement.worldName(),
                String.format(Locale.ROOT, "%.2f", placement.x()),
                String.format(Locale.ROOT, "%.2f", placement.y()),
                String.format(Locale.ROOT, "%.2f", placement.z())
        ));
    }

    private void handleRemove(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        if (!placementService.remove(args[1])) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5775.getId(), args[1]));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5771.getId(), args[1]));
    }

    private void handleList(@NotNull AstPlayer player) {
        var placements = placementService.getPlacements();
        if (placements.isEmpty()) {
            sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5776.getId()));
            return;
        }

        String ids = placements.stream()
                .map(TextDisplayPlacement::id)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        sendInfo(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5772.getId(), placements.size(), ids));
    }

    private void handleReload(@NotNull AstPlayer player) {
        int count = placementService.loadAll();
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5773.getId(), count));
    }
}
