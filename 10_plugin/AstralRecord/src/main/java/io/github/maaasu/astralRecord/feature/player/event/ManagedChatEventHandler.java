package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * バニラチャットを AstralRecord 管理チャットへ置き換えるイベントハンドラ。
 */
public final class ManagedChatEventHandler extends AbstractEventHandler {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
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
            plugin.getServer().getScheduler().runTask(plugin, () ->
                PlayerMessageService.getInstance().broadcastGlobalChat(event.getPlayer(), message)
            );
        }, LogId.E_3002, handlerName + ":chat");
    }

    /**
     * バニラDM系コマンドを無効化し、自前コマンド利用を促す。
     *
     * @param event コマンド前処理イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerCommandPreprocess(@NotNull PlayerCommandPreprocessEvent event) {
        runSafely(() -> {
            String raw = event.getMessage().trim().toLowerCase(Locale.ROOT);
            if (!isVanillaDirectMessageCommand(raw)) {
                return;
            }
            event.setCancelled(true);
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5945);
        }, LogId.E_3002, handlerName + ":command");
    }

    static boolean isVanillaDirectMessageCommand(@NotNull String raw) {
        String commandToken = raw.split("\\s+", 2)[0];
        if (commandToken.startsWith("/")) {
            commandToken = commandToken.substring(1);
        }
        int namespaceSeparator = commandToken.lastIndexOf(':');
        if (namespaceSeparator >= 0) {
            commandToken = commandToken.substring(namespaceSeparator + 1);
        }
        return commandToken.equals("msg")
            || commandToken.equals("tell")
            || commandToken.equals("w")
            || commandToken.equals("whisper");
    }
}
