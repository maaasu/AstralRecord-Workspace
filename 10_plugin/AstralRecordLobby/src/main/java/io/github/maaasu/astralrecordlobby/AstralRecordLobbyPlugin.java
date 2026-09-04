package io.github.maaasu.astralrecordlobby;

import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AstralRecordLobbyPlugin extends JavaPlugin {
    private final Map<UUID, Integer> permissions = new ConcurrentHashMap<>();
    private final Set<UUID> administrators = ConcurrentHashMap.newKeySet();
    private LobbyApiClient api;
    private ServerSelector selector;
    private DiscordNetworkBridge discordBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        api = new LobbyApiClient(getConfig());
        selector = new ServerSelector(this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BackendProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, BackendProtocol.CHANNEL,
            (channel, player, message) -> {
                if (BackendProtocol.isOpenMenu(message)) selector.open(player);
            });
        getServer().getPluginManager().registerEvents(new LobbyListener(this, selector), this);
        getServer().getWorlds().forEach(world -> world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true));
        selector.spawnNpc();
        discordBridge = new DiscordNetworkBridge(this, api);
        discordBridge.start();
        getServer().getScheduler().runTaskTimer(this, () ->
            getServer().getOnlinePlayers().forEach(this::applyLobbyPermission), 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (discordBridge != null) discordBridge.stop();
        if (selector != null) selector.removeNpc();
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("プレイヤー専用コマンドです。");
            return true;
        }
        selector.open(player);
        return true;
    }

    LobbyApiClient api() { return api; }
    String serverId() { return getConfig().getString("serverId", "lobby"); }
    String channelName() { return getConfig().getString("channelName", "ロビー"); }

    void cachePermission(UUID playerId, int permission) {
        permissions.put(playerId, permission);
        if (permission == 99) administrators.add(playerId); else administrators.remove(playerId);
    }

    void clearPermission(UUID playerId) {
        permissions.remove(playerId);
        administrators.remove(playerId);
    }

    boolean isAdmin(Player player) {
        return administrators.contains(player.getUniqueId()) && permissions.getOrDefault(player.getUniqueId(), 0) == 99;
    }

    void applyLobbyPermission(Player player) {
        boolean admin = isAdmin(player);
        if (player.isOp() != admin) player.setOp(admin);
        if (!admin && player.getGameMode() != GameMode.ADVENTURE) player.setGameMode(GameMode.ADVENTURE);
        setAttribute(player, Attribute.ENTITY_INTERACTION_RANGE, admin ? 3.0 : 0.0);
        setAttribute(player, Attribute.BLOCK_INTERACTION_RANGE, admin ? 4.5 : 0.0);
    }

    private void setAttribute(Player player, Attribute attribute, double value) {
        var instance = player.getAttribute(attribute);
        if (instance != null && Double.compare(instance.getBaseValue(), value) != 0) instance.setBaseValue(value);
    }
}
