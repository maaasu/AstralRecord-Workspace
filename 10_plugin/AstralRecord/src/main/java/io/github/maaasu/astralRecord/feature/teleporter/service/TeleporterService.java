package io.github.maaasu.astralRecord.feature.teleporter.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.teleporter.gui.TeleporterGui;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneUnlockState;
import io.github.maaasu.astralRecord.feature.teleporter.repository.AccountWaystoneRepository;
import io.github.maaasu.astralRecord.feature.teleporter.repository.WaystoneDefinitionRepository;
import io.github.maaasu.astralRecord.feature.teleporter.view.WaystonePacketView;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * ウェイストーンのマスタ、解除状態、GUI、テレポート処理を統括します。
 */
public final class TeleporterService {
    private static final DateTimeFormatter ID_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final Plugin plugin;
    private final WaystoneDefinitionRepository definitionRepository;
    private final AccountWaystoneRepository accountWaystoneRepository;
    private final Map<String, WaystoneDefinition> definitionsById = new LinkedHashMap<>();
    private final Map<UUID, WaystoneUnlockState> unlockStatesByAccount = new LinkedHashMap<>();

    private @Nullable InventoryService inventoryService;
    private @Nullable WorldService worldService;
    private @Nullable WaystonePacketView packetView;
    private @Nullable TeleporterGui gui;
    private @Nullable io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterGuiEventHandler guiEventHandler;

    public TeleporterService(
            @NotNull Plugin plugin,
            @NotNull WaystoneDefinitionRepository definitionRepository,
            @NotNull AccountWaystoneRepository accountWaystoneRepository
    ) {
        this.plugin = plugin;
        this.definitionRepository = definitionRepository;
        this.accountWaystoneRepository = accountWaystoneRepository;
    }

    /**
     * 実行時依存サービスを設定します。
     */
    public void setRuntimeServices(
            @NotNull InventoryService inventoryService,
            @NotNull WorldService worldService,
            @NotNull WaystonePacketView packetView,
            @NotNull TeleporterGui gui,
            @NotNull io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterGuiEventHandler guiEventHandler
    ) {
        this.inventoryService = inventoryService;
        this.worldService = worldService;
        this.packetView = packetView;
        this.gui = gui;
        this.guiEventHandler = guiEventHandler;
    }

    /**
     * waystones.yml から全定義を読み込みます。
     *
     * @return 読み込んだ件数
     */
    public int loadAll() {
        definitionsById.clear();
        for (WaystoneDefinition definition : definitionRepository.loadAll()) {
            definitionsById.put(definition.id(), definition);
        }
        return definitionsById.size();
    }

    /**
     * waystones.yml を再読み込みして表示を再同期します。
     *
     * @return 読み込んだ件数
     */
    public int reload() {
        int count = loadAll();
        syncAllViews();
        return count;
    }

    /**
     * ウェイストーンを新規作成します。
     */
    @NotNull
    public WaystoneDefinition createWaystone(@NotNull AstPlayer astPlayer, @NotNull String name, boolean lockEnabled, long unlockGold) {
        Player player = astPlayer.getBukkit();
        Location location = player.getLocation();
        String id = generateId(name);
        WaystoneDefinition definition = new WaystoneDefinition(
                id,
                name.trim(),
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                location.getYaw(),
                location.getPitch(),
                lockEnabled,
                lockEnabled ? Math.max(0L, unlockGold) : 0L,
                Instant.now(),
                astPlayer.getUser().getUuid().toString()
        );
        definitionsById.put(definition.id(), definition);
        saveAll();
        syncAllViews();
        Logger.log(LogId.I_5950, definition.id(), definition.name(), definition.worldName());
        return definition;
    }

    /**
     * ウェイストーンを削除します。
     *
     * @param waystoneId 削除対象 ID
     * @return 削除できた場合 true
     */
    public boolean removeWaystone(@NotNull String waystoneId) {
        WaystoneDefinition removed = definitionsById.remove(waystoneId);
        if (removed == null) {
            return false;
        }
        saveAll();
        syncAllViews();
        return true;
    }

    /**
     * 登録済みウェイストーン一覧を返します。
     */
    @NotNull
    public Collection<WaystoneDefinition> getAll() {
        return List.copyOf(definitionsById.values());
    }

    /**
     * ID からウェイストーン定義を取得します。
     */
    @Nullable
    public WaystoneDefinition getById(@NotNull String waystoneId) {
        return definitionsById.get(waystoneId);
    }

    /**
     * 対象プレイヤーから見て解除済みかを返します。
     */
    public boolean isUnlocked(@NotNull AstPlayer astPlayer, @NotNull WaystoneDefinition definition) {
        if (!definition.lockEnabled()) {
            return true;
        }
        WaystoneUnlockState state = unlockStatesByAccount.get(astPlayer.getAccount().getUuid());
        return state != null && state.isUnlocked(definition);
    }

    /**
     * GUI に表示する同一ワールドのウェイストーン一覧を作ります。
     */
    @NotNull
    public List<TeleporterGui.Entry> listGuiEntries(@NotNull AstPlayer astPlayer, @NotNull WaystoneDefinition source) {
        List<TeleporterGui.Entry> entries = new ArrayList<>();
        for (WaystoneDefinition definition : definitionsById.values()) {
            if (definition.id().equals(source.id()) || !definition.worldName().equals(source.worldName())) {
                continue;
            }
            entries.add(new TeleporterGui.Entry(definition, isUnlocked(astPlayer, definition)));
        }
        entries.sort(Comparator.comparing(TeleporterGui.Entry::unlocked).reversed()
                .thenComparing(entry -> entry.definition().name(), String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /**
     * 解除状態を読み込み、表示を同期します。
     */
    @NotNull
    public CompletableFuture<WaystoneUnlockState> loadUnlockStateAsync(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        return CompletableFuture.supplyAsync(() -> {
            Set<String> ids = new LinkedHashSet<>(accountWaystoneRepository.loadUnlockedWaystoneIds(accountId));
            return new WaystoneUnlockState(accountId, Set.copyOf(ids));
        }).thenApply(state -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                unlockStatesByAccount.put(accountId, state);
                syncView(astPlayer.getBukkit());
            });
            return state;
        });
    }

    /**
     * ウェイストーンをクリックしたときの処理を実行します。
     */
    public void handleWaystoneClick(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull WaystoneDefinition definition, boolean rightClick) {
        if (definition.lockEnabled() && !isUnlockStateLoaded(astPlayer)) {
            loadUnlockStateAsync(astPlayer).whenComplete((ignored, throwable) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (throwable != null) {
                            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5957);
                            return;
                        }
                        handleWaystoneClick(player, astPlayer, definition, rightClick);
                    })
            );
            return;
        }
        if (isUnlocked(astPlayer, definition)) {
            openGui(player, astPlayer, definition, 0);
            return;
        }
        if (!rightClick) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5956, definition.name(), definition.unlockGold());
            return;
        }
        unlockWaystone(player, astPlayer, definition);
    }

    /**
     * 指定ウェイストーンを解除します。
     */
    public void unlockWaystone(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull WaystoneDefinition definition) {
        if (!definition.lockEnabled() || isUnlocked(astPlayer, definition)) {
            openGui(player, astPlayer, definition, 0);
            return;
        }
        InventoryService inventory = inventoryService;
        if (inventory == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5958);
            return;
        }
        long cost = Math.max(0L, definition.unlockGold());
        UUID accountId = astPlayer.getAccount().getUuid();
        if (!inventory.consumeGold(accountId, cost)) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5955, cost);
            return;
        }
        inventory.saveNow(accountId);
        CompletableFuture.runAsync(() -> accountWaystoneRepository.unlock(accountId, definition.id()))
                .whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        if (!inventory.addGold(astPlayer, cost)) {
                            Logger.log(LogId.E_5952, player.getName(), definition.id(), cost);
                        }
                        inventory.saveNow(accountId);
                        PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5957);
                        return;
                    }
                    WaystoneUnlockState current = unlockStatesByAccount.getOrDefault(accountId, new WaystoneUnlockState(accountId, Set.of()));
                    unlockStatesByAccount.put(accountId, current.withUnlocked(definition.id()));
                    PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5952, definition.name());
                    syncView(player);
                }));
    }

    /**
     * GUI 上の対象ウェイストーンへテレポートします。
     */
    public void teleportToWaystone(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull WaystoneDefinition source,
            @NotNull WaystoneDefinition target
    ) {
        WorldService world = worldService;
        if (world == null || !source.worldName().equals(target.worldName()) || !isUnlocked(astPlayer, target)) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5954);
            return;
        }
        Location location = target.toLocation();
        if (location == null || location.getWorld() == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5954);
            return;
        }
        player.closeInventory();
        world.teleportPlayerAsync(player, location, () -> PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5953, target.name()))
                .thenAccept(success -> Bukkit.getScheduler().runTaskLater(plugin, () -> syncView(player), 5L));
    }

    /**
     * GUI を開きます。
     */
    public void openGui(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull WaystoneDefinition source, int pageIndex) {
        io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterGuiEventHandler handler = guiEventHandler;
        if (handler == null) {
            return;
        }
        handler.open(player, astPlayer, source, pageIndex);
    }

    /**
     * 対象プレイヤーの packet 表示を同期します。
     */
    public void syncView(@NotNull Player player) {
        WaystonePacketView view = packetView;
        if (view != null && player.isOnline()) {
            view.syncForPlayer(player);
        }
    }

    /**
     * オンラインプレイヤー全員の packet 表示を同期します。
     */
    public void syncAllViews() {
        WaystonePacketView view = packetView;
        if (view != null) {
            view.syncAll();
        }
    }

    /**
     * プレイヤー別キャッシュと packet 表示を破棄します。
     */
    public void clearPlayer(@NotNull Player player) {
        AstPlayer astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(player);
        if (astPlayer != null) {
            unlockStatesByAccount.remove(astPlayer.getAccount().getUuid());
        }
        WaystonePacketView view = packetView;
        if (view != null) {
            view.clearPlayer(player);
        }
    }

    /**
     * サービス停止時に表示を破棄します。
     */
    public void stop() {
        WaystonePacketView view = packetView;
        if (view != null) {
            view.clearAll();
        }
        unlockStatesByAccount.clear();
    }

    private void saveAll() {
        definitionRepository.saveAll(definitionsById.values());
    }

    private boolean isUnlockStateLoaded(@NotNull AstPlayer astPlayer) {
        return unlockStatesByAccount.containsKey(astPlayer.getAccount().getUuid());
    }

    @NotNull
    private String generateId(@NotNull String name) {
        String normalized = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            normalized = "waystone";
        }
        String base = "ws-" + ID_TIME_FORMAT.format(Instant.now()) + "-" + normalized;
        String candidate = base.length() > 96 ? base.substring(0, 96) : base;
        int index = 2;
        while (definitionsById.containsKey(candidate)) {
            String suffix = "-" + index++;
            int maxLength = 100 - suffix.length();
            candidate = (base.length() > maxLength ? base.substring(0, maxLength) : base) + suffix;
        }
        return candidate;
    }
}
