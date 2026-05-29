package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Display;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /temp コマンドでプレイヤー追従の一時テキスト表示をトグルします。
 * <p>
 * 初回実行では視点の 3m 先へ出現し、次 tick でプレイヤー追従へ切り替えます。
 * 再実行で表示を削除します。
 */
public class TempCommand extends AstCommand {

    private static final double SPAWN_DISTANCE = 3.0D;
    private static final double FOLLOW_HEIGHT_OFFSET = 0.35D;
    private static final Map<UUID, DisplayTextService.ManagedTextDisplay> ACTIVE_DISPLAYS = new ConcurrentHashMap<>();

    /**
     * TempCommand を初期化します。
     */
    public TempCommand() {
        super(
                "temp",
                "プレイヤー追従の一時テキスト表示を切り替えます。",
                "/temp",
                true
        );
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        UUID playerId = player.getBukkit().getUniqueId();
        DisplayTextService.ManagedTextDisplay activeDisplay = ACTIVE_DISPLAYS.remove(playerId);
        if (activeDisplay != null) {
            activeDisplay.destroy();
            player.sendMessage(PlayerMsgId.P_5081);
            return;
        }

        String text = buildText(player, args);
        DisplayTextService displayService = requireDisplayService();
        DisplayTextService.ManagedTextDisplay display = displayService.create(
                DisplayAnchor.view(player.getBukkit(), SPAWN_DISTANCE, new Vector()),
                DisplayTextOptions.defaults(text)
                        .withBillboard(Display.Billboard.CENTER)
                        .withSeeThrough(true)
                        .withShadowed(true)
                        .withViewRange(64.0F)
                        .withInterpolationDuration(2)
                        .withTeleportDuration(2)
        );
        ACTIVE_DISPLAYS.put(playerId, display);

        Plugin plugin = AstralRecord.getInstance();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            DisplayTextService.ManagedTextDisplay current = ACTIVE_DISPLAYS.get(playerId);
            if (current != display) {
                return;
            }
            current.setAnchor(DisplayAnchor.entity(player.getBukkit(), new Vector(0.0D, player.getBukkit().getHeight() + FOLLOW_HEIGHT_OFFSET, 0.0D)));
            current.setText(text);
        }, 1L);

        player.sendMessage(PlayerMsgId.P_5080, text);
    }

    private @NotNull DisplayTextService requireDisplayService() {
        DisplayTextService displayService = AstralRecord.getInstance().getDisplayTextService();
        if (displayService == null) {
            throw new IllegalStateException("DisplayTextService is not initialized.");
        }
        return displayService;
    }

    private @NotNull String buildText(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            return player.getBukkit().getName();
        }
        String joined = joinArgs(args, 0).trim();
        if (joined.isEmpty()) {
            return player.getBukkit().getName();
        }
        return joined;
    }
}
