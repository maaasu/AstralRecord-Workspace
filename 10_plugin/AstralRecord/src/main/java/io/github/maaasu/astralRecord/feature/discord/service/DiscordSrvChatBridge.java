package io.github.maaasu.astralRecord.feature.discord.service;

import github.scarsz.configuralize.DynamicConfig;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.ListenerPriority;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.dependencies.kyori.adventure.text.Component;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.service.ChatMessageSanitizer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DiscordSRVのAPIイベントを利用した全体チャット中継。
 * DiscordSRVが未導入の場合は生成されず、AstralRecord単体の動作を維持する。
 */
public final class DiscordSrvChatBridge implements GlobalChatBridge {
    private static final String SERVER_STARTUP_MESSAGE_KEY = "DiscordChatChannelServerStartupMessage";
    private static final String SERVER_SHUTDOWN_MESSAGE_KEY = "DiscordChatChannelServerShutdownMessage";
    private static final List<String> PLAYER_LIFECYCLE_MESSAGE_ENABLED_KEYS = List.of(
        "MinecraftPlayerJoinMessage.Enabled",
        "MinecraftPlayerFirstJoinMessage.Enabled",
        "MinecraftPlayerLeaveMessage.Enabled"
    );

    private static boolean lifecycleMessagesSuppressed;
    private static String originalServerStartupMessage;
    private static String originalServerShutdownMessage;
    private static final Map<String, Boolean> originalPlayerLifecycleMessageEnabled = new LinkedHashMap<>();

    private final AstralRecord plugin;
    private final PlayerMessageService playerMessageService;
    private final String globalChannelId;
    private final int maxMessageLength;
    private volatile boolean subscribed;
    private volatile boolean maintenanceMode;

    private DiscordSrvChatBridge(
        @NotNull AstralRecord plugin,
        @NotNull PlayerMessageService playerMessageService,
        @NotNull String globalChannelId,
        int maxMessageLength
    ) {
        this.plugin = plugin;
        this.playerMessageService = playerMessageService;
        this.globalChannelId = globalChannelId;
        this.maxMessageLength = maxMessageLength;
    }

    /**
     * DiscordSRVが有効な場合だけ中継を登録する。
     *
     * @param plugin AstralRecord
     * @param playerMessageService プレイヤーメッセージサービス
     * @return 登録された中継。利用できない場合は {@code null}
     */
    public static @Nullable DiscordSrvChatBridge create(
        @NotNull AstralRecord plugin,
        @NotNull PlayerMessageService playerMessageService
    ) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("DiscordSRV")) {
            Logger.log(LogId.W_7100);
            return null;
        }

        ConfigProperties config = ConfigProperties.getInstance();
        String channelId = Objects.requireNonNullElse(config.getDiscordGlobalChannelId(), "").trim();
        if (channelId.isBlank()) {
            Logger.log(LogId.W_7101);
            return null;
        }

        DiscordSrvChatBridge bridge = new DiscordSrvChatBridge(
            plugin,
            playerMessageService,
            channelId,
            config.getDiscordMaxMessageLength()
        );
        DiscordSRV.api.subscribe(bridge);
        bridge.subscribed = true;
        Logger.log(LogId.I_7100, channelId);
        return bridge;
    }

    /**
     * DiscordSRVの起動・停止・プレイヤー参加・初回参加・退出通知をwhitelist状態に合わせて抑制します。
     * DiscordSRV 1.30.5 の messages.yml は実サーバーの設定ファイルを書き換えず、
     * DynamicConfig のランタイム値だけを変更します。
     *
     * @param suppressed 抑制する場合は {@code true}
     */
    public static synchronized void setServerLifecycleMessagesSuppressed(boolean suppressed) {
        if (!Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            return;
        }

        try {
            setServerLifecycleMessagesSuppressed(DiscordSRV.config(), suppressed);
        } catch (LinkageError | RuntimeException exception) {
            Logger.log(LogId.W_7110, exception, exception.getClass().getSimpleName());
        }
    }

    /**
     * DiscordSRVの自動通知設定をランタイム値へ反映します。
     *
     * @param config DiscordSRVの動的設定
     * @param suppressed 抑制する場合は {@code true}
     */
    static synchronized void setServerLifecycleMessagesSuppressed(
        @NotNull DynamicConfig config,
        boolean suppressed
    ) {
        if (suppressed) {
            if (!lifecycleMessagesSuppressed) {
                originalServerStartupMessage = config.getString(SERVER_STARTUP_MESSAGE_KEY);
                originalServerShutdownMessage = config.getString(SERVER_SHUTDOWN_MESSAGE_KEY);
                originalPlayerLifecycleMessageEnabled.clear();
                for (String key : PLAYER_LIFECYCLE_MESSAGE_ENABLED_KEYS) {
                    originalPlayerLifecycleMessageEnabled.put(
                        key,
                        config.getOptionalBoolean(key).orElse(true)
                    );
                }
                lifecycleMessagesSuppressed = true;
            }
            config.setRuntimeValue(SERVER_STARTUP_MESSAGE_KEY, "");
            config.setRuntimeValue(SERVER_SHUTDOWN_MESSAGE_KEY, "");
            for (String key : PLAYER_LIFECYCLE_MESSAGE_ENABLED_KEYS) {
                config.setRuntimeValue(key, false);
            }
            return;
        }

        if (lifecycleMessagesSuppressed) {
            config.setRuntimeValue(
                SERVER_STARTUP_MESSAGE_KEY,
                Objects.requireNonNullElse(originalServerStartupMessage, "")
            );
            config.setRuntimeValue(
                SERVER_SHUTDOWN_MESSAGE_KEY,
                Objects.requireNonNullElse(originalServerShutdownMessage, "")
            );
            for (Map.Entry<String, Boolean> entry : originalPlayerLifecycleMessageEnabled.entrySet()) {
                config.setRuntimeValue(entry.getKey(), entry.getValue());
            }
            lifecycleMessagesSuppressed = false;
            originalServerStartupMessage = null;
            originalServerShutdownMessage = null;
            originalPlayerLifecycleMessageEnabled.clear();
        }
    }

    /**
     * whitelist有効時のDiscord連携状態を反映します。
     * DiscordからMinecraftへの受信処理は維持します。
     *
     * @param maintenanceMode メンテナンス中なら {@code true}
     */
    @Override
    public void setMaintenanceMode(boolean maintenanceMode) {
        this.maintenanceMode = maintenanceMode;
        setServerLifecycleMessagesSuppressed(maintenanceMode);
    }

    @Override
    public void publishMinecraftGlobalChat(@NotNull Player sender, @NotNull String message) {
        if (maintenanceMode || !subscribed || !DiscordSRV.isReady) {
            return;
        }

        String normalizedMessage = ChatMessageSanitizer.normalize(message, maxMessageLength);
        if (normalizedMessage.isBlank()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (maintenanceMode || !subscribed || !DiscordSRV.isReady) {
                return;
            }
            try {
                DiscordSRV.getPlugin().processChatMessage(
                    sender,
                    Component.text(normalizedMessage),
                    "global",
                    false,
                    null
                );
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_7100, exception, exception.getClass().getSimpleName());
            }
        });
    }

    /**
     * DiscordSRVの標準受信処理をキャンセルし、AstralRecordの整形で配信する。
     *
     * @param event DiscordSRV受信前イベント
     */
    @Subscribe(priority = ListenerPriority.HIGHEST)
    public void onDiscordMessage(@NotNull DiscordGuildMessagePreProcessEvent event) {
        if (!subscribed || !globalChannelId.equals(event.getChannel().getId())) {
            return;
        }

        event.setCancelled(true);
        if (event.getAuthor().isBot()) {
            return;
        }

        var accountLinkManager = DiscordSRV.getPlugin().getAccountLinkManager();
        if (accountLinkManager == null || accountLinkManager.getUuid(event.getAuthor().getId()) == null) {
            return;
        }

        String message = ChatMessageSanitizer.normalize(
            event.getMessage().getContentRaw(),
            maxMessageLength
        );
        if (message.isBlank()) {
            return;
        }

        String authorName = event.getMember() == null
            ? event.getAuthor().getName()
            : event.getMember().getEffectiveName();
        authorName = ChatMessageSanitizer.normalize(authorName, 64);
        if (authorName.isBlank()) {
            authorName = event.getAuthor().getName();
        }

        String finalAuthorName = authorName;
        plugin.getServer().getScheduler().runTask(
            plugin,
            () -> playerMessageService.broadcastDiscordGlobalChat(finalAuthorName, message)
        );
    }

    /**
     * AstralRecordがキャンセルしたBukkitチャットをDiscordSRVが再送しないようにする。
     * 明示的なMinecraft中継はtriggeringBukkitEventがnullのため、この処理ではキャンセルしない。
     *
     * @param event DiscordSRVのMinecraftチャット処理前イベント
     */
    @Subscribe(priority = ListenerPriority.HIGHEST)
    public void onGameChatMessagePreProcess(@NotNull GameChatMessagePreProcessEvent event) {
        if (maintenanceMode) {
            event.setCancelled(true);
            return;
        }
        if (event.getTriggeringBukkitEvent() instanceof Cancellable cancellable && cancellable.isCancelled()) {
            event.setCancelled(true);
        }
    }

    @Override
    public void close() {
        if (!subscribed) {
            return;
        }
        subscribed = false;
        DiscordSRV.api.unsubscribe(this);
    }
}
