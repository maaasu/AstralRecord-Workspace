package io.github.maaasu.astralrecordproxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Plugin(id = "astralrecordproxy", name = "AstralRecordProxy", version = "1.0.0")
public final class AstralRecordProxyPlugin {
    private static final MinecraftChannelIdentifier CHANNEL =
        MinecraftChannelIdentifier.from(BackendProtocol.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, PlayerMetadata> metadata = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGameConnectMillis = new ConcurrentHashMap<>();
    private final Set<UUID> pendingGameConnections = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> serverReservations = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, PlayerMetadata>> tabDisplayCache = new ConcurrentHashMap<>();
    private final AtomicBoolean discordPollRunning = new AtomicBoolean();
    private final AtomicLong discordSequence = new AtomicLong();
    private final AtomicReference<String> discordGenerationId = new AtomicReference<>();
    private ProxyConfig config;
    private NetworkApiClient api;

    @Inject
    public AstralRecordProxyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = ProxyConfig.load(dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load AstralRecordProxy config", exception);
        }
        api = new NetworkApiClient(config);
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getCommandManager().register(
            proxy.getCommandManager().metaBuilder("server").plugin(this).build(),
            new ServerMenuCommand());
        proxy.getScheduler().buildTask(this, this::refreshTabEntries)
            .repeat(Duration.ofSeconds(config.tabRefreshSeconds())).schedule();
        proxy.getScheduler().buildTask(this, this::refreshPresence)
            .repeat(Duration.ofSeconds(config.presenceHeartbeatSeconds())).schedule();
        proxy.getScheduler().buildTask(this, this::pollDiscordChat)
            .repeat(Duration.ofMillis(config.discordPollMillis())).schedule();
        logger.info("AstralRecordProxy enabled. lobby={}, gameServers={}", config.lobbyServer(), config.gameServers());
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        proxy.getServer(config.lobbyServer()).ifPresent(event::setInitialServer);
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (event.getPlayer().getCurrentServer().isPresent()) return;
        String target = event.getOriginalServer().getServerInfo().getName();
        if (!target.equalsIgnoreCase(config.lobbyServer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        String kickedServer = event.getServer().getServerInfo().getName();
        if (kickedServer.equalsIgnoreCase(config.lobbyServer())) {
            Component reason = event.getServerKickReason().orElse(
                Component.text("ロビーサーバーへ接続できません。", NamedTextColor.RED));
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
            return;
        }
        proxy.getServer(config.lobbyServer()).ifPresentOrElse(
            lobby -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                lobby, Component.text("ロビーへ移動しました。", NamedTextColor.YELLOW))),
            () -> event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                Component.text("ロビーサーバーへ接続できません。", NamedTextColor.RED))));
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverId = event.getServer().getServerInfo().getName();
        metadata.compute(player.getUniqueId(), (ignored, current) -> {
            if (current == null) {
                return lobbyMetadata(player, serverId);
            }
            return current.withServer(serverId, config.channelName(serverId));
        });
        if (config.isGameServer(serverId)) {
            lastGameConnectMillis.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null
            && config.isGameServer(event.getPreviousServer().getServerInfo().getName())) {
            lastGameConnectMillis.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
        refreshTabEntries();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        boolean disconnectedFromGame = event.getPlayer().getCurrentServer()
            .map(connection -> config.isGameServer(connection.getServerInfo().getName()))
            .orElseGet(() -> {
                PlayerMetadata current = metadata.get(playerId);
                return current != null && config.isGameServer(current.serverId());
            });
        if (disconnectedFromGame) {
            lastGameConnectMillis.put(playerId, System.currentTimeMillis());
        }
        metadata.remove(playerId);
        tabDisplayCache.remove(playerId);
        tabDisplayCache.values().forEach(cache -> cache.remove(playerId));
        api.removePlayer(playerId).exceptionally(failure -> {
            logger.warn("Failed to remove player presence for {}", playerId, failure);
            return null;
        });
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        try {
            BackendProtocol.Incoming incoming = BackendProtocol.decode(event.getData());
            if (incoming instanceof BackendProtocol.Connect connect) {
                requestConnection(connection.getPlayer(), connect.targetServer());
            } else if (incoming instanceof BackendProtocol.Metadata update) {
                if (!connection.getPlayer().getUniqueId().equals(update.playerId())) {
                    return;
                }
                String sourceServer = connection.getServerInfo().getName();
                metadata.put(update.playerId(), new PlayerMetadata(
                    update.playerId(), update.mcid(), sourceServer, update.channel(), update.displayName(),
                    update.level(), update.className(), update.afk()));
            } else if (incoming instanceof BackendProtocol.Chat chat) {
                if (!connection.getPlayer().getUniqueId().equals(chat.playerId())) {
                    return;
                }
                broadcastMinecraftChat(chat);
                api.publishMinecraftChat(chat, connection.getServerInfo().getName()).exceptionally(failure -> {
                    logger.warn("Failed to relay Minecraft chat to API", failure);
                    return null;
                });
            }
        } catch (RuntimeException | IOException exception) {
            logger.warn("Rejected malformed AstralRecord plugin message", exception);
        }
    }

    private void requestConnection(Player player, String targetServer) {
        RegisteredServer target = proxy.getServer(targetServer).orElse(null);
        if (target == null || (!config.isGameServer(targetServer)
            && !targetServer.equalsIgnoreCase(config.lobbyServer()))) {
            player.sendMessage(Component.text("接続先サーバーが見つかりません。", NamedTextColor.RED));
            return;
        }
        if (config.isGameServer(targetServer)) {
            boolean currentlyInGame = player.getCurrentServer()
                .map(connection -> config.isGameServer(connection.getServerInfo().getName()))
                .orElse(false);
            if (currentlyInGame) {
                player.sendMessage(Component.text("RPGサーバーから戻る場合は /lobby を使用してください。", NamedTextColor.YELLOW));
                return;
            }
            long remaining = cooldownRemaining(player.getUniqueId());
            if (remaining > 0L) {
                player.sendMessage(Component.text("再接続まで " + remaining + " 秒お待ちください。", NamedTextColor.YELLOW));
                return;
            }
            if (!pendingGameConnections.add(player.getUniqueId())) {
                player.sendMessage(Component.text("サーバーへ接続中です。", NamedTextColor.YELLOW));
                return;
            }
            int capacity = config.capacity(targetServer);
            if (capacity > 0 && !reserveServerSlot(targetServer, target, capacity)) {
                pendingGameConnections.remove(player.getUniqueId());
                player.sendMessage(Component.text("このチャンネルは満員です。", NamedTextColor.YELLOW));
                return;
            }
            connectToGameServer(player, target, targetServer, capacity > 0);
            return;
        }
        player.createConnectionRequest(target).connect().thenAccept(result -> {
            if (!result.isSuccessful()) {
                player.sendMessage(Component.text("サーバーへの接続に失敗しました。", NamedTextColor.RED));
            }
        });
    }

    private void connectToGameServer(
        Player player,
        RegisteredServer target,
        String targetServer,
        boolean reserved
    ) {
        player.createConnectionRequest(target).connect().whenComplete((result, failure) -> {
            if (reserved) releaseServerSlot(targetServer);
            pendingGameConnections.remove(player.getUniqueId());
            if (failure == null && result != null && result.isSuccessful()) {
                lastGameConnectMillis.put(player.getUniqueId(), System.currentTimeMillis());
                return;
            }
            player.sendMessage(Component.text("サーバーへの接続に失敗しました。", NamedTextColor.RED));
        });
    }

    private boolean reserveServerSlot(String serverId, RegisteredServer server, int capacity) {
        AtomicInteger reservations = serverReservations.computeIfAbsent(
            serverId.toLowerCase(Locale.ROOT), ignored -> new AtomicInteger());
        while (true) {
            int current = reservations.get();
            if (server.getPlayersConnected().size() + current >= capacity) return false;
            if (reservations.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseServerSlot(String serverId) {
        AtomicInteger reservations = serverReservations.get(serverId.toLowerCase(Locale.ROOT));
        if (reservations != null) reservations.updateAndGet(value -> Math.max(0, value - 1));
    }

    private long cooldownRemaining(UUID playerId) {
        Long last = lastGameConnectMillis.get(playerId);
        if (last == null) return 0L;
        long expiresAt = last + TimeUnit.SECONDS.toMillis(config.transferCooldownSeconds());
        long remaining = Math.max(0L, (expiresAt - System.currentTimeMillis() + 999L) / 1000L);
        if (remaining == 0L) lastGameConnectMillis.remove(playerId, last);
        return remaining;
    }

    private void sendOpenMenu(Player player) {
        player.getCurrentServer().ifPresent(connection -> {
            if (!connection.getServerInfo().getName().equalsIgnoreCase(config.lobbyServer())
                || !connection.sendPluginMessage(CHANNEL, BackendProtocol.openMenu())) {
                player.sendMessage(Component.text("サーバー選択画面を開けませんでした。", NamedTextColor.RED));
            }
        });
    }

    private void broadcastMinecraftChat(BackendProtocol.Chat chat) {
        Component message = Component.text("[" + chat.channel() + "] ", NamedTextColor.GRAY);
        if (!chat.className().isBlank()) {
            message = message.append(Component.text(
                "[Lv." + Math.max(1, chat.level()) + " " + chat.className() + "] ",
                NamedTextColor.AQUA));
        }
        message = message
            .append(Component.text(chat.displayName() + ": ", NamedTextColor.WHITE))
            .append(Component.text(chat.message(), NamedTextColor.WHITE));
        Component completedMessage = message;
        proxy.getAllPlayers().forEach(player -> player.sendMessage(completedMessage));
    }

    private void pollDiscordChat() {
        if (!discordPollRunning.compareAndSet(false, true)) return;
        api.getDiscordChat(discordSequence.get()).whenComplete((batch, failure) -> {
            discordPollRunning.set(false);
            if (failure != null) {
                logger.warn("Failed to poll Discord chat", failure);
                return;
            }
            String generation = discordGenerationId.get();
            if (!batch.generationId().equals(generation)) {
                discordGenerationId.set(batch.generationId());
                discordSequence.set(0L);
                return;
            }
            batch.messages().forEach(message -> {
                discordSequence.set(message.sequence());
                Component component = Component.text("[Discord] ", NamedTextColor.BLUE)
                    .append(Component.text(message.authorName() + ": ", NamedTextColor.WHITE))
                    .append(Component.text(message.message(), NamedTextColor.WHITE));
                proxy.getAllPlayers().forEach(player -> player.sendMessage(component));
            });
        });
    }

    private void refreshPresence() {
        long cooldownCutoff = System.currentTimeMillis()
            - TimeUnit.SECONDS.toMillis(config.transferCooldownSeconds());
        lastGameConnectMillis.entrySet().removeIf(entry -> entry.getValue() <= cooldownCutoff
            && !pendingGameConnections.contains(entry.getKey()));
        for (Player player : proxy.getAllPlayers()) {
            String serverId = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName()).orElse(config.lobbyServer());
            PlayerMetadata value = metadata.computeIfAbsent(
                player.getUniqueId(), ignored -> lobbyMetadata(player, serverId));
            if (!serverId.equalsIgnoreCase(value.serverId())) {
                value = value.withServer(serverId, config.channelName(serverId));
                metadata.put(player.getUniqueId(), value);
            }
            api.heartbeatPlayer(value).exceptionally(failure -> null);
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            String serverId = server.getServerInfo().getName();
            api.heartbeatServer(serverId, config.channelName(serverId), server.getPlayersConnected().size(), config.capacity(serverId))
                .exceptionally(failure -> null);
        }
    }

    private void refreshTabEntries() {
        Set<UUID> onlineIds = new HashSet<>();
        proxy.getAllPlayers().forEach(player -> onlineIds.add(player.getUniqueId()));
        for (Player viewer : proxy.getAllPlayers()) {
            TabList tabList = viewer.getTabList();
            Map<UUID, PlayerMetadata> cached =
                tabDisplayCache.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>());
            cached.keySet().removeIf(playerId -> !onlineIds.contains(playerId));
            for (Player target : proxy.getAllPlayers()) {
                PlayerMetadata value = metadata.getOrDefault(
                    target.getUniqueId(), lobbyMetadata(target, config.lobbyServer()));
                Component displayName = tabDisplayName(value);
                var entry = tabList.getEntry(target.getUniqueId());
                if (entry.isEmpty()) {
                    tabList.addEntry(TabListEntry.builder()
                        .tabList(tabList)
                        .profile(target.getGameProfile())
                        .displayName(displayName)
                        .latency((int) Math.min(Integer.MAX_VALUE, target.getPing()))
                        .gameMode(0)
                        .build());
                } else if (!value.equals(cached.get(target.getUniqueId()))) {
                    entry.get().setDisplayName(displayName);
                }
                cached.put(target.getUniqueId(), value);
            }
        }
    }

    private Component tabDisplayName(PlayerMetadata value) {
        Component prefix = Component.text("[" + value.channel() + "] ", NamedTextColor.GRAY);
        if (value.level() == null || value.className() == null) {
            return prefix.append(Component.text(value.mcid(), NamedTextColor.WHITE));
        }
        Component afk = value.afk() ? Component.text("[AFK] ", NamedTextColor.YELLOW) : Component.empty();
        return prefix
            .append(Component.text("[Lv." + value.level() + " " + value.className() + "] ", NamedTextColor.AQUA))
            .append(afk)
            .append(Component.text(value.displayName(), NamedTextColor.WHITE));
    }

    private PlayerMetadata lobbyMetadata(Player player, String serverId) {
        return new PlayerMetadata(
            player.getUniqueId(), player.getUsername(), serverId, config.channelName(serverId),
            player.getUsername(), null, null, false);
    }

    private final class ServerMenuCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("プレイヤー専用コマンドです。"));
                return;
            }
            String current = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName()).orElse("");
            if (current.equalsIgnoreCase(config.lobbyServer())) {
                sendOpenMenu(player);
                return;
            }
            player.sendMessage(Component.text("RPGサーバーから戻る場合は /lobby を使用してください。", NamedTextColor.YELLOW));
        }
    }
}
