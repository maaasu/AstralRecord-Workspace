package io.github.maaasu.astralrecordlobby;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import github.scarsz.discordsrv.util.DiscordUtil;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class DiscordNetworkBridge {
    private final AstralRecordLobbyPlugin plugin;
    private final LobbyApiClient api;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicLong minecraftSequence = new AtomicLong();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private String minecraftGenerationId;
    private volatile String gameChannelId;
    private volatile boolean subscribed;
    private BukkitTask initializationTask;
    private BukkitTask minecraftPollTask;
    private boolean channelWarningLogged;
    private boolean initializationWarningLogged;

    DiscordNetworkBridge(AstralRecordLobbyPlugin plugin, LobbyApiClient api) {
        this.plugin = plugin;
        this.api = api;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)
            || Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) return;
        initializationTask = Bukkit.getScheduler().runTaskTimer(
            plugin, this::tryInitialize, 1L, 20L);
        tryInitialize();
    }

    private void tryInitialize() {
        if (!Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            deactivateForDependencyRestart();
            return;
        }
        if (subscribed) return;
        try {
            String gameChannel = plugin.getConfig().getString("discord.gameChannel", "global");
            var destination = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(gameChannel);
            if (destination == null) {
                if (!channelWarningLogged) {
                    plugin.getLogger().warning("DiscordSRV game channel is not configured: " + gameChannel);
                    channelWarningLogged = true;
                }
                return;
            }
            lifecycleLock.writeLock().lock();
            try {
                if (subscribed) return;
                channelWarningLogged = false;
                gameChannelId = destination.getId();
                DiscordSRV.api.subscribe(this);
                subscribed = true;
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
        boolean wasSubscribed;
        lifecycleLock.writeLock().lock();
        try {
            wasSubscribed = subscribed;
            subscribed = false;
            gameChannelId = null;
            lifecycleGeneration.incrementAndGet();
            if (initializationTask != null) initializationTask.cancel();
            if (minecraftPollTask != null) minecraftPollTask.cancel();
            initializationTask = null;
            minecraftPollTask = null;
        } finally {
            lifecycleLock.writeLock().unlock();
        }
        if (wasSubscribed) DiscordSRV.api.unsubscribe(this);
    }

    private void deactivateForDependencyRestart() {
        boolean wasActive;
        lifecycleLock.writeLock().lock();
        try {
            wasActive = subscribed || minecraftPollTask != null;
            subscribed = false;
            gameChannelId = null;
            if (wasActive) lifecycleGeneration.incrementAndGet();
            if (minecraftPollTask != null) minecraftPollTask.cancel();
            minecraftPollTask = null;
        } finally {
            lifecycleLock.writeLock().unlock();
        }
        if (wasActive) DiscordSRV.api.unsubscribe(this);
    }

    @Subscribe
    public void onDiscordChat(DiscordGuildMessagePreProcessEvent event) {
        if (event.isCancelled() || !subscribed || gameChannelId == null
            || !gameChannelId.equals(event.getChannel().getId())) return;
        if (event.getAuthor().isBot()) return;
        var accountLinkManager = DiscordSRV.getPlugin().getAccountLinkManager();
        if (accountLinkManager == null || accountLinkManager.getUuid(event.getAuthor().getId()) == null) return;
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
                if (!batch.generationId().equals(minecraftGenerationId)) {
                    minecraftGenerationId = batch.generationId();
                    minecraftSequence.set(0L);
                    return;
                }
                for (LobbyApiClient.ChatMessage message : batch.messages()) {
                    if (!isActive(generation)) return;
                    DiscordUtil.sendMessage(destination,
                        "[" + message.sourceServerId() + "] " + message.authorName() + ": " + message.message());
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

    private boolean isActive(long generation) {
        return subscribed && lifecycleGeneration.get() == generation;
    }
}
