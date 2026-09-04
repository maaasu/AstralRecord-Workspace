package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * バニラチャットを AstralRecord 管理チャットへ置き換えるイベントハンドラ。
 */
public final class ManagedChatEventHandler extends AbstractEventHandler {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final Set<String> VANILLA_DIRECT_MESSAGE_COMMANDS = Set.of("msg", "tell", "w", "whisper");
    private static final Set<String> VANILLA_GLOBAL_MESSAGE_COMMANDS = Set.of("say");
    private final AstralRecord plugin;

    /**
     * ManagedChatEventHandler を初期化する。
     *
     * @param plugin プラグインインスタンス
     */
    public ManagedChatEventHandler(@NotNull AstralRecord plugin) {
        this.plugin = plugin;
    }

    /**
     * バニラ全体チャットをキャンセルし、自前フォーマットで再配信する。
     *
     * @param event 非同期チャットイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAsyncChat(@NotNull AsyncChatEvent event) {
        runSafely(() -> {
            event.setCancelled(true);
            String message = PLAIN_TEXT.serialize(event.message()).trim();
            if (message.isBlank()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                PartyService partyService = plugin.getPartyService();
                if (partyService != null
                    && partyService.isPartyChatEnabled(event.getPlayer().getUniqueId())) {
                    partyService.broadcastPartyChat(event.getPlayer(), message);
                    return;
                }
                PlayerMessageService.getInstance().broadcastGlobalChat(event.getPlayer(), message);
            });
        }, LogId.E_3002, handlerName + ":chat");
    }

    /**
     * バニラのメッセージ送信コマンドを AstralRecord 管理の送信経路へ変換します。
     *
     * @param event コマンド前処理イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerCommandPreprocess(@NotNull PlayerCommandPreprocessEvent event) {
        runSafely(() -> {
            String raw = event.getMessage().trim();
            if (isVanillaDirectMessageCommand(raw)) {
                String arguments = commandArguments(raw);
                event.setMessage(arguments.isBlank() ? "/message" : "/message " + arguments);
                return;
            }
            if (isVanillaGlobalMessageCommand(raw)) {
                event.setCancelled(true);
                String message = commandArguments(raw);
                if (message.isBlank()) {
                    PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5946);
                    return;
                }
                PlayerMessageService.getInstance().broadcastGlobalChat(event.getPlayer(), message);
                return;
            }
            if (isGuideHelpCommand(raw) && hasPlayerPermission(event.getPlayer())) {
                event.setMessage("/guide");
            }
        }, LogId.E_3002, handlerName + ":command");
    }

    static boolean isVanillaDirectMessageCommand(@NotNull String raw) {
        return VANILLA_DIRECT_MESSAGE_COMMANDS.contains(commandToken(raw));
    }

    static boolean isVanillaGlobalMessageCommand(@NotNull String raw) {
        return VANILLA_GLOBAL_MESSAGE_COMMANDS.contains(commandToken(raw));
    }

    static boolean isGuideHelpCommand(@NotNull String raw) {
        return commandToken(raw).equals("help") && commandArguments(raw).isBlank();
    }

    private static @NotNull String commandToken(@NotNull String raw) {
        String commandToken = raw.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (commandToken.startsWith("/")) {
            commandToken = commandToken.substring(1);
        }
        int namespaceSeparator = commandToken.lastIndexOf(':');
        if (namespaceSeparator >= 0) {
            commandToken = commandToken.substring(namespaceSeparator + 1);
        }
        return commandToken;
    }

    private static @NotNull String commandArguments(@NotNull String raw) {
        String trimmed = raw.trim();
        int separator = -1;
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isWhitespace(trimmed.charAt(index))) {
                separator = index;
                break;
            }
        }
        return separator < 0 ? "" : trimmed.substring(separator + 1).trim();
    }

    private static boolean hasPlayerPermission(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null
            && astPlayer.getUser().getPermission() == UserPermission.PLAYER.getValue();
    }
}
