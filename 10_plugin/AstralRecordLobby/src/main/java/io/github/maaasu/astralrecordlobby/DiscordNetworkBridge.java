package io.github.maaasu.astralrecordlobby;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import github.scarsz.discordsrv.util.DiscordUtil;
import org.bukkit.Bukkit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class DiscordNetworkBridge {
    private final AstralRecordLobbyPlugin plugin;
    private final LobbyApiClient api;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicLong minecraftSequence = new AtomicLong();
    private String minecraftGenerationId;
    private String gameChannelId;
    private boolean subscribed;

    DiscordNetworkBridge(AstralRecordLobbyPlugin plugin, LobbyApiClient api) {
        this.plugin = plugin;
        this.api = api;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)
            || Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) return;
        String gameChannel = plugin.getConfig().getString("discord.gameChannel", "global");
        var destination = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(gameChannel);
        if (destination == null) {
            plugin.getLogger().warning("DiscordSRV game channel is not configured: " + gameChannel);
            return;
        }
        gameChannelId = destination.getId();
        DiscordSRV.api.subscribe(this);
        subscribed = true;
        long period = Math.max(5L, plugin.getConfig().getLong("discord.minecraftPollTicks", 10L));
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollMinecraft, period, period);
    }

    void stop() {
        if (subscribed) DiscordSRV.api.unsubscribe(this);
        subscribed = false;
    }

    @Subscribe
    public void onDiscordChat(DiscordGuildMessagePreProcessEvent event) {
        if (event.isCancelled() || gameChannelId == null
            || !gameChannelId.equals(event.getChannel().getId())) return;
        event.setCancelled(true);
        if (event.getAuthor().isBot()) return;
        var accountLinkManager = DiscordSRV.getPlugin().getAccountLinkManager();
        if (accountLinkManager == null || accountLinkManager.getUuid(event.getAuthor().getId()) == null) return;
        String author = event.getMember() == null
            ? event.getAuthor().getName() : event.getMember().getEffectiveName();
        String message = event.getMessage().getContentDisplay().trim();
        if (message.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                api.publishDiscordChat(plugin.serverId(), author, message);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Discord chat relay failed: " + exception.getMessage());
            }
        });
    }

    private void pollMinecraft() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            String gameChannel = plugin.getConfig().getString("discord.gameChannel", "global");
            var destination = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(gameChannel);
            if (destination == null) return;
            LobbyApiClient.ChatBatch batch = api.getMinecraftChat(minecraftSequence.get());
            if (!batch.generationId().equals(minecraftGenerationId)) {
                minecraftGenerationId = batch.generationId();
                minecraftSequence.set(0L);
                return;
            }
            for (LobbyApiClient.ChatMessage message : batch.messages()) {
                minecraftSequence.set(message.sequence());
                DiscordUtil.sendMessage(destination,
                    "[" + message.sourceServerId() + "] " + message.authorName() + ": " + message.message());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Minecraft chat Discord relay failed: " + exception.getMessage());
        } finally {
            polling.set(false);
        }
    }
}
