package io.github.maaasu.astralrecordlobby;

import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AstralRecordLobbyPlugin extends JavaPlugin {
    private final Map<UUID, LobbyApiClient.Admission> admissions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> permissions = new ConcurrentHashMap<>();
    private final Set<UUID> administrators = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<AsyncPlayerPreLoginEvent>> pendingLogins = new ConcurrentHashMap<>();
    private final AtomicReference<Set<UUID>> editingPlayers = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<UUID>> safeToDisconnect = new AtomicReference<>(Set.of());
    private final Map<UUID, UUID> editAckIds = new ConcurrentHashMap<>();
    private final UUID runtimeSessionId = UUID.randomUUID();
    private final AtomicBoolean editDrainPollRunning = new AtomicBoolean();
    private final AtomicBoolean editDrainFailureLogged = new AtomicBoolean();
    private volatile boolean runtimeRegistered;
    private LobbyApiClient api;
    private ServerSelector selector;
    private DiscordNetworkBridge discordBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getBoolean("api.allowInsecureTls", false)) {
            getLogger().warning(
                "Network API TLS certificate and host name verification are disabled. Use only in development.");
        }
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
        getServer().getScheduler().runTaskTimer(this, this::publishServerMetrics, 20L, 100L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::pollEditDrains, 1L, 20L);
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

    static String editingDisconnectReason() {
        return "管理者によるプレイヤー情報の編集が行われているため参加できません。管理者にお問い合わせください。";
    }

    boolean isEditing(UUID playerId) { return editingPlayers.get().contains(playerId); }
    boolean canDisconnectForEdit(UUID playerId) { return safeToDisconnect.get().contains(playerId); }
    void beginLogin(AsyncPlayerPreLoginEvent event) {
        pendingLogins.compute(event.getUniqueId(), (ignored, attempts) -> {
            Set<AsyncPlayerPreLoginEvent> active = attempts == null ? ConcurrentHashMap.newKeySet() : attempts;
            active.add(event);
            return active;
        });
    }

    void finishLogin(AsyncPlayerPreLoginEvent event) {
        pendingLogins.computeIfPresent(event.getUniqueId(), (ignored, attempts) -> {
            attempts.remove(event);
            return attempts.isEmpty() ? null : attempts;
        });
    }

    void finishConnection(UUID playerId) {
        pendingLogins.computeIfPresent(playerId, (ignored, attempts) -> {
            var iterator = attempts.iterator();
            if (iterator.hasNext()) attempts.remove(iterator.next());
            return attempts.isEmpty() ? null : attempts;
        });
    }

    /** Polls the durable edit session list and disconnects Lobby players after RPG save acknowledgement. */
    private void pollEditDrains() {
        if (!editDrainPollRunning.compareAndSet(false, true)) return;
        try {
            String runtimeServerId = getConfig().getString("runtime.serverId", serverId());
            if (!runtimeRegistered) {
                api.registerRuntimeServer(runtimeServerId, runtimeSessionId);
                runtimeRegistered = true;
            }
            List<LobbyApiClient.EditDrain> drains = api.getEditDrains(runtimeServerId, runtimeSessionId);
            Set<UUID> active = new HashSet<>();
            Set<UUID> safe = new HashSet<>();
            for (LobbyApiClient.EditDrain drain : drains) {
                active.add(drain.userUuid());
                if (drain.safeToDisconnect()) safe.add(drain.userUuid());
            }
            editingPlayers.set(Set.copyOf(active));
            safeToDisconnect.set(Set.copyOf(safe));
            editAckIds.keySet().retainAll(drains.stream()
                .map(LobbyApiClient.EditDrain::editSessionId).collect(java.util.stream.Collectors.toSet()));
            editDrainFailureLogged.set(false);
            getServer().getScheduler().runTask(this, () -> {
                for (LobbyApiClient.EditDrain drain : drains) {
                    if (!drain.safeToDisconnect()) continue;
                    Player player = getServer().getPlayer(drain.userUuid());
                    if (player != null && player.isOnline()) {
                        player.kick(Component.text(editingDisconnectReason()));
                        continue;
                    }
                    if (!pendingLogins.containsKey(drain.userUuid())
                        && getServer().getPlayer(drain.userUuid()) == null) {
                        UUID ackId = editAckIds.computeIfAbsent(drain.editSessionId(), ignored -> UUID.randomUUID());
                        getServer().getScheduler().runTaskAsynchronously(this, () -> {
                            if (pendingLogins.containsKey(drain.userUuid())) return;
                            try {
                                api.acknowledgeEditDrain(runtimeServerId, runtimeSessionId, drain, ackId);
                            } catch (RuntimeException exception) {
                                getLogger().warning("Failed to acknowledge player edit drain " + drain.editSessionId()
                                    + ": " + exception.getMessage());
                            }
                        });
                    }
                }
            });
        } catch (RuntimeException exception) {
            runtimeRegistered = false;
            if (editDrainFailureLogged.compareAndSet(false, true)) {
                getLogger().warning("Player edit runtime is unavailable; retrying registration and drain polling: "
                    + exception.getMessage());
            }
        } finally {
            editDrainPollRunning.set(false);
        }
    }

    void cachePermission(UUID playerId, int permission) {
        permissions.put(playerId, permission);
        if (permission == 99) administrators.add(playerId); else administrators.remove(playerId);
    }

    /** 選択中アカウントを含むAPI admissionを保持し、権限とVIP期限を更新する。 */
    void cacheAdmission(UUID playerId, LobbyApiClient.Admission admission) {
        admissions.put(playerId, admission);
        cachePermission(playerId, admission.permission());
    }

    /** 期限切れVIPを通常枠へ戻す。取得失敗・未取得はVIP枠を与えない。 */
    boolean hasActiveVip(UUID playerId) {
        LobbyApiClient.Admission admission = admissions.get(playerId);
        return admission != null && admission.admitted() && admission.hasActiveVip();
    }

    void clearPermission(UUID playerId) {
        admissions.remove(playerId);
        permissions.remove(playerId);
        administrators.remove(playerId);
    }

    boolean isAdmin(Player player) {
        return administrators.contains(player.getUniqueId()) && permissions.getOrDefault(player.getUniqueId(), 0) == 99;
    }

    /**
     * API admissionで確認済みのユーザー権限を取得する。
     *
     * @param playerId プレイヤーUUID
     * @return キャッシュ済み権限。未取得の場合は一般権限0
     */
    int permissionOf(UUID playerId) {
        return permissions.getOrDefault(playerId, 0);
    }

    void applyLobbyPermission(Player player) {
        boolean admin = isAdmin(player);
        if (player.isOp() != admin) player.setOp(admin);
        if (!admin && player.getGameMode() != GameMode.ADVENTURE) player.setGameMode(GameMode.ADVENTURE);
        setAttribute(player, Attribute.ENTITY_INTERACTION_RANGE, admin ? 3.0 : 0.0);
        setAttribute(player, Attribute.BLOCK_INTERACTION_RANGE, admin ? 4.5 : 0.0);
    }

    /** オンラインプレイヤー1人を経由してLobbyの平均MSPTをProxyへ送る。 */
    private void publishServerMetrics() {
        getServer().getOnlinePlayers().stream().findFirst().ifPresent(player ->
            BackendProtocol.sendServerMetrics(this, player, getServer().getAverageTickTime()));
    }

    private void setAttribute(Player player, Attribute attribute, double value) {
        var instance = player.getAttribute(attribute);
        if (instance != null && Double.compare(instance.getBaseValue(), value) != 0) instance.setBaseValue(value);
    }
}
