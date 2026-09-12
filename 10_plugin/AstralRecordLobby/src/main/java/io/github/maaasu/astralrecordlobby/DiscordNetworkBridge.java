package io.github.maaasu.astralrecordlobby;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.configuralize.DynamicConfig;
import github.scarsz.discordsrv.api.ListenerPriority;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.util.DiscordUtil;
import github.scarsz.discordsrv.util.WebhookUtil;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.Permission;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class DiscordNetworkBridge {
    private static final int DISCORD_WEBHOOK_NAME_MAX_LENGTH = 80;
    private static final int SYSTEM_EMBED_COLOR = 0x5865F2;
    private static final java.util.List<String> PLAYER_LIFECYCLE_KEYS = java.util.List.of(
        "MinecraftPlayerJoinMessage.Enabled",
        "MinecraftPlayerFirstJoinMessage.Enabled",
        "MinecraftPlayerLeaveMessage.Enabled"
    );
    private final AstralRecordLobbyPlugin plugin;
    private final LobbyApiClient api;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicLong minecraftSequence = new AtomicLong();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private String minecraftGenerationId;
    private volatile String gameChannelId;
    private volatile DiscordSRV subscribedDiscordSrv;
    private volatile boolean subscribed;
    private BukkitTask initializationTask;
    private BukkitTask minecraftPollTask;
    private boolean channelWarningLogged;
    private boolean initializationWarningLogged;
    private boolean minecraftRelayCompatibilityWarningLogged;

    DiscordNetworkBridge(AstralRecordLobbyPlugin plugin, LobbyApiClient api) {
        this.plugin = plugin;
        this.api = api;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)) return;
        initializationTask = Bukkit.getScheduler().runTaskTimer(
            plugin, this::tryInitialize, 1L, 20L);
        tryInitialize();
    }

    private void tryInitialize() {
        if (!Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            deactivateForDependencyRestart();
            return;
        }
        try {
            DiscordSRV activeDiscordSrv = DiscordSRV.getPlugin();
            String gameChannel = plugin.getConfig().getString("discord.gameChannel", "global");
            var destination = activeDiscordSrv.getDestinationTextChannelForGameChannelName(gameChannel);
            if (destination == null) {
                if (!channelWarningLogged) {
                    plugin.getLogger().warning("DiscordSRV game channel is not configured: " + gameChannel);
                    channelWarningLogged = true;
                }
                return;
            }
            lifecycleLock.writeLock().lock();
            try {
                if (subscribed && subscribedDiscordSrv == activeDiscordSrv) return;
                deactivateForDependencyRestartLocked();
                channelWarningLogged = false;
                gameChannelId = destination.getId();
                suppressStandardPlayerLifecycleMessages(activeDiscordSrv.config());
                DiscordSRV.api.subscribe(this);
                subscribed = true;
                subscribedDiscordSrv = activeDiscordSrv;
                initializationWarningLogged = false;
                long period = Math.max(5L, plugin.getConfig().getLong("discord.minecraftPollTicks", 10L));
                minecraftPollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::pollMinecraft, period, period);
            } finally {
                lifecycleLock.writeLock().unlock();
            }
        } catch (RuntimeException exception) {
            if (!initializationWarningLogged) {
                plugin.getLogger().warning("DiscordSRV bridge initialization failed: " + exception.getMessage());
                initializationWarningLogged = true;
            }
        }
    }

    void stop() {
        lifecycleLock.writeLock().lock();
        try {
            if (initializationTask != null) initializationTask.cancel();
            initializationTask = null;
            deactivateForDependencyRestartLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void deactivateForDependencyRestart() {
        lifecycleLock.writeLock().lock();
        try {
            deactivateForDependencyRestartLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /** DiscordSRV購読をlock内で解除し、古い停止処理が再購読を解除しないようにする。 */
    private void deactivateForDependencyRestartLocked() {
        boolean wasActive = subscribed || minecraftPollTask != null;
        if (subscribed) DiscordSRV.api.unsubscribe(this);
        subscribed = false;
        subscribedDiscordSrv = null;
        gameChannelId = null;
        if (wasActive) lifecycleGeneration.incrementAndGet();
        if (minecraftPollTask != null) minecraftPollTask.cancel();
        minecraftPollTask = null;
    }

    @Subscribe
    public void onDiscordChat(DiscordGuildMessagePreProcessEvent event) {
        if (event.isCancelled() || !subscribed || gameChannelId == null
            || !gameChannelId.equals(event.getChannel().getId())) return;
        if (event.getAuthor().isBot()) return;
        String author = event.getMember() == null
            ? event.getAuthor().getName() : event.getMember().getEffectiveName();
        String message = event.getMessage().getContentDisplay().trim();
        if (message.isEmpty()) return;
        long generation = lifecycleGeneration.get();
        lifecycleLock.readLock().lock();
        try {
            if (!isActive(generation)) return;
            event.setCancelled(true);
        } finally {
            lifecycleLock.readLock().unlock();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            lifecycleLock.readLock().lock();
            try {
                if (!isActive(generation)) return;
                try {
                    api.publishDiscordChat(plugin.serverId(), author, message);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Discord chat relay failed: " + exception.getMessage());
                }
            } finally {
                lifecycleLock.readLock().unlock();
            }
        });
    }

    /**
     * 独自のNetwork API経由の中継だけをDiscordへ送るため、
     * DiscordSRVのMinecraft→Discord標準経路を除外する。
     * LobbyのMinecraftチャットはLobbyListenerが常にキャンセルしてProxyへ送るため、
     * triggeringBukkitEventの状態に依存せず、このイベント自体を常にキャンセルする。
     *
     * @param event DiscordSRVのMinecraftチャット処理前イベント
     */
    @Subscribe(priority = ListenerPriority.HIGHEST)
    public void onGameChatMessagePreProcess(GameChatMessagePreProcessEvent event) {
        event.setCancelled(true);
    }

    private void pollMinecraft() {
        long generation = lifecycleGeneration.get();
        if (!isActive(generation)) return;
        if (!polling.compareAndSet(false, true)) return;
        try {
            String gameChannel = plugin.getConfig().getString("discord.gameChannel", "global");
            var destination = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(gameChannel);
            if (destination == null || !isActive(generation)) return;
            LobbyApiClient.ChatBatch batch = api.getMinecraftChat(minecraftSequence.get());
            lifecycleLock.readLock().lock();
            try {
                if (!isActive(generation)) return;
                MinecraftCursorTransition transition = resolveMinecraftCursorTransition(
                    minecraftGenerationId, minecraftSequence.get(), batch);
                if (!transition.relayEnabled()) {
                    if (!minecraftRelayCompatibilityWarningLogged) {
                        plugin.getLogger().warning(
                            "Minecraft chat Discord relay is waiting for an API response with latestSequence.");
                        minecraftRelayCompatibilityWarningLogged = true;
                    }
                    return;
                }
                minecraftRelayCompatibilityWarningLogged = false;
                minecraftGenerationId = transition.generationId();
                minecraftSequence.set(transition.sequence());
                if (transition.skipBatch()) {
                    return;
                }
                for (LobbyApiClient.ChatMessage message : batch.messages()) {
                    if (!isActive(generation)) return;
                    relayMinecraftMessage(destination, message);
                    if (!isActive(generation)) return;
                    minecraftSequence.set(message.sequence());
                }
            } finally {
                lifecycleLock.readLock().unlock();
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Minecraft chat Discord relay failed: " + exception.getMessage());
        } finally {
            polling.set(false);
        }
    }

    /**
     * Network APIから取得したMinecraftメッセージを種別に応じたDiscord表示で送信する。
     *
     * <p>接続通知はBotのEmbed、プレイヤー発言はMinecraftスキン付きWebhookを使用する。
     * Webhook権限またはプレイヤー識別情報がない場合はプレーンテキストへ退避する。</p>
     *
     * @param destination 送信先Discordテキストチャンネル
     * @param message Network APIのMinecraftメッセージ
     */
    private void relayMinecraftMessage(TextChannel destination, LobbyApiClient.ChatMessage message) {
        if ("lifecycle".equalsIgnoreCase(message.kind())) {
            if (!destination.getGuild().getSelfMember()
                .hasPermission(destination, Permission.MESSAGE_EMBED_LINKS)) {
                sendFallbackMessage(destination, fallbackSystemMessage(message));
                return;
            }
            MessageEmbed embed = new EmbedBuilder()
                .setColor(SYSTEM_EMBED_COLOR)
                .setAuthor("AstralRecord システム")
                .setDescription(message.message())
                .setFooter(message.sourceServerId())
                .build();
            destination.sendMessageEmbeds(embed).complete();
            return;
        }

        if (message.authorPlayerId() != null && message.authorMinecraftName() != null
            && destination.getGuild().getSelfMember().hasPermission(destination, Permission.MANAGE_WEBHOOKS)
            && WebhookUtil.getWebhookUrlToUseForChannel(destination) != null) {
            WebhookUtil.deliverMessage(
                destination,
                playerWebhookName(message),
                DiscordSRV.getAvatarUrl(message.authorMinecraftName(), message.authorPlayerId()),
                message.message(),
                (MessageEmbed) null,
                false);
            return;
        }

        sendFallbackMessage(destination, fallbackPlayerMessage(message));
    }

    /**
     * Discordのテキスト退避メッセージを同期送信する。
     *
     * @param destination 送信先Discordテキストチャンネル
     * @param content 送信本文
     * @throws IllegalStateException DiscordSRVが送信結果を返さなかった場合
     */
    private static void sendFallbackMessage(TextChannel destination, String content) {
        if (DiscordUtil.sendMessageBlocking(destination, content) == null) {
            throw new IllegalStateException("Discord fallback message delivery failed");
        }
    }

    /**
     * プレイヤー発言用Webhookの表示名をDiscord上限内で生成する。
     *
     * @param message Network APIのMinecraftメッセージ
     * @return アカウント表示と送信元backendを含むWebhook表示名
     */
    static String playerWebhookName(LobbyApiClient.ChatMessage message) {
        String name = message.authorName() + " • " + message.sourceServerId();
        return name.length() <= DISCORD_WEBHOOK_NAME_MAX_LENGTH
            ? name : name.substring(0, DISCORD_WEBHOOK_NAME_MAX_LENGTH);
    }

    /**
     * Webhookを利用できない場合のプレーンテキスト表示を生成する。
     *
     * @param message Network APIのMinecraftメッセージ
     * @return 発言者見出しと本文を改行で分離したDiscordメッセージ
     */
    static String fallbackPlayerMessage(LobbyApiClient.ChatMessage message) {
        return "**[" + message.sourceServerId() + "] " + message.authorName() + "**\n"
            + message.message();
    }

    /**
     * Embedを利用できない場合のシステム通知表示を生成する。
     *
     * @param message Network APIのlifecycleメッセージ
     * @return システム通知見出しと引用本文を含むDiscordメッセージ
     */
    static String fallbackSystemMessage(LobbyApiClient.ChatMessage message) {
        return "**🔔 AstralRecord システム • " + message.sourceServerId() + "**\n> "
            + message.message();
    }

    private boolean isActive(long generation) {
        return subscribed && lifecycleGeneration.get() == generation;
    }

    /**
     * Minecraft中継カーソルの世代遷移を決定する。
     * 初回接続ではAPIに残っている履歴を送らず、応答時点の最新シーケンスから中継を開始する。
     * API再起動後はgenerationIdの変更を検知して次回pollで新世代のメッセージを取得する。
     *
     * @param currentGenerationId 現在保持しているAPI世代ID。初回接続前はnull
     * @param currentSequence 現在保持している取得カーソル
     * @param batch APIから取得したメッセージバッチ
     * @return 次に保持するカーソルと、このバッチをDiscordへ送らないかの判定
     */
    static MinecraftCursorTransition resolveMinecraftCursorTransition(
        String currentGenerationId,
        long currentSequence,
        LobbyApiClient.ChatBatch batch
    ) {
        if (batch.latestSequence() == null) {
            return new MinecraftCursorTransition(currentGenerationId, currentSequence, true, false);
        }
        if (currentGenerationId == null) {
            return new MinecraftCursorTransition(batch.generationId(), batch.latestSequence(), true, true);
        }
        if (!batch.generationId().equals(currentGenerationId)) {
            return new MinecraftCursorTransition(batch.generationId(), 0L, true, true);
        }
        return new MinecraftCursorTransition(currentGenerationId, currentSequence, false, true);
    }

    record MinecraftCursorTransition(String generationId, long sequence, boolean skipBatch, boolean relayEnabled) {
    }

    /**
     * Proxyがネットワーク単位の通知を送るため、Lobby単位のDiscordSRV参加・退出通知を停止する。
     *
     * @param config DiscordSRVの動的設定
     */
    @SuppressWarnings("deprecation")
    static void suppressStandardPlayerLifecycleMessages(DynamicConfig config) {
        for (String key : PLAYER_LIFECYCLE_KEYS) {
            config.setRuntimeValue(key, false);
        }
    }
}
