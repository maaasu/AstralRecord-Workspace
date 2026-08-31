package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.gui.OrbGuiHolder;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentRune;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResult;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResultType;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 通常インベントリ上のオーブクリックから装備選択・実行・結果反映までを管理します。
 */
public final class OrbService {

    private static final int CONTENT_SLOT_COUNT = 45;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int INVENTORY_ORB_CONTENT_SLOT_COUNT = 28;
    private static final int INVENTORY_ORB_PREVIOUS_PAGE_SLOT = 45;
    private static final int INVENTORY_ORB_INFO_SLOT = 49;
    private static final int INVENTORY_ORB_NEXT_PAGE_SLOT = 53;
    private static final int CONFIRM_TARGET_SLOT = 11;
    private static final int CONFIRM_MATERIAL_LIST_SLOT = 13;
    private static final int CONFIRM_GOLD_SLOT = 4;
    private static final int CONFIRM_EXECUTE_SLOT = 15;
    private static final int CONFIRM_BACK_SLOT = 22;
    private static final int RUNE_TARGET_SLOT = 10;
    private static final int RUNE_SELECTION_SLOT = 13;
    private static final int RUNE_RESULT_SLOT = 16;
    private static final int RUNE_RETURN_SLOT = 25;
    private static final int RUNE_DETACH_SELECT_BACK_SLOT = 22;
    private static final int MATERIAL_LIST_PREVIOUS_PAGE_SLOT = 45;
    private static final int MATERIAL_LIST_PAGE_INFO_SLOT = 46;
    private static final int MATERIAL_LIST_GOLD_SLOT = 47;
    private static final int MATERIAL_LIST_BACK_SLOT = 49;
    private static final int MATERIAL_LIST_NEXT_PAGE_SLOT = 53;
    private static final Material PROCESSING_ICON = Material.CLOCK;
    private static final long OPERATION_RETRY_INITIAL_MILLIS = 250L;
    private static final long OPERATION_RETRY_MAX_MILLIS = 2_000L;

    private final Plugin plugin;
    private final InventoryService inventoryService;
    private final InventorySaveCoordinator inventorySaveCoordinator;
    private final PlayerInventoryStateRegistry inventoryStateRegistry;
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;
    private final OrbInventoryOpener inventoryOpener;
    private final OrbRetryWaiter retryWaiter;
    private final Map<UUID, OrbSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, OrbInventoryListSession> inventoryOrbListSessions = new ConcurrentHashMap<>();
    private @Nullable StatusService statusService;
    private @NotNull BiConsumer<AstPlayer, String> useSuccessListener = (player, orbItemId) -> { };

    /**
     * オーブ GUI サービスを初期化します。
     *
     * @param plugin Bukkit task の所有プラグイン
     * @param inventoryService 所持品の正本参照・差分消費サービス
     * @param inventorySaveCoordinator ログアウト保存との直列化サービス
     * @param inventoryStateRegistry ログイン世代 state の検証レジストリ
     * @param itemService 装備・共通マスタ参照と更新サービス
     * @param itemStackFactory 装備表示生成サービス
     */
    public OrbService(
        @NotNull Plugin plugin,
        @NotNull InventoryService inventoryService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator,
        @NotNull PlayerInventoryStateRegistry inventoryStateRegistry,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory
    ) {
        this(
            plugin,
            inventoryService,
            inventorySaveCoordinator,
            inventoryStateRegistry,
            itemService,
            itemStackFactory,
            GuiOpenSupport::open,
            OrbService::sleepForOperationRetry
        );
    }

    OrbService(
        @NotNull Plugin plugin,
        @NotNull InventoryService inventoryService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator,
        @NotNull PlayerInventoryStateRegistry inventoryStateRegistry,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull OrbInventoryOpener inventoryOpener
    ) {
        this(
            plugin,
            inventoryService,
            inventorySaveCoordinator,
            inventoryStateRegistry,
            itemService,
            itemStackFactory,
            inventoryOpener,
            OrbService::sleepForOperationRetry
        );
    }

    OrbService(
        @NotNull Plugin plugin,
        @NotNull InventoryService inventoryService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator,
        @NotNull PlayerInventoryStateRegistry inventoryStateRegistry,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull OrbInventoryOpener inventoryOpener,
        @NotNull OrbRetryWaiter retryWaiter
    ) {
        this.plugin = plugin;
        this.inventoryService = inventoryService;
        this.inventorySaveCoordinator = inventorySaveCoordinator;
        this.inventoryStateRegistry = inventoryStateRegistry;
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
        this.inventoryOpener = inventoryOpener;
        this.retryWaiter = retryWaiter;
    }

    /**
     * 装備更新後に装備中ステータスを再計算するサービスを接続します。
     *
     * @param statusService ステータス再計算サービス。未接続を許可する場合は {@code null}
     */
    public void setStatusService(@Nullable StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * オーブによる装備更新が確定した後の通知先を設定します。
     *
     * @param listener 使用プレイヤーと消費したオーブ item ID を受け取る通知先
     */
    public void setUseSuccessListener(@NotNull BiConsumer<AstPlayer, String> listener) {
        this.useSuccessListener = listener;
    }

    /**
     * 対象インベントリがオーブ専用 GUI か判定します。
     *
     * @param inventory 判定対象
     * @return オーブ GUI の場合 {@code true}
     */
    public boolean isOrbInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof OrbGuiHolder;
    }

    private boolean isInventoryOrbList(@Nullable Inventory inventory) {
        return inventory != null
            && inventory.getHolder() instanceof OrbGuiHolder holder
            && holder.screen() == OrbGuiHolder.Screen.INVENTORY_ORB_LIST;
    }

    /**
     * プレイヤーがオーブ装備更新中か判定します。
     *
     * @param player 判定対象プレイヤー
     * @return 同一プレイヤーのセッションがAPI操作・正本照合中なら {@code true}
     */
    public boolean isLocked(@NotNull Player player) {
        OrbSession session = sessions.get(player.getUniqueId());
        return session != null && session.interactionLock.isLocked() && !session.detached;
    }

    /**
     * 通常プレイヤーインベントリの情報アイコンから、所持オーブ一覧 GUI を開きます。
     *
     * @param event 通常プレイヤーインベントリのクリックイベント
     * @return 情報アイコンのクリックとして処理した場合 {@code true}
     */
    public boolean handleInventoryInfoClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getView().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING
            || !(event.getClickedInventory() instanceof PlayerInventory)
            || !inventoryService.isInventoryInfoSlot(event.getSlot())) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            return false;
        }

        event.setCancelled(true);
        openInventoryOrbList(player, astPlayer, null);
        return true;
    }

    /**
     * 通常プレイヤーインベントリのクリックをオーブ起動として処理します。
     * 表示 ItemStack ではなく BAG/HOTBAR の正本entryからオーブ種別を解決します。
     *
     * @param event 通常インベントリのクリックイベント
     * @return オーブ起動としてイベントを消費した場合 {@code true}
     */
    public boolean handleInventoryOrbClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getView().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING
            || !(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            return false;
        }
        InventoryEntryModel entry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, event.getSlot());
        ItemModel orbModel = resolveOrbModel(entry);
        if (entry == null || orbModel == null) {
            return false;
        }

        event.setCancelled(true);
        startOrbOperation(player, astPlayer, orbModel, false);
        return true;
    }

    /**
     * 指定された所持オーブを起点に、既存の装備候補 GUI を開くセッションを開始します。
     *
     * @param player 操作プレイヤー
     * @param astPlayer ログイン中のプレイヤー状態
     * @param orbModel 起点オーブのマスタ
     * @param returnToInventoryOrbListOnFailure 対象装備がない場合に所持オーブ一覧へ戻すか
     */
    private void startOrbOperation(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull ItemModel orbModel,
        boolean returnToInventoryOrbListOnFailure
    ) {
        OrbSession previous = sessions.get(player.getUniqueId());
        if (previous != null
            && (previous.operationFuture != null || previous.preloadFuture != null)) {
            GuiSound.DENY.play(player);
            return;
        }
        InventoryEntryModel consumable = inventoryService.findOwnedNormalItemEntryForConsumption(
            astPlayer.getAccount().getUuid(),
            orbModel.getId()
        );
        ItemModel consumableModel = resolveOrbModel(consumable);
        if (consumableModel == null || !consumableModel.getId().equalsIgnoreCase(orbModel.getId())) {
            return;
        }
        removeSession(player.getUniqueId());
        inventoryOrbListSessions.remove(player.getUniqueId());
        OrbSession session = new OrbSession(
            player,
            astPlayer,
            astPlayer.getAccount().getUuid(),
            UUID.randomUUID(),
            consumable.getInventoryEntryId(),
            orbModel.getId(),
            returnToInventoryOrbListOnFailure
        );
        sessions.put(player.getUniqueId(), session);
        preloadAndOpenList(session, orbModel);
    }

    /**
     * Bukkit state から候補 ID だけを同期取得し、装備個体の API 取得は非同期で完了させてから一覧を開きます。
     * reload 時に温め済みの個体も同じ経路を通るため、候補描画・クリック再検証は常に cache-only です。
     */
    private void preloadAndOpenList(@NotNull OrbSession session, @NotNull ItemModel orbModel) {
        Set<String> instanceIds = candidateInstanceIds(session);
        session.interactionLock.beginMutation();
        CompletableFuture<ItemService.EquipmentPreloadResult> future = AsyncTaskUtil.supplyAsync(
            plugin,
            () -> itemService.preloadEquipmentInstances(instanceIds)
        );
        session.preloadFuture = future;
        future.whenComplete((result, throwable) -> AsyncTaskUtil.runSync(plugin, () -> {
            if (sessions.get(session.player.getUniqueId()) != session
                || session.detached
                || inventoryStateRegistry.get(session.accountId) == null
                || AstPlayerCache.get(session.player) != session.astPlayer) {
                return;
            }
            session.preloadFuture = null;
            session.interactionLock.release();
            if (throwable != null || result == ItemService.EquipmentPreloadResult.UNAVAILABLE) {
                restoreInventoryOrbListOrRemoveSession(session);
                PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5295);
                GuiSound.DENY.play(session.player);
                return;
            }
            ItemModel currentOrb = resolveCurrentOrb(session);
            if (currentOrb == null || !currentOrb.getId().equalsIgnoreCase(orbModel.getId())) {
                restoreInventoryOrbListOrRemoveSession(session);
                PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5289);
                GuiSound.DENY.play(session.player);
                return;
            }
            openList(session, currentOrb);
        }));
    }

    /** 候補となり得る装備個体 ID をメモリ上のロードアウトと通常 inventory から固定します。 */
    private @NotNull Set<String> candidateInstanceIds(@NotNull OrbSession session) {
        Set<String> instanceIds = new LinkedHashSet<>();
        for (ItemReference reference : inventoryService.getEquippedItemReferences(session.astPlayer)) {
            if (reference.hasEquipmentInstanceId()) {
                instanceIds.add(reference.equipmentInstanceId());
            }
        }
        for (InventoryEntryModel entry : ownedEntries(session.accountId)) {
            if (entry.getInstanceId() != null
                && ItemCategory.fromApiValue(entry.getItemCategory()) == ItemCategory.EQUIPMENT) {
                instanceIds.add(entry.getInstanceId().toString());
            }
        }
        return Set.copyOf(instanceIds);
    }

    /**
     * オーブ GUI の上段操作を処理し、下段のプレイヤーインベントリ操作は共通処理へ委譲します。
     *
     * @param event オーブ GUI 上のクリックイベント
     */
    public void handleGuiClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() instanceof PlayerInventory) {
            OrbSession session = currentSession(player, event.getView().getTopInventory());
            if (session != null) {
                if (session.interactionLock.isLocked() || session.transitioning) {
                    event.setCancelled(true);
                    GuiSound.DENY.play(player);
                    return;
                }
                if (HotbarShortcutClickSupport.handleInventoryControlClick(event, player, inventoryService)) {
                    return;
                }
                if (session.screen == OrbGuiHolder.Screen.RUNE_ATTACH) {
                    if (event.getSlot() >= 0 && event.getSlot() <= 8
                        && HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
                        return;
                    }
                    event.setCancelled(true);
                    handleRuneGuiClick(event, session);
                    return;
                }
            }
            if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
                return;
            }
            event.setCancelled(true);
            GuiSound.DENY.play(player);
            return;
        }
        event.setCancelled(true);
        if (isInventoryOrbList(event.getView().getTopInventory())) {
            handleInventoryOrbListClick(event);
            return;
        }
        OrbSession session = currentSession(player, event.getView().getTopInventory());
        if (session == null || session.interactionLock.isLocked() || session.transitioning) {
            GuiSound.DENY.play(player);
            return;
        }
        handleUnlockedGuiClick(event, session);
    }

    /**
     * オーブ GUI へのドラッグを常に拒否し、更新中を含む複数スロット変更を防ぎます。
     *
     * @param event ドラッグイベント
     */
    public void handleGuiDrag(@NotNull InventoryDragEvent event) {
        if (isOrbInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    /**
     * 装備更新中のホットバー選択変更を拒否します。
     *
     * @param event ホットバー選択変更イベント
     * @return ロック中として拒否した場合 {@code true}
     */
    public boolean handleHeldChange(@NotNull PlayerItemHeldEvent event) {
        if (!isLocked(event.getPlayer())) {
            return false;
        }
        event.setCancelled(true);
        return true;
    }

    /**
     * GUI を閉じたプレイヤーを処理します。装備更新中は同じ GUI を次 tick に再表示して
     * クリックロックを更新完了まで維持します。
     *
     * @param player GUI を閉じたプレイヤー
     */
    public void handleClose(@NotNull Player player) {
        handleClose(player, null);
    }

    /**
     * 指定された GUI のクローズを、画面切替中の古いクローズイベントと区別して処理します。
     *
     * @param player GUI を閉じたプレイヤー
     * @param closedInventory 閉じられたトップインベントリ。旧 API 互換の呼び出しでは {@code null}
     */
    public void handleClose(@NotNull Player player, @Nullable Inventory closedInventory) {
        if (closedInventory != null && isInventoryOrbList(closedInventory)) {
            OrbInventoryListSession currentList = currentInventoryOrbListSession(player, closedInventory);
            if (currentList != null) {
                inventoryOrbListSessions.remove(player.getUniqueId(), currentList);
            }
            return;
        }
        if (closedInventory != null && inventoryOrbListSessions.containsKey(player.getUniqueId())) {
            return;
        }
        if (inventoryOrbListSessions.remove(player.getUniqueId()) != null) {
            return;
        }
        OrbSession session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player || session.transitioning) {
            return;
        }
        if (session.interactionLock.isLocked() && !session.detached && player.isOnline()) {
            scheduleLockedReopen(session);
            return;
        }
        session.uiClosed = true;
        session.interactionLock.close();
        if (session.operationFuture == null) {
            sessions.remove(player.getUniqueId(), session);
        }
    }

    /** 操作中の Escape close 後に同じ token・inventory を再表示します。 */
    private void scheduleLockedReopen(@NotNull OrbSession session) {
        if (session.reopenTask != null) {
            return;
        }
        session.reopening = true;
        session.reopenTask = plugin.getServer().getScheduler().runTask(plugin, () -> {
            session.reopenTask = null;
            if (session.detached
                || sessions.get(session.player.getUniqueId()) != session
                || !session.player.isOnline()) {
                session.reopening = false;
                return;
            }
            if (!session.interactionLock.isLocked()) {
                session.reopening = false;
                sessions.remove(session.player.getUniqueId(), session);
                return;
            }
            session.player.openInventory(session.inventory);
            session.reopening = false;
            if (!isCurrentInventory(session.player, session)) {
                sessions.remove(session.player.getUniqueId(), session);
                session.interactionLock.close();
            }
        });
    }

    /**
     * ログアウト保存前に対象セッションを切り離し、通信中なら保存キュー上で結果を確定します。
     *
     * @param player ログアウトするプレイヤー
     */
    public void prepareForPlayerSave(@NotNull Player player) {
        inventoryOrbListSessions.remove(player.getUniqueId());
        OrbSession session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player || !sessions.remove(player.getUniqueId(), session)) {
            return;
        }
        detachForSave(session);
    }

    /**
     * プラグイン停止前に全オーブセッションを停止し、未確定通信を保存キューへ登録します。
     */
    public void prepareAllForShutdown() {
        inventoryOrbListSessions.clear();
        for (OrbSession session : List.copyOf(sessions.values())) {
            if (sessions.remove(session.player.getUniqueId(), session)) {
                detachForSave(session);
            }
        }
    }

    /**
     * 適格装備を収集してオーブ対象一覧を開きます。
     *
     * @param session 操作セッション
     * @param orbModel 使用オーブのマスタ
     */
    private void openList(@NotNull OrbSession session, @NotNull ItemModel orbModel) {
        List<OrbCandidate> candidates = collectCandidates(session, orbModel);
        if (candidates.isEmpty()) {
            restoreInventoryOrbListOrRemoveSession(session);
            PlayerMessageService.getInstance().send(
                session.player,
                orbModel.getOrb().getEffect().getType() == ItemOrbEffectType.ENCHANT
                    ? PlayerMsgId.P_5294
                    : PlayerMsgId.P_5288
            );
            GuiSound.DENY.play(session.player);
            return;
        }
        Inventory inventory = Bukkit.createInventory(
            new OrbGuiHolder(session.player.getUniqueId(), session.token, OrbGuiHolder.Screen.LIST),
            OrbGuiHolder.sizeFor(OrbGuiHolder.Screen.LIST),
            Component.text("オーブ対象装備", NamedTextColor.DARK_PURPLE)
        );
        session.screen = OrbGuiHolder.Screen.LIST;
        session.inventory = inventory;
        renderList(session, orbModel, inventory, candidates);
        inventoryOpener.open(session.player, inventory, () -> GuiSound.OPEN.play(session.player), () ->
            sessions.remove(session.player.getUniqueId(), session));
    }

    private void restoreInventoryOrbListOrRemoveSession(@NotNull OrbSession session) {
        if (session.returnToInventoryOrbListOnFailure) {
            openInventoryOrbList(session.player, session.astPlayer, session);
            return;
        }
        sessions.remove(session.player.getUniqueId(), session);
    }

    /**
     * インベントリ情報アイコンから所持オーブの一覧 GUI を開きます。
     *
     * @param player 操作プレイヤー
     * @param astPlayer ログイン中のプレイヤー状態
     * @param previousOperation 切替元のオーブ操作セッション。通常インベントリ起点では {@code null}
     *                          であり、通常起点は {@link GuiSound#OPEN}、操作中の画面遷移は
     *                          {@link GuiSound#SELECT} を再生します。
     */
    private void openInventoryOrbList(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @Nullable OrbSession previousOperation
    ) {
        OrbSession currentOperation = sessions.get(player.getUniqueId());
        if (previousOperation != null && currentOperation != previousOperation) {
            GuiSound.DENY.play(player);
            return;
        }
        if (currentOperation != null
            && (currentOperation.interactionLock.isLocked()
                || currentOperation.operationFuture != null
                || currentOperation.preloadFuture != null)) {
            GuiSound.DENY.play(player);
            return;
        }

        if (currentOperation != null) {
            removeSession(player.getUniqueId());
        }
        inventoryOrbListSessions.remove(player.getUniqueId());

        UUID token = UUID.randomUUID();
        Inventory inventory = Bukkit.createInventory(
            new OrbGuiHolder(player.getUniqueId(), token, OrbGuiHolder.Screen.INVENTORY_ORB_LIST),
            OrbGuiHolder.sizeFor(OrbGuiHolder.Screen.INVENTORY_ORB_LIST),
            Component.text("インベントリ内のオーブ", NamedTextColor.DARK_PURPLE)
        );
        OrbInventoryListSession listSession = new OrbInventoryListSession(
            astPlayer,
            astPlayer.getAccount().getUuid(),
            token,
            inventory
        );
        inventoryOrbListSessions.put(player.getUniqueId(), listSession);
        renderInventoryOrbList(listSession, collectInventoryOrbs(listSession.accountId));
        GuiSound openSound = previousOperation == null ? GuiSound.OPEN : GuiSound.SELECT;
        inventoryOpener.open(player, inventory, () -> openSound.play(player), () ->
            inventoryOrbListSessions.remove(player.getUniqueId(), listSession));
    }

    /**
     * 所持オーブ一覧 GUI のクリックをページ移動またはオーブ操作開始へ振り分けます。
     *
     * @param event オーブ一覧 GUI のクリックイベント
     */
    private void handleInventoryOrbListClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        OrbInventoryListSession session = currentInventoryOrbListSession(
            player, event.getView().getTopInventory());
        if (session == null
            || event.getRawSlot() < 0
            || event.getRawSlot() >= event.getView().getTopInventory().getSize()
            || event.getClick() != ClickType.LEFT) {
            GuiSound.DENY.play(player);
            return;
        }

        List<OrbInventoryEntry> entries = collectInventoryOrbs(session.accountId);
        int pageCount = inventoryOrbPageCount(entries.size());
        if (event.getRawSlot() == INVENTORY_ORB_PREVIOUS_PAGE_SLOT) {
            if (session.page > 0) {
                session.page--;
                renderInventoryOrbList(session, entries);
                GuiSound.PAGE.play(player);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (event.getRawSlot() == INVENTORY_ORB_NEXT_PAGE_SLOT) {
            if (session.page + 1 < pageCount) {
                session.page++;
                renderInventoryOrbList(session, entries);
                GuiSound.PAGE.play(player);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (event.getRawSlot() == INVENTORY_ORB_INFO_SLOT) {
            GuiSound.DENY.play(player);
            return;
        }

        String itemId = session.displayedOrbItemIds.get(event.getRawSlot());
        if (itemId == null) {
            GuiSound.DENY.play(player);
            return;
        }
        InventoryEntryModel entry = findOwnedOrbEntry(session.accountId, itemId);
        ItemModel orbModel = resolveOrbModel(entry);
        if (entry == null || orbModel == null) {
            renderInventoryOrbList(session, collectInventoryOrbs(session.accountId));
            GuiSound.DENY.play(player);
            return;
        }

        inventoryOrbListSessions.remove(player.getUniqueId(), session);
        startOrbOperation(player, session.astPlayer, orbModel, true);
    }

    /**
     * 所持オーブ一覧の現在ページを描画します。
     *
     * @param session 一覧 GUI セッション
     * @param entries 集約済みオーブ一覧
     */
    private void renderInventoryOrbList(
        @NotNull OrbInventoryListSession session,
        @NotNull List<OrbInventoryEntry> entries
    ) {
        fillInventoryOrbList(session.inventory);
        int pageCount = inventoryOrbPageCount(entries.size());
        session.page = Math.max(0, Math.min(session.page, pageCount - 1));
        int fromIndex = session.page * INVENTORY_ORB_CONTENT_SLOT_COUNT;
        int toIndex = Math.min(entries.size(), fromIndex + INVENTORY_ORB_CONTENT_SLOT_COUNT);
        Map<Integer, String> displayed = new LinkedHashMap<>();
        for (int index = fromIndex; index < toIndex; index++) {
            int logicalSlot = index - fromIndex;
            int slot = (logicalSlot / 7 + 1) * 9 + logicalSlot % 7 + 1;
            OrbInventoryEntry entry = entries.get(index);
            session.inventory.setItem(slot, createInventoryOrbItem(entry));
            displayed.put(slot, entry.itemId());
        }
        session.displayedOrbItemIds = Map.copyOf(displayed);
        session.inventory.setItem(
            INVENTORY_ORB_PREVIOUS_PAGE_SLOT,
            pageButton(false, session.page > 0)
        );
        session.inventory.setItem(
            INVENTORY_ORB_INFO_SLOT,
            createInventoryOrbListInfo(session.page, pageCount, entries.size())
        );
        session.inventory.setItem(
            INVENTORY_ORB_NEXT_PAGE_SLOT,
            pageButton(true, session.page + 1 < pageCount)
        );
    }

    private int inventoryOrbPageCount(int entryCount) {
        return Math.max(1, (entryCount + INVENTORY_ORB_CONTENT_SLOT_COUNT - 1)
            / INVENTORY_ORB_CONTENT_SLOT_COUNT);
    }

    private @NotNull ItemStack createInventoryOrbItem(@NotNull OrbInventoryEntry entry) {
        int maxStack = Math.max(1, entry.model().getMaxStack());
        int displayAmount = (int) Math.min(
            maxStack,
            Math.max(1L, entry.quantity())
        );
        ItemStack item = itemStackFactory.create(entry.model(), displayAmount);
        appendLore(item, List.of(
            Component.empty(),
            Component.text("所持数: " + entry.quantity(), NamedTextColor.AQUA),
            Component.text("クリックで使用", NamedTextColor.GOLD)
        ));
        return item;
    }

    private @NotNull ItemStack createInventoryOrbListInfo(
        int page,
        int pageCount,
        int orbTypeCount
    ) {
        return GuiItems.create(
            Material.CHEST,
            Component.text("インベントリ内のオーブ", NamedTextColor.GOLD),
            List.of(
                Component.text("種類数: " + orbTypeCount, NamedTextColor.GRAY),
                Component.text("ページ: " + (page + 1) + " / " + pageCount, NamedTextColor.GRAY),
                Component.text("オーブをクリックして使用", NamedTextColor.YELLOW)
            )
        );
    }

    private void fillInventoryOrbList(@NotNull Inventory inventory) {
        ItemStack filler = GuiItems.create(
            Material.BLACK_STAINED_GLASS_PANE,
            Component.text(" "),
            List.of()
        );
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        for (int logicalSlot = 0; logicalSlot < INVENTORY_ORB_CONTENT_SLOT_COUNT; logicalSlot++) {
            int slot = (logicalSlot / 7 + 1) * 9 + logicalSlot % 7 + 1;
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
    }

    /**
     * 現在ページの候補とナビゲーションを一覧へ再描画します。
     *
     * @param session 操作セッション
     * @param orbModel 使用オーブのマスタ
     * @param inventory 描画先GUI
     * @param candidates 正本から再収集した候補
     */
    private void renderList(
        @NotNull OrbSession session,
        @NotNull ItemModel orbModel,
        @NotNull Inventory inventory,
        @NotNull List<OrbCandidate> candidates
    ) {
        fillInventory(inventory);
        int pageCount = Math.max(1, (candidates.size() + CONTENT_SLOT_COUNT - 1) / CONTENT_SLOT_COUNT);
        session.page = Math.max(0, Math.min(session.page, pageCount - 1));
        int fromIndex = session.page * CONTENT_SLOT_COUNT;
        int toIndex = Math.min(candidates.size(), fromIndex + CONTENT_SLOT_COUNT);
        Map<Integer, String> displayed = new LinkedHashMap<>();
        for (int index = fromIndex; index < toIndex; index++) {
            int slot = index - fromIndex;
            OrbCandidate candidate = candidates.get(index);
            inventory.setItem(slot, createCandidateItem(candidate, orbModel.getOrb().getEffect()));
            displayed.put(slot, candidate.instance.getEquipmentInstanceId());
        }
        session.displayedTargets = Map.copyOf(displayed);
        inventory.setItem(PREVIOUS_PAGE_SLOT, pageButton(false, session.page > 0));
        inventory.setItem(NEXT_PAGE_SLOT, pageButton(true, session.page + 1 < pageCount));
        ItemStack info = itemStackFactory.create(orbModel, 1);
        appendLore(info, List.of(
            Component.empty(),
            Component.text("対象装備のみ表示しています。", NamedTextColor.GRAY),
            Component.text("ページ " + (session.page + 1) + " / " + pageCount, NamedTextColor.DARK_GRAY)
        ));
        inventory.setItem(INFO_SLOT, info);
    }

    /**
     * ロックされていないGUIの左クリックだけをページ移動または装備操作へ振り分けます。
     *
     * @param event 取消済みクリックイベント
     * @param session 現在セッション
     */
    private void handleUnlockedGuiClick(@NotNull InventoryClickEvent event, @NotNull OrbSession session) {
        if (event.getRawSlot() < 0
            || event.getRawSlot() >= event.getView().getTopInventory().getSize()
            || event.getClick() != ClickType.LEFT) {
            GuiSound.DENY.play(session.player);
            return;
        }
        if (session.screen == OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM) {
            handleConfirmationClick(event.getRawSlot(), session);
            return;
        }
        if (session.screen == OrbGuiHolder.Screen.TRANSCENDENCE_MATERIAL_LIST) {
            handleMaterialListClick(event.getRawSlot(), session);
            return;
        }
        if (session.screen == OrbGuiHolder.Screen.RUNE_ATTACH
            || session.screen == OrbGuiHolder.Screen.RUNE_DETACH
            || session.screen == OrbGuiHolder.Screen.RUNE_DETACH_SELECT) {
            handleRuneGuiClick(event, session);
            return;
        }

        if (event.getRawSlot() == INFO_SLOT) {
            openInventoryOrbList(session.player, session.astPlayer, session);
            return;
        }

        ItemModel orbModel = resolveCurrentOrb(session);
        if (orbModel == null) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5289);
            closeAndRemove(session);
            return;
        }
        if (event.getRawSlot() == PREVIOUS_PAGE_SLOT && session.page > 0) {
            session.page--;
            renderList(session, orbModel, session.inventory, collectCandidates(session, orbModel));
            GuiSound.PAGE.play(session.player);
            return;
        }
        if (event.getRawSlot() == NEXT_PAGE_SLOT) {
            int pageCount = Math.max(1,
                (collectCandidates(session, orbModel).size() + CONTENT_SLOT_COUNT - 1) / CONTENT_SLOT_COUNT);
            if (session.page + 1 < pageCount) {
                session.page++;
                renderList(session, orbModel, session.inventory, collectCandidates(session, orbModel));
                GuiSound.PAGE.play(session.player);
            } else {
                GuiSound.DENY.play(session.player);
            }
            return;
        }
        String targetId = session.displayedTargets.get(event.getRawSlot());
        if (targetId == null) {
            GuiSound.DENY.play(session.player);
            return;
        }
        OrbCandidate target = collectCandidates(session, orbModel).stream()
            .filter(candidate -> candidate.instance.getEquipmentInstanceId().equalsIgnoreCase(targetId))
            .findFirst()
            .orElse(null);
        if (target == null) {
            PlayerMessageService.getInstance().send(
                session.player,
                orbModel.getOrb().getEffect().getType() == ItemOrbEffectType.ENCHANT
                    ? PlayerMsgId.P_5294
                    : PlayerMsgId.P_5290
            );
            renderList(session, orbModel, session.inventory, collectCandidates(session, orbModel));
            GuiSound.DENY.play(session.player);
            return;
        }
        session.processingSlot = event.getRawSlot();
        if (orbModel.getOrb().getEffect().getType() == ItemOrbEffectType.TRANSCENDENCE) {
            openTranscendenceConfirmation(session, orbModel, target);
            return;
        }
        if (orbModel.getOrb().getEffect().getType() == ItemOrbEffectType.RUNE_ATTACH
            || orbModel.getOrb().getEffect().getType() == ItemOrbEffectType.RUNE_DETACH) {
            openRuneScreen(session, target, orbModel.getOrb().getEffect().getType());
            return;
        }
        executeCandidate(session, orbModel, target);
    }

    /**
     * 正本entryが数量を持つオーブか検証してロード済みマスタを返します。
     *
     * @param entry 検証する所持品entry
     * @return 有効なオーブマスタ。条件外は {@code null}
     */
    private @Nullable ItemModel resolveOrbModel(@Nullable InventoryEntryModel entry) {
        if (entry == null
            || entry.isDeleted()
            || entry.getQuantity() <= 0L
            || entry.getItemId() == null
            || ItemCategory.fromApiValue(entry.getItemCategory()) != ItemCategory.ORB) {
            return null;
        }
        ItemModel model = itemService.findLoadedById(entry.getItemId());
        return model != null
            && ItemCategory.fromApiValue(model.getCategory()) == ItemCategory.ORB
            && model.getOrb() != null
            && model.getOrb().getEffect() != null
            ? model
            : null;
    }

    /** ルーン装着・脱着用の3行確認画面を開きます。 */
    private void openRuneScreen(@NotNull OrbSession session, @NotNull OrbCandidate target,
        @NotNull ItemOrbEffectType type) {
        session.selectedTargetId = target.instance.getEquipmentInstanceId();
        session.selectedRuneItemId = null;
        session.selectedRuneSlot = -1;
        session.screen = type == ItemOrbEffectType.RUNE_ATTACH
            ? OrbGuiHolder.Screen.RUNE_ATTACH : OrbGuiHolder.Screen.RUNE_DETACH;
        if (type == ItemOrbEffectType.RUNE_DETACH && target.instance.getRunes().size() == 1) {
            EquipmentRune rune = target.instance.getRunes().getFirst();
            session.selectedRuneItemId = rune.getItemId();
            session.selectedRuneSlot = rune.getSlotIndex();
        }
        session.inventory = Bukkit.createInventory(
            new OrbGuiHolder(session.player.getUniqueId(), session.token, session.screen),
            OrbGuiHolder.sizeFor(session.screen),
            Component.text(type == ItemOrbEffectType.RUNE_ATTACH ? "ルーン装着" : "ルーン脱着", NamedTextColor.DARK_PURPLE));
        renderRuneScreen(session, target);
        transitionInventory(session, session.inventory, session.screen);
    }

    /** 現在選択中の対象・ルーンから3行の完成プレビューを描画します。 */
    private void renderRuneScreen(@NotNull OrbSession session, @NotNull OrbCandidate target) {
        fillInventory(session.inventory);
        session.inventory.setItem(RUNE_TARGET_SLOT, itemStackFactory.create(target.model, target.instance, 1));
        ItemStack selector = new ItemStack(Material.CHEST);
        ItemMeta selectorMeta = selector.getItemMeta();
        selectorMeta.displayName(Component.text(session.screen == OrbGuiHolder.Screen.RUNE_ATTACH
            ? "インベントリ内のルーンを選択" : "脱着するルーンを選択", NamedTextColor.YELLOW));
        selectorMeta.lore(List.of(Component.text(session.screen == OrbGuiHolder.Screen.RUNE_ATTACH
            ? "下段の所持ルーンをクリック" : "クリックして装着済みルーンを選択", NamedTextColor.GRAY)));
        selector.setItemMeta(selectorMeta);
        if (session.selectedRuneItemId != null) {
            ItemModel rune = itemService.findLoadedById(session.selectedRuneItemId);
            if (rune != null) selector = itemStackFactory.create(rune, 1);
        }
        session.inventory.setItem(RUNE_SELECTION_SLOT, selector);
        session.inventory.setItem(CONFIRM_BACK_SLOT, GuiItems.backButton());
        boolean ready = isRuneOperationReady(session, target);
        ItemStack result = ready ? itemStackFactory.create(target.model, previewRuneEquipment(session, target), 1)
            : GuiItems.create(Material.BARRIER, Component.text("ルーンを選択してください", NamedTextColor.RED), List.of());
        appendLore(result, List.of(Component.empty(), Component.text(ready ? "クリックして確定" : "操作できません", ready ? NamedTextColor.GREEN : NamedTextColor.RED)));
        session.inventory.setItem(RUNE_RESULT_SLOT, result);
        if (session.screen == OrbGuiHolder.Screen.RUNE_DETACH && ready) {
            ItemModel rune = itemService.findLoadedById(session.selectedRuneItemId);
            if (rune != null) {
                ItemStack returned = itemStackFactory.create(rune, 1);
                appendLore(returned, List.of(Component.text("取り外し後に返却されます", NamedTextColor.GREEN)));
                session.inventory.setItem(RUNE_RETURN_SLOT, returned);
            }
        }
    }

    private void handleRuneGuiClick(@NotNull InventoryClickEvent event, @NotNull OrbSession session) {
        if (event.getClick() != ClickType.LEFT) { GuiSound.DENY.play(session.player); return; }
        ItemModel orb = resolveCurrentOrb(session);
        if (orb == null || session.selectedTargetId == null) { closeAndRemove(session); return; }
        OrbCandidate target = collectCandidates(session, orb).stream().filter(c ->
            c.instance.getEquipmentInstanceId().equalsIgnoreCase(session.selectedTargetId)).findFirst().orElse(null);
        if (target == null) { closeAndRemove(session); return; }
        int topSize = event.getView().getTopInventory().getSize();
        if ((session.screen == OrbGuiHolder.Screen.RUNE_ATTACH
            || session.screen == OrbGuiHolder.Screen.RUNE_DETACH)
            && event.getRawSlot() == CONFIRM_BACK_SLOT) {
            returnToOrbTargetList(session, orb);
            return;
        }
        if (session.screen == OrbGuiHolder.Screen.RUNE_ATTACH
            && event.getRawSlot() == RUNE_SELECTION_SLOT
            && session.selectedRuneItemId != null) {
            session.selectedRuneItemId = null;
            renderRuneScreen(session, target);
            GuiSound.SELECT.play(session.player);
            return;
        }
        if (session.screen == OrbGuiHolder.Screen.RUNE_ATTACH && event.getRawSlot() >= topSize) {
            InventoryEntryModel entry = inventoryService.getOwnedEntryAtBukkitSlot(session.astPlayer, event.getSlot());
            ItemModel rune = entry == null || entry.getItemId() == null ? null : itemService.findLoadedById(entry.getItemId());
            if (rune == null || ItemCategory.fromApiValue(rune.getCategory()) != ItemCategory.RUNE) { GuiSound.DENY.play(session.player); return; }
            session.selectedRuneItemId = rune.getId(); renderRuneScreen(session, target); GuiSound.SELECT.play(session.player); return;
        }
        if (session.screen == OrbGuiHolder.Screen.RUNE_DETACH && event.getRawSlot() == RUNE_SELECTION_SLOT) {
            session.screen = OrbGuiHolder.Screen.RUNE_DETACH_SELECT;
            session.runePage = 0;
            openRuneDetachSelection(session, target); return;
        }
        if (session.screen == OrbGuiHolder.Screen.RUNE_DETACH_SELECT) {
            int rawSlot = event.getRawSlot();
            if (rawSlot == RUNE_DETACH_SELECT_BACK_SLOT) {
                session.screen = OrbGuiHolder.Screen.RUNE_DETACH;
                session.inventory = Bukkit.createInventory(
                    new OrbGuiHolder(session.player.getUniqueId(), session.token, session.screen),
                    OrbGuiHolder.sizeFor(session.screen),
                    Component.text("ルーン脱着", NamedTextColor.DARK_PURPLE));
                renderRuneScreen(session, target);
                transitionInventory(session, session.inventory, session.screen);
                GuiSound.SELECT.play(session.player);
                return;
            }
            if (rawSlot == 18) {
                if (session.runePage > 0) { session.runePage--; openRuneDetachSelection(session, target); }
                else { GuiSound.DENY.play(session.player); }
                return;
            }
            if (rawSlot == 26) {
                if ((session.runePage + 1) * 18 < target.instance.getRunes().size()) {
                    session.runePage++;
                    openRuneDetachSelection(session, target);
                } else {
                    GuiSound.DENY.play(session.player);
                }
                return;
            }
            if (rawSlot < 0 || rawSlot >= 18) { GuiSound.DENY.play(session.player); return; }
            int index = session.runePage * 18 + rawSlot;
            if (index >= target.instance.getRunes().size()) { GuiSound.DENY.play(session.player); return; }
            EquipmentRune rune = target.instance.getRunes().get(index);
            session.selectedRuneItemId = rune.getItemId(); session.selectedRuneSlot = rune.getSlotIndex();
            session.screen = OrbGuiHolder.Screen.RUNE_DETACH; session.inventory = Bukkit.createInventory(
                new OrbGuiHolder(session.player.getUniqueId(), session.token, session.screen),
                OrbGuiHolder.sizeFor(session.screen), Component.text("ルーン脱着", NamedTextColor.DARK_PURPLE));
            renderRuneScreen(session, target); transitionInventory(session, session.inventory, session.screen); GuiSound.SELECT.play(session.player); return;
        }
        if (event.getRawSlot() != RUNE_RESULT_SLOT || session.selectedRuneItemId == null) { GuiSound.DENY.play(session.player); return; }
        session.processingSlot = RUNE_RESULT_SLOT;
        executeCandidate(session, orb, target);
    }

    /** 選択済みルーンが対象装備へ装着・脱着できるかをGUI表示用に再検証します。 */
    private boolean isRuneOperationReady(@NotNull OrbSession session, @NotNull OrbCandidate target) {
        if (session.selectedRuneItemId == null) return false;
        if (session.screen == OrbGuiHolder.Screen.RUNE_DETACH) {
            return target.instance.getRunes().stream().anyMatch(rune -> rune.getSlotIndex() == session.selectedRuneSlot
                && rune.getItemId().equalsIgnoreCase(session.selectedRuneItemId));
        }
        ItemModel rune = itemService.findLoadedById(session.selectedRuneItemId);
        if (rune == null || rune.getRune() == null || target.model.getEquipment() == null
            || target.model.getEquipment().getRune() == null
            || target.instance.getRuneMaxSlots() <= target.instance.getRunes().size()
            || target.instance.getEnhanceLevel() < rune.getRune().getRequiredEnhanceLevel()) return false;
        return RuneTargetMatcher.matches(rune.getRune(), target.model.getEquipment());
    }

    /** 右側の完成形表示だけに使う、選択内容を反映した一時装備個体を作成します。 */
    private @NotNull EquipmentInstance previewRuneEquipment(@NotNull OrbSession session, @NotNull OrbCandidate target) {
        List<EquipmentRune> runes = new ArrayList<>(target.instance.getRunes());
        if (session.screen == OrbGuiHolder.Screen.RUNE_ATTACH) {
            int slot = 0;
            while (true) {
                int candidate = slot;
                if (runes.stream().noneMatch(rune -> rune.getSlotIndex() == candidate)) {
                    break;
                }
                slot++;
            }
            runes.add(new EquipmentRune("preview", target.instance.getEquipmentInstanceId(), slot, session.selectedRuneItemId));
        } else {
            runes.removeIf(rune -> rune.getSlotIndex() == session.selectedRuneSlot);
        }
        return new EquipmentInstance(
            target.instance.getEquipmentInstanceId(), target.instance.getAccountId(), target.instance.getItemId(),
            target.instance.getEnhanceLevel(), target.instance.getRuneMaxSlots(), target.instance.getTranscendenceRank(),
            target.instance.getDurabilityMax(), target.instance.getDurabilityValue(), target.instance.getCreatedAt(),
            target.instance.getUpdatedAt(), target.instance.getStatRolls(), target.instance.getEnchants(), runes
        );
    }

    /** 装備済みルーンを3行・18件単位で選択するページを描画します。 */
    private void openRuneDetachSelection(@NotNull OrbSession session, @NotNull OrbCandidate target) {
        session.inventory = Bukkit.createInventory(new OrbGuiHolder(session.player.getUniqueId(), session.token,
            OrbGuiHolder.Screen.RUNE_DETACH_SELECT), OrbGuiHolder.sizeFor(OrbGuiHolder.Screen.RUNE_DETACH_SELECT),
            Component.text("脱着ルーン選択", NamedTextColor.DARK_PURPLE));
        fillInventory(session.inventory);
        int from = session.runePage * 18;
        for (int index = from; index < Math.min(from + 18, target.instance.getRunes().size()); index++) {
            ItemModel rune = itemService.findLoadedById(target.instance.getRunes().get(index).getItemId());
            if (rune != null) session.inventory.setItem(index - from, itemStackFactory.create(rune, 1));
        }
        session.inventory.setItem(18, pageButton(false, session.runePage > 0));
        session.inventory.setItem(RUNE_DETACH_SELECT_BACK_SLOT, GuiItems.backButton());
        session.inventory.setItem(26, pageButton(true, from + 18 < target.instance.getRunes().size()));
        transitionInventory(session, session.inventory, OrbGuiHolder.Screen.RUNE_DETACH_SELECT);
    }

    private @Nullable OrbInventoryListSession currentInventoryOrbListSession(
        @NotNull Player player,
        @NotNull Inventory inventory
    ) {
        if (!(inventory.getHolder() instanceof OrbGuiHolder holder)
            || holder.screen() != OrbGuiHolder.Screen.INVENTORY_ORB_LIST
            || !holder.ownerId().equals(player.getUniqueId())) {
            return null;
        }
        OrbInventoryListSession session = inventoryOrbListSessions.get(player.getUniqueId());
        return session != null
            && session.token.equals(holder.sessionToken())
            && session.inventory == inventory
            ? session
            : null;
    }

    /**
     * item ID を正本stateの通常アイテム消費順で解決し、現在の消費対象entryを更新します。
     *
     * @param session 操作セッション
     * @return 現在消費できるオーブマスタ。全消費済みなら {@code null}
     */
    private @Nullable ItemModel resolveCurrentOrb(@NotNull OrbSession session) {
        if (inventoryStateRegistry.get(session.accountId) == null
            || AstPlayerCache.get(session.player) != session.astPlayer) {
            return null;
        }
        InventoryEntryModel consumable = inventoryService.findOwnedNormalItemEntryForConsumption(
            session.accountId,
            session.orbItemId
        );
        ItemModel consumableModel = resolveOrbModel(consumable);
        if (consumableModel != null && consumableModel.getId().equalsIgnoreCase(session.orbItemId)) {
            session.orbEntryId = consumable.getInventoryEntryId();
            return consumableModel;
        }
        return null;
    }

    /**
     * BAG、HOTBAR順かつ各スロット順で有効entryを取得します。
     *
     * @param accountId 対象アカウントID
     * @return 安定順の所持品entry
     */
    private @NotNull List<InventoryEntryModel> ownedEntries(@NotNull UUID accountId) {
        List<InventoryEntryModel> entries = new ArrayList<>();
        inventoryService.getInventories(accountId).stream()
            .filter(inventory -> inventory.isEnabled() && !inventory.isDeleted())
            .filter(inventory -> inventory.getInventoryType() == InventoryType.BAG
                || inventory.getInventoryType() == InventoryType.HOTBAR)
            .sorted(Comparator.comparingInt(inventory ->
                inventory.getInventoryType() == InventoryType.BAG ? 0 : 1))
            .forEach(inventory -> inventoryService.getEntries(inventory.getInventoryId()).stream()
                .filter(entry -> !entry.isDeleted())
                .sorted(Comparator
                    .comparing((InventoryEntryModel entry) -> entry.getSlotIndex() == null
                        ? Integer.MAX_VALUE
                        : entry.getSlotIndex())
                    .thenComparing(InventoryEntryModel::getCreatedAt))
                .forEach(entries::add));
        return List.copyOf(entries);
    }

    /**
     * BAG と HOTBAR の有効なオーブ entry を item ID 単位へ集約します。
     *
     * @param accountId 対象アカウント ID
     * @return 表示順を維持したオーブ種類一覧
     */
    private @NotNull List<OrbInventoryEntry> collectInventoryOrbs(@NotNull UUID accountId) {
        Map<String, OrbInventoryEntry> byItemId = new LinkedHashMap<>();
        for (InventoryEntryModel entry : ownedEntries(accountId)) {
            ItemModel model = resolveOrbModel(entry);
            if (model == null) {
                continue;
            }
            String key = model.getId().toLowerCase(Locale.ROOT);
            OrbInventoryEntry current = byItemId.get(key);
            if (current == null) {
                byItemId.put(key, new OrbInventoryEntry(model.getId(), model, entry.getQuantity()));
            } else {
                byItemId.put(key, new OrbInventoryEntry(
                    current.itemId(),
                    current.model(),
                    saturatingAdd(current.quantity(), entry.getQuantity())
                ));
            }
        }
        return List.copyOf(byItemId.values());
    }

    private @Nullable InventoryEntryModel findOwnedOrbEntry(
        @NotNull UUID accountId,
        @NotNull String itemId
    ) {
        InventoryEntryModel entry = inventoryService.findOwnedNormalItemEntryForConsumption(accountId, itemId);
        return resolveOrbModel(entry) == null ? null : entry;
    }

    private long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /**
     * 装備中を先頭にし、instance IDを重複排除して適格装備だけを収集します。
     *
     * @param session 操作セッション
     * @param orbModel 使用オーブのマスタ
     * @return 表示・再検証に共用する候補
     */
    private @NotNull List<OrbCandidate> collectCandidates(
        @NotNull OrbSession session,
        @NotNull ItemModel orbModel
    ) {
        ItemOrbEffect effect = orbModel.getOrb().getEffect();
        EnchantMaster enchantMaster = effect.getType() == ItemOrbEffectType.ENCHANT
            && effect.getEnchantMasterId() != null
            ? itemService.findEnchantMasterById(effect.getEnchantMasterId())
            : null;
        Map<String, OrbCandidate> candidates = new LinkedHashMap<>();

        for (ItemReference reference : inventoryService.getEquippedItemReferences(session.astPlayer)) {
            if (reference.hasEquipmentInstanceId()) {
                addCandidate(candidates, session, effect, enchantMaster, reference.equipmentInstanceId(), true);
            }
        }
        for (InventoryEntryModel entry : ownedEntries(session.accountId)) {
            if (entry.getInstanceId() == null
                || ItemCategory.fromApiValue(entry.getItemCategory()) != ItemCategory.EQUIPMENT) {
                continue;
            }
            addCandidate(candidates, session, effect, enchantMaster, entry.getInstanceId().toString(), false);
        }
        return List.copyOf(candidates.values());
    }

    /**
     * 所有・マスタ・効果条件を満たす装備個体を候補へ追加します。
     *
     * @param candidates instance ID正規化キーの候補map
     * @param session 操作セッション
     * @param effect オーブ効果
     * @param enchantMaster エンチャント時の共通マスタ
     * @param equipmentInstanceId 検証対象instance ID
     * @param equipped 装備中として見つかった場合 {@code true}
     */
    private void addCandidate(
        @NotNull Map<String, OrbCandidate> candidates,
        @NotNull OrbSession session,
        @NotNull ItemOrbEffect effect,
        @Nullable EnchantMaster enchantMaster,
        @NotNull String equipmentInstanceId,
        boolean equipped
    ) {
        String normalizedId = equipmentInstanceId.trim().toLowerCase(Locale.ROOT);
        OrbCandidate existing = candidates.get(normalizedId);
        if (existing != null) {
            if (equipped && !existing.equipped) {
                candidates.put(normalizedId, new OrbCandidate(existing.model, existing.instance, true));
            }
            return;
        }
        EquipmentInstance instance = itemService.findLoadedEquipmentInstanceById(equipmentInstanceId);
        if (instance == null || !instance.getAccountId().equalsIgnoreCase(session.accountId.toString())) {
            return;
        }
        ItemModel model = itemService.findLoadedById(instance.getItemId());
        if (model == null
            || ItemCategory.fromApiValue(model.getCategory()) != ItemCategory.EQUIPMENT
            || model.getEquipment() == null
            || !isEligible(effect, model, instance, enchantMaster)) {
            return;
        }
        candidates.put(normalizedId, new OrbCandidate(model, instance, equipped));
    }

    /**
     * 効果種別ごとの候補条件を純粋判定へ委譲します。
     *
     * @param effect オーブ効果
     * @param model 装備マスタ
     * @param instance 装備個体
     * @param enchantMaster エンチャント共通マスタ
     * @return 一覧へ表示できる場合 {@code true}
     */
    private boolean isEligible(
        @NotNull ItemOrbEffect effect,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        @Nullable EnchantMaster enchantMaster
    ) {
        return switch (effect.getType()) {
            case ENHANCE -> OrbEligibility.resolveEnhancement(effect, model, instance) != null;
            case REPAIR -> OrbEligibility.canRepair(effect, model, instance);
            case TRANSCENDENCE -> OrbEligibility.resolveTranscendence(effect, model, instance) != null;
            case ENCHANT -> OrbEligibility.canEnchant(effect, model, instance, enchantMaster);
            case RUNE_ATTACH -> instance.getRuneMaxSlots() > instance.getRunes().size();
            case RUNE_DETACH -> !instance.getRunes().isEmpty();
        };
    }

    /**
     * 装備表示へ各操作の実行結果説明を追記します。
     *
     * @param candidate 表示候補
     * @param effect オーブ効果
     * @return GUI表示用ItemStack
     */
    private @NotNull ItemStack createCandidateItem(
        @NotNull OrbCandidate candidate,
        @NotNull ItemOrbEffect effect
    ) {
        ItemStack item = itemStackFactory.create(candidate.model, candidate.instance, 1);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (candidate.equipped) {
            lore.add(Component.text("装備中", NamedTextColor.GREEN));
        }
        switch (effect.getType()) {
            case ENHANCE -> {
                OrbEligibility.EnhancementPlan plan = Objects.requireNonNull(
                    OrbEligibility.resolveEnhancement(effect, candidate.model, candidate.instance));
                lore.add(Component.text("次の強化値: +" + plan.targetLevel(), NamedTextColor.YELLOW));
                lore.add(Component.text(
                    "成功率: " + formatPercent(plan.levelDefinition().getSuccessRate()) + "%",
                    NamedTextColor.AQUA
                ));
                lore.add(Component.text(
                    "失敗時: " + failureDescription(plan.levelDefinition().getFailAction(),
                        plan.levelDefinition().getFailTargetLevel()),
                    NamedTextColor.RED
                ));
            }
            case REPAIR -> {
                int recovered = effect.getRepairFull()
                    ? candidate.instance.getDurabilityMax() - candidate.instance.getDurabilityValue()
                    : Math.min(
                        Objects.requireNonNullElse(effect.getRepairAmount(), 0),
                        candidate.instance.getDurabilityMax() - candidate.instance.getDurabilityValue()
                    );
                lore.add(Component.text(
                    effect.getRepairFull() ? "耐久値を全回復" : "耐久値を " + recovered + " 回復",
                    NamedTextColor.GREEN
                ));
            }
            case TRANSCENDENCE -> {
                OrbEligibility.TranscendencePlan plan = Objects.requireNonNull(
                    OrbEligibility.resolveTranscendence(effect, candidate.model, candidate.instance));
                lore.add(Component.text(
                    transitionListDescription(plan.definition()),
                    NamedTextColor.LIGHT_PURPLE
                ));
                lore.add(Component.text("クリックして必要素材を確認", NamedTextColor.YELLOW));
            }
            case RUNE_ATTACH -> lore.add(Component.text("クリックしてルーンを装着", NamedTextColor.GREEN));
            case RUNE_DETACH -> lore.add(Component.text("クリックしてルーンを取り外し", NamedTextColor.AQUA));
            case ENCHANT -> {
                int maxSlots = OrbEligibility.effectiveEnchantMaxSlots(
                    candidate.model.getEquipment(), candidate.instance.getTranscendenceRank());
                lore.add(Component.text(
                    "エンチャント枠: " + candidate.instance.getEnchants().size() + " / " + maxSlots,
                    NamedTextColor.AQUA
                ));
                lore.add(Component.text(enchantOperationDescription(effect), NamedTextColor.YELLOW));
            }
        }
        lore.add(Component.text("クリックして実行", NamedTextColor.GOLD));
        appendLore(item, lore);
        return item;
    }

    /**
     * ページ移動ボタンを作成します。
     *
     * @param next 次ページボタンなら {@code true}
     * @param enabled 移動可能なら {@code true}
     * @return 表示用ボタン
     */
    private @NotNull ItemStack pageButton(boolean next, boolean enabled) {
        Material material = enabled ? Material.ARROW : Material.GRAY_DYE;
        String name = next ? "次のページ" : "前のページ";
        NamedTextColor color = enabled ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY;
        return GuiItems.create(material, Component.text(name, color), List.of());
    }

    /**
     * GUI全枠を操作不能な背景itemで初期化します。
     *
     * @param inventory 描画先GUI
     */
    private void fillInventory(@NotNull Inventory inventory) {
        ItemStack filler = GuiItems.create(
            Material.GRAY_STAINED_GLASS_PANE,
            Component.text(" "),
            List.of()
        );
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    /**
     * 既存loreを保持して説明行を末尾へ追加します。
     *
     * @param item 更新対象item
     * @param additions 追加する説明行
     */
    private void appendLore(@NotNull ItemStack item, @NotNull List<Component> additions) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.lore() == null
            ? new ArrayList<>()
            : new ArrayList<>(meta.lore());
        lore.addAll(additions);
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /**
     * 強化失敗時のマスタ動作をプレイヤー向け文言へ変換します。
     *
     * @param action 失敗時動作
     * @param targetLevel SET_LEVEL時の指定値
     * @return 説明文
     */
    private @NotNull String failureDescription(
        @NotNull ItemEquipmentEnhanceFailAction action,
        @Nullable Integer targetLevel
    ) {
        return switch (action) {
            case NONE -> "強化値は変化しない";
            case SET_LEVEL -> "強化値が +" + Math.max(0, Objects.requireNonNullElse(targetLevel, 0)) + " になる";
            case DECREASE_ONE -> "現在の強化値から1下がる";
        };
    }

    /**
     * エンチャント枠操作をプレイヤー向け文言へ変換します。
     *
     * @param effect オーブ効果
     * @return 説明文
     */
    private @NotNull String enchantOperationDescription(@NotNull ItemOrbEffect effect) {
        if (effect.getEnchantOperation() == null) {
            return "エンチャント情報を取得できません";
        }
        return switch (effect.getEnchantOperation()) {
            case OVERWRITE_RANDOM -> "既存の1枠をランダムに上書き";
            case FILL_ONE_EMPTY -> "空いている1枠へ付与";
            case FILL_ALL_EMPTY -> "空いている全枠へ1個で付与";
        };
    }

    /**
     * 状態変化名を空値フォールバック付きで返します。
     *
     * @param definition 状態変化定義
     * @return プレイヤー表示名
     */
    private @NotNull String transitionName(@NotNull ItemEquipmentTranscendence definition) {
        return definition.getName() == null || definition.getName().isBlank()
            ? "次の状態"
            : definition.getName();
    }

    /**
     * 一覧用に数値ランクを含まない状態変化説明を返します。
     *
     * @param definition 状態変化定義
     * @return 「状態変化名へ変化」形式の説明
     */
    static @NotNull String transitionListDescription(@NotNull ItemEquipmentTranscendence definition) {
        String name = definition.getName() == null || definition.getName().isBlank()
            ? "次の状態"
            : definition.getName();
        return name + "へ変化";
    }

    /**
     * 0から1の割合を小数第一位で四捨五入した整数百分率へ変換します。
     *
     * @param rate 割合
     * @return パーセント記号を含まない整数の数値文字列
     */
    static @NotNull String formatPercent(double rate) {
        return BigDecimal.valueOf(Math.clamp(rate, 0.0D, 1.0D))
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .toPlainString();
    }

    /**
     * 選択装備を保持して状態変化専用の確認画面へ遷移します。
     *
     * @param session 操作セッション
     * @param orbModel 使用オーブのマスタ
     * @param target 再検証済み対象装備
     */
    private void openTranscendenceConfirmation(
        @NotNull OrbSession session,
        @NotNull ItemModel orbModel,
        @NotNull OrbCandidate target
    ) {
        session.selectedTargetId = target.instance.getEquipmentInstanceId();
        Inventory confirmation = Bukkit.createInventory(
            new OrbGuiHolder(
                session.player.getUniqueId(),
                session.token,
                OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM
            ),
            OrbGuiHolder.sizeFor(OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM),
            Component.text("状態変化の確認", NamedTextColor.DARK_PURPLE)
        );
        renderTranscendenceConfirmation(session, orbModel, target, confirmation);
        transitionInventory(session, confirmation, OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM);
    }

    /**
     * 即時次ランク名と素材・ゴールドの所持状況を確認画面へ描画します。
     *
     * @param session 操作セッション
     * @param orbModel 使用オーブのマスタ
     * @param target 再検証済み対象装備
     * @param inventory 描画先GUI
     */
    private void renderTranscendenceConfirmation(
        @NotNull OrbSession session,
        @NotNull ItemModel orbModel,
        @NotNull OrbCandidate target,
        @NotNull Inventory inventory
    ) {
        fillInventory(inventory);
        ItemOrbEffect effect = orbModel.getOrb().getEffect();
        OrbEligibility.TranscendencePlan plan = OrbEligibility.resolveTranscendence(
            effect, target.model, target.instance);
        ItemStack targetItem = itemStackFactory.create(target.model, target.instance, 1);
        appendLore(targetItem, List.of(
            Component.empty(),
            Component.text(
                plan == null
                    ? "状態変化先を確認できません"
                    : "状態変化先: " + transitionName(plan.definition()),
                plan == null ? NamedTextColor.RED : NamedTextColor.LIGHT_PURPLE
            )
        ));
        inventory.setItem(CONFIRM_TARGET_SLOT, targetItem);
        inventory.setItem(CONFIRM_BACK_SLOT, GuiItems.create(
            Material.ARROW,
            Component.text("一覧へ戻る", NamedTextColor.YELLOW),
            List.of()
        ));
        if (plan == null) {
            inventory.setItem(CONFIRM_EXECUTE_SLOT, GuiItems.create(
                Material.BARRIER,
                Component.text("状態変化できません", NamedTextColor.RED),
                List.of(Component.text("対象条件が変化しました。", NamedTextColor.GRAY))
            ));
            return;
        }

        List<InventoryService.InventoryItemRequirement> requirements = materialRequirements(
            plan.definition(), orbModel.getId());
        inventory.setItem(CONFIRM_MATERIAL_LIST_SLOT, GuiItems.create(
            Material.CHEST,
            Component.text("消費アイテム一覧", NamedTextColor.YELLOW),
            List.of(
                Component.text("必要素材: " + requirements.size() + " 種類", NamedTextColor.GRAY),
                Component.text("クリックして一覧を開く", NamedTextColor.WHITE)
            )
        ));
        inventory.setItem(CONFIRM_GOLD_SLOT, createGoldRequirementItem(
            session,
            Math.max(0, plan.definition().getRequiredCurrency())
        ));
        boolean enough = hasTransitionRequirements(session, plan.definition(), orbModel.getId());
        inventory.setItem(CONFIRM_EXECUTE_SLOT, GuiItems.create(
            enough ? Material.LIME_CONCRETE : Material.BARRIER,
            Component.text(
                enough ? "状態変化を実行" : "素材またはゴールドが不足",
                enough ? NamedTextColor.GREEN : NamedTextColor.RED
            ),
            List.of(Component.text(
                enough ? "クリックして実行します。" : "不足を解消すると実行できます。",
                NamedTextColor.GRAY
            ))
        ));
    }

    /**
     * 状態変化に必要な消費アイテム一覧をページ付きGUIへ描画します。
     *
     * @param session 操作セッション
     * @param orbModel 使用オーブのマスタ
     * @param target 状態変化対象装備
     * @param inventory 描画先GUI
     */
    private void renderTranscendenceMaterialList(
        @NotNull OrbSession session,
        @NotNull ItemModel orbModel,
        @NotNull OrbCandidate target,
        @NotNull Inventory inventory
    ) {
        fillInventory(inventory);
        OrbEligibility.TranscendencePlan plan = OrbEligibility.resolveTranscendence(
            orbModel.getOrb().getEffect(), target.model, target.instance);
        inventory.setItem(MATERIAL_LIST_BACK_SLOT, GuiItems.create(
            Material.ARROW,
            Component.text("確認画面へ戻る", NamedTextColor.YELLOW),
            List.of()
        ));
        if (plan == null) {
            inventory.setItem(MATERIAL_LIST_GOLD_SLOT, GuiItems.create(
                Material.BARRIER,
                Component.text("状態変化できません", NamedTextColor.RED),
                List.of(Component.text("対象条件が変化しました。", NamedTextColor.GRAY))
            ));
            return;
        }

        List<InventoryService.InventoryItemRequirement> requirements = materialRequirements(
            plan.definition(), orbModel.getId());
        int pageCount = Math.max(1, (requirements.size() + CONTENT_SLOT_COUNT - 1) / CONTENT_SLOT_COUNT);
        session.materialPage = Math.max(0, Math.min(session.materialPage, pageCount - 1));
        int fromIndex = session.materialPage * CONTENT_SLOT_COUNT;
        int toIndex = Math.min(requirements.size(), fromIndex + CONTENT_SLOT_COUNT);
        for (int index = fromIndex; index < toIndex; index++) {
            InventoryService.InventoryItemRequirement requirement = requirements.get(index);
            inventory.setItem(index - fromIndex, createMaterialRequirementItem(session, requirement));
        }

        inventory.setItem(MATERIAL_LIST_PREVIOUS_PAGE_SLOT,
            pageButton(false, session.materialPage > 0));
        inventory.setItem(MATERIAL_LIST_PAGE_INFO_SLOT, GuiItems.create(
            Material.PAPER,
            Component.text("ページ " + (session.materialPage + 1) + " / " + pageCount,
                NamedTextColor.WHITE),
            List.of(Component.text("消費アイテム一覧", NamedTextColor.GRAY))
        ));
        inventory.setItem(MATERIAL_LIST_NEXT_PAGE_SLOT,
            pageButton(true, session.materialPage + 1 < pageCount));
        inventory.setItem(MATERIAL_LIST_GOLD_SLOT, createGoldRequirementItem(
            session,
            Math.max(0, plan.definition().getRequiredCurrency())
        ));
        inventory.setItem(MATERIAL_LIST_BACK_SLOT, GuiItems.create(
            Material.ARROW,
            Component.text("確認画面へ戻る", NamedTextColor.YELLOW),
            List.of(Component.text(
                "状態変化: " + transitionName(plan.definition()),
                NamedTextColor.LIGHT_PURPLE
            ))
        ));
    }

    /**
     * 消費アイテム要件の表示アイコンを作成します。
     *
     * @param session 操作セッション
     * @param requirement アイテム要件
     * @return 要件表示アイコン
     */
    private @NotNull ItemStack createMaterialRequirementItem(
        @NotNull OrbSession session,
        @NotNull InventoryService.InventoryItemRequirement requirement
    ) {
        ItemModel materialModel = itemService.findLoadedById(requirement.itemId());
        ItemStack materialItem = materialModel == null
            ? GuiItems.create(
                Material.CHEST,
                Component.text("未登録の素材", NamedTextColor.RED),
                List.of(Component.text("この素材情報を取得できません。", NamedTextColor.RED))
            )
            : itemStackFactory.create(materialModel, 1);
        long owned = inventoryService.getNormalItemAmount(session.accountId, requirement.itemId());
        appendLore(materialItem, List.of(
            Component.empty(),
            Component.text(
                "必要: " + requirement.amount() + " / 所持: " + owned,
                owned >= requirement.amount() ? NamedTextColor.GREEN : NamedTextColor.RED
            )
        ));
        return materialItem;
    }

    /**
     * 必要ゴールドと現在の所持ゴールドを表示するアイコンを作成します。
     *
     * @param session 操作セッション
     * @param requiredGold 必要ゴールド
     * @return ゴールド要件表示アイコン
     */
    private @NotNull ItemStack createGoldRequirementItem(
        @NotNull OrbSession session,
        long requiredGold
    ) {
        long ownedGold = inventoryService.getGoldAmount(session.accountId);
        return GuiItems.create(
            Material.GOLD_INGOT,
            Component.text("必要ゴールド", NamedTextColor.GOLD),
            List.of(Component.text(
                "必要: " + requiredGold + " / 所持: " + ownedGold,
                ownedGold >= requiredGold ? NamedTextColor.GREEN : NamedTextColor.RED
            ))
        );
    }

    /**
     * 消費アイテム一覧GUIのページ操作と確認画面への復帰を処理します。
     *
     * @param rawSlot クリックされた上段raw slot
     * @param session 操作セッション
     */
    private void handleMaterialListClick(int rawSlot, @NotNull OrbSession session) {
        ItemModel orbModel = resolveCurrentOrb(session);
        if (orbModel == null || session.selectedTargetId == null) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5289);
            closeAndRemove(session);
            return;
        }
        OrbCandidate target = collectCandidates(session, orbModel).stream()
            .filter(candidate -> candidate.instance.getEquipmentInstanceId()
                .equalsIgnoreCase(session.selectedTargetId))
            .findFirst()
            .orElse(null);
        if (target == null) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5290);
            GuiSound.DENY.play(session.player);
            closeAndRemove(session);
            return;
        }
        OrbEligibility.TranscendencePlan plan = OrbEligibility.resolveTranscendence(
            orbModel.getOrb().getEffect(), target.model, target.instance);
        if (plan == null) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5290);
            GuiSound.DENY.play(session.player);
            closeAndRemove(session);
            return;
        }

        List<InventoryService.InventoryItemRequirement> requirements = materialRequirements(
            plan.definition(), orbModel.getId());
        int pageCount = Math.max(1, (requirements.size() + CONTENT_SLOT_COUNT - 1) / CONTENT_SLOT_COUNT);
        if (rawSlot == MATERIAL_LIST_BACK_SLOT) {
            openTranscendenceConfirmation(session, orbModel, target);
            return;
        }
        if (rawSlot == MATERIAL_LIST_PREVIOUS_PAGE_SLOT) {
            if (session.materialPage <= 0) {
                GuiSound.DENY.play(session.player);
                return;
            }
            session.materialPage--;
            renderTranscendenceMaterialList(session, orbModel, target, session.inventory);
            GuiSound.PAGE.play(session.player);
            return;
        }
        if (rawSlot == MATERIAL_LIST_NEXT_PAGE_SLOT) {
            if (session.materialPage + 1 >= pageCount) {
                GuiSound.DENY.play(session.player);
                return;
            }
            session.materialPage++;
            renderTranscendenceMaterialList(session, orbModel, target, session.inventory);
            GuiSound.PAGE.play(session.player);
            return;
        }
        GuiSound.DENY.play(session.player);
    }

    /**
     * 確認画面の戻る操作または不足再検証付き実行を処理します。
     *
     * @param rawSlot クリックされた上段raw slot
     * @param session 操作セッション
     */
    private void handleConfirmationClick(int rawSlot, @NotNull OrbSession session) {
        ItemModel orbModel = resolveCurrentOrb(session);
        if (orbModel == null) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5289);
            closeAndRemove(session);
            return;
        }
        if (rawSlot == CONFIRM_BACK_SLOT) {
            returnToOrbTargetList(session, orbModel);
            return;
        }
        if (rawSlot == CONFIRM_MATERIAL_LIST_SLOT) {
            if (session.selectedTargetId == null) {
                GuiSound.DENY.play(session.player);
                return;
            }
            OrbCandidate target = collectCandidates(session, orbModel).stream()
                .filter(candidate -> candidate.instance.getEquipmentInstanceId()
                    .equalsIgnoreCase(session.selectedTargetId))
                .findFirst()
                .orElse(null);
            if (target == null
                || OrbEligibility.resolveTranscendence(
                    orbModel.getOrb().getEffect(), target.model, target.instance) == null) {
                PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5290);
                GuiSound.DENY.play(session.player);
                closeAndRemove(session);
                return;
            }
            session.materialPage = 0;
            Inventory materialList = Bukkit.createInventory(
                new OrbGuiHolder(
                    session.player.getUniqueId(),
                    session.token,
                    OrbGuiHolder.Screen.TRANSCENDENCE_MATERIAL_LIST
                ),
                OrbGuiHolder.sizeFor(OrbGuiHolder.Screen.TRANSCENDENCE_MATERIAL_LIST),
                Component.text("消費アイテム一覧", NamedTextColor.DARK_PURPLE)
            );
            renderTranscendenceMaterialList(session, orbModel, target, materialList);
            transitionInventory(
                session,
                materialList,
                OrbGuiHolder.Screen.TRANSCENDENCE_MATERIAL_LIST
            );
            return;
        }
        if (rawSlot != CONFIRM_EXECUTE_SLOT || session.selectedTargetId == null) {
            GuiSound.DENY.play(session.player);
            return;
        }
        OrbCandidate target = collectCandidates(session, orbModel).stream()
            .filter(candidate -> candidate.instance.getEquipmentInstanceId()
                .equalsIgnoreCase(session.selectedTargetId))
            .findFirst()
            .orElse(null);
        if (target == null) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5290);
            GuiSound.DENY.play(session.player);
            closeAndRemove(session);
            return;
        }
        OrbEligibility.TranscendencePlan plan = OrbEligibility.resolveTranscendence(
            orbModel.getOrb().getEffect(), target.model, target.instance);
        if (plan == null) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5290);
            GuiSound.DENY.play(session.player);
            closeAndRemove(session);
            return;
        }
        if (!hasTransitionRequirements(session, plan.definition(), orbModel.getId())) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5291);
            renderTranscendenceConfirmation(session, orbModel, target, session.inventory);
            GuiSound.DENY.play(session.player);
            return;
        }
        session.processingSlot = CONFIRM_TARGET_SLOT;
        executeCandidate(session, orbModel, target);
    }

    /** 装備操作の確認画面から、同じオーブの対象装備一覧へ戻します。 */
    private void returnToOrbTargetList(@NotNull OrbSession session, @NotNull ItemModel orbModel) {
        session.selectedTargetId = null;
        session.selectedRuneItemId = null;
        session.selectedRuneSlot = -1;
        Inventory list = Bukkit.createInventory(
            new OrbGuiHolder(session.player.getUniqueId(), session.token, OrbGuiHolder.Screen.LIST),
            OrbGuiHolder.sizeFor(OrbGuiHolder.Screen.LIST),
            Component.text("オーブ対象装備", NamedTextColor.DARK_PURPLE)
        );
        renderList(session, orbModel, list, collectCandidates(session, orbModel));
        transitionInventory(session, list, OrbGuiHolder.Screen.LIST);
    }

    /**
     * closeイベントからセッションを保護しつつオーブ内部画面を切り替えます。
     *
     * @param session 操作セッション
     * @param target 遷移先GUI
     * @param screen 遷移先画面種別
     */
    private void transitionInventory(
        @NotNull OrbSession session,
        @NotNull Inventory target,
        @NotNull OrbGuiHolder.Screen screen
    ) {
        session.transitioning = true;
        inventoryOpener.open(session.player, target, () -> {
            if (sessions.get(session.player.getUniqueId()) != session) {
                return;
            }
            session.inventory = target;
            session.screen = screen;
            session.transitioning = false;
            GuiSound.SELECT.play(session.player);
        }, () -> {
            session.transitioning = false;
            sessions.remove(session.player.getUniqueId(), session);
        });
    }

    /**
     * 状態変化マスタの有効な必要素材を消費サービス用へ変換します。
     *
     * @param definition 状態変化定義
     * @return 正の数量を持つ素材要件
     */
    private @NotNull List<InventoryService.InventoryItemRequirement> materialRequirements(
        @NotNull ItemEquipmentTranscendence definition,
        @NotNull String orbItemId
    ) {
        Map<String, InventoryService.InventoryItemRequirement> aggregated = new LinkedHashMap<>();
        definition.getRequiredMaterials().stream()
            .filter(material -> material.getAmount() > 0 && !material.getItemId().isBlank())
            .forEach(material -> {
                String key = material.getItemId().trim().toLowerCase(Locale.ROOT);
                InventoryService.InventoryItemRequirement previous = aggregated.get(key);
                long amount = material.getAmount() + (previous == null ? 0L : previous.amount());
                aggregated.put(key, new InventoryService.InventoryItemRequirement(material.getItemId(), amount));
            });
        String normalizedOrbItemId = orbItemId.trim().toLowerCase(Locale.ROOT);
        InventoryService.InventoryItemRequirement orbMaterial = aggregated.get(normalizedOrbItemId);
        if (orbMaterial != null) {
            aggregated.put(normalizedOrbItemId, new InventoryService.InventoryItemRequirement(
                orbMaterial.itemId(), Math.addExact(orbMaterial.amount(), 1L)));
        }
        return List.copyOf(aggregated.values());
    }

    /**
     * 確認画面表示時点で全素材とゴールドを所持しているか判定します。
     *
     * @param session 操作セッション
     * @param definition 状態変化定義
     * @return 全要件を満たす場合 {@code true}
     */
    private boolean hasTransitionRequirements(
        @NotNull OrbSession session,
        @NotNull ItemEquipmentTranscendence definition,
        @NotNull String orbItemId
    ) {
        if (inventoryService.getGoldAmount(session.accountId) < Math.max(0, definition.getRequiredCurrency())) {
            return false;
        }
        return materialRequirements(definition, orbItemId).stream().allMatch(requirement ->
            itemService.findLoadedById(requirement.itemId()) != null
                && inventoryService.getNormalItemAmount(session.accountId, requirement.itemId()) >= requirement.amount());
    }

    /**
     * オーブと対象を再検証し、API の単一 transaction へ冪等 operation を送ります。
     * 支払いはローカル state から先行消費せず、API が装備更新と同時に確定します。
     */
    private void executeCandidate(
        @NotNull OrbSession session,
        @NotNull ItemModel orbModel,
        @NotNull OrbCandidate target
    ) {
        ItemModel currentOrb = resolveCurrentOrb(session);
        if (currentOrb == null || !currentOrb.getId().equalsIgnoreCase(orbModel.getId())) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5289);
            closeAndRemove(session);
            return;
        }
        ItemOrbEffect effect = currentOrb.getOrb().getEffect();
        if ((effect.getType() == ItemOrbEffectType.RUNE_ATTACH || effect.getType() == ItemOrbEffectType.RUNE_DETACH)
            && session.selectedRuneItemId == null) {
            GuiSound.DENY.play(session.player);
            return;
        }
        OrbEligibility.TranscendencePlan transitionPlan = null;
        if (effect.getType() == ItemOrbEffectType.TRANSCENDENCE) {
            transitionPlan = OrbEligibility.resolveTranscendence(
                effect, target.model, target.instance);
            if (transitionPlan == null) {
                PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5290);
                GuiSound.DENY.play(session.player);
                return;
            }
        }

        UUID operationId = UUID.randomUUID();
        if (!reserveOperationPayment(session, currentOrb, transitionPlan, operationId)) {
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5289);
            GuiSound.DENY.play(session.player);
            return;
        }
        session.interactionLock.beginMutation();
        session.operationId = operationId;
        session.externalOperationStarted = false;
        showProcessingIcon(session);
        startAsyncMutation(session, target, currentOrb);
    }

    /** APIが支払う資産をローカル消費から予約し、報酬加算や移動は止めず二重支出だけを防ぎます。 */
    private boolean reserveOperationPayment(
        @NotNull OrbSession session,
        @NotNull ItemModel orbModel,
        @Nullable OrbEligibility.TranscendencePlan transitionPlan,
        @NotNull UUID operationId
    ) {
        Map<String, Long> normalItems = new LinkedHashMap<>();
        try {
            normalItems.put(orbModel.getId().trim().toLowerCase(Locale.ROOT), 1L);
            if (orbModel.getOrb().getEffect().getType() == ItemOrbEffectType.RUNE_ATTACH
                && session.selectedRuneItemId != null) {
                normalItems.merge(session.selectedRuneItemId.trim().toLowerCase(Locale.ROOT), 1L, Math::addExact);
            }
            if (transitionPlan != null) {
                transitionPlan.definition().getRequiredMaterials().stream()
                    .filter(material -> material.getAmount() > 0 && !material.getItemId().isBlank())
                    .forEach(material -> normalItems.merge(
                        material.getItemId().trim().toLowerCase(Locale.ROOT),
                        (long) material.getAmount(),
                        Math::addExact
                    ));
            }
        } catch (ArithmeticException overflow) {
            return false;
        }
        long gold = transitionPlan == null
            ? 0L
            : Math.max(0L, transitionPlan.definition().getRequiredCurrency());
        return inventoryService.reserveOrbOperationPayment(
            session.accountId,
            operationId,
            normalItems,
            gold
        );
    }

    /** 保存 lane 内で事前保存、冪等 API 操作、影響 entry の正本照合を一続きで実行します。 */
    private void startAsyncMutation(
        @NotNull OrbSession session,
        @NotNull OrbCandidate target,
        @NotNull ItemModel orbModel
    ) {
        UUID operationId = Objects.requireNonNull(session.operationId);
        CompletableFuture<MutationResult> future = inventorySaveCoordinator.executeExclusiveAfterSave(
            session.accountId,
            baseline -> performOrbOperation(session, target, orbModel, operationId, baseline)
        );
        session.operationFuture = future;
        future.whenComplete((result, throwable) -> {
            if (throwable != null && !session.externalOperationStarted) {
                inventoryService.releaseOrbOperationPayment(session.accountId, operationId);
            }
            if (session.detached) {
                return;
            }
            AsyncTaskUtil.runSync(plugin, () -> {
                if (session.detached
                    || !Objects.equals(operationId, session.operationId)
                    || sessions.get(session.player.getUniqueId()) != session) {
                    return;
                }
                completeMutation(
                    session,
                    throwable == null && result != null
                        ? result
                        : MutationResult.failed(MutationStatus.FAILED)
                );
            });
        });
    }

    /** transport failure 時も同一 operationId を保持し、台帳結果と正本照合が完了するまでlaneを解放しません。 */
    private @NotNull MutationResult performOrbOperation(
        @NotNull OrbSession session,
        @NotNull OrbCandidate target,
        @NotNull ItemModel orbModel,
        @NotNull UUID operationId,
        @NotNull InventoryPersistence.PersistedInventoryBaseline baseline
    ) {
        if (!inventoryService.finalizeOrbOperationPaymentReservation(
            session.accountId,
            operationId,
            baseline
        )) {
            inventoryService.releaseOrbOperationPayment(session.accountId, operationId);
            return MutationResult.failed(MutationStatus.PAYMENT_UNAVAILABLE);
        }
        // From this point onward the POST may commit even if its response is lost. A failure must
        // retain both the unresolved account lane and payment reservation until reconciliation.
        session.externalOperationStarted = true;
        String accountId = session.accountId.toString();
        String operationIdText = operationId.toString();
        long retryDelayMillis = OPERATION_RETRY_INITIAL_MILLIS;
        EquipmentOrbOperationResult operation = null;
        while (operation == null) {
            ensureOperationThreadActive(operationId);
            operation = itemService.applyEquipmentOrbOperation(
                operationIdText,
                accountId,
                target.instance.getEquipmentInstanceId(),
                session.orbEntryId.toString(),
                orbModel.getId(),
                session.selectedRuneItemId,
                session.screen == OrbGuiHolder.Screen.RUNE_DETACH ? session.selectedRuneSlot : null
            );
            if (operation == null) {
                operation = itemService.findEquipmentOrbOperation(operationIdText, accountId);
            }
            if (operation == null) {
                waitForOperationRetry(operationId, retryDelayMillis);
                retryDelayMillis = Math.min(OPERATION_RETRY_MAX_MILLIS, retryDelayMillis * 2L);
            }
        }

        Set<UUID> reconciliationEntryIds = new LinkedHashSet<>();
        for (String affectedEntryId : operation.getAffectedInventoryEntryIds()) {
            reconciliationEntryIds.add(UUID.fromString(affectedEntryId));
        }
        // request origin は古いAPIのaffected欠落や業務失敗でも必ず照合する。
        reconciliationEntryIds.add(session.orbEntryId);
        while (true) {
            ensureOperationThreadActive(operationId);
            try {
                inventoryService.reconcileOrbOperationEntries(
                    session.accountId,
                    reconciliationEntryIds,
                    baseline
                );
                break;
            } catch (Exception reconciliationFailure) {
                waitForOperationRetry(operationId, retryDelayMillis);
                retryDelayMillis = Math.min(OPERATION_RETRY_MAX_MILLIS, retryDelayMillis * 2L);
            }
        }

        // 三者マージは上で一度だけ完了済み。以後のcleanup retryでAPI消費deltaを再適用しない。
        if (!operation.getTargetAvailable()
            && operation.getResult() != EquipmentOrbOperationResultType.OPERATION_CONFLICT
            && operation.getResult() != EquipmentOrbOperationResultType.INVALID) {
            UUID targetInstanceId = UUID.fromString(target.instance.getEquipmentInstanceId());
            while (true) {
                ensureOperationThreadActive(operationId);
                try {
                    itemService.evictEquipmentInstanceFromCache(target.instance.getEquipmentInstanceId());
                    inventoryService.discardUnavailableEquipmentInstance(session.accountId, targetInstanceId);
                    break;
                } catch (Exception cleanupFailure) {
                    waitForOperationRetry(operationId, retryDelayMillis);
                    retryDelayMillis = Math.min(OPERATION_RETRY_MAX_MILLIS, retryDelayMillis * 2L);
                }
            }
        }
        MutationResult result = toMutationResult(operation, target, orbModel);
        inventoryService.releaseOrbOperationPayment(session.accountId, operationId);
        return result;
    }

    /** shutdown interrupt時はpending境界を残したままlane jobを失敗させます。 */
    private void ensureOperationThreadActive(@NotNull UUID operationId) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Orb operation reconciliation interrupted: " + operationId);
        }
    }

    /** 同一operationId再送の指数backoffを非メインスレッドで待機します。 */
    private void waitForOperationRetry(@NotNull UUID operationId, long delayMillis) {
        retryWaiter.await(operationId, Math.max(1L, delayMillis));
    }

    /** production用の再送待機。interruptを復元してpending境界を維持したまま失敗させます。 */
    private static void sleepForOperationRetry(@NotNull UUID operationId, long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Orb operation reconciliation interrupted: " + operationId,
                interrupted
            );
        }
    }

    /** API 台帳結果を GUI 反映用の結果へ変換します。 */
    private @NotNull MutationResult toMutationResult(
        @NotNull EquipmentOrbOperationResult operation,
        @NotNull OrbCandidate target,
        @NotNull ItemModel orbModel
    ) {
        // Availability is current-state metadata, so it takes precedence over the fixed
        // business result (including PAYMENT_UNAVAILABLE/NO_CANDIDATE replays).
        if (!operation.getTargetAvailable()
            && operation.getResult() != EquipmentOrbOperationResultType.OPERATION_CONFLICT
            && operation.getResult() != EquipmentOrbOperationResultType.INVALID) {
            return MutationResult.failed(MutationStatus.TARGET_UNAVAILABLE);
        }
        if (operation.getResult() == EquipmentOrbOperationResultType.NO_CANDIDATE
            || operation.getResult() == EquipmentOrbOperationResultType.NO_SLOT) {
            return MutationResult.failed(MutationStatus.NO_CANDIDATE);
        }
        if (operation.getResult() == EquipmentOrbOperationResultType.PAYMENT_UNAVAILABLE) {
            return MutationResult.failed(MutationStatus.PAYMENT_UNAVAILABLE);
        }
        EquipmentInstance instance = operation.getEquipment();
        if (instance == null && operation.getResult() == EquipmentOrbOperationResultType.APPLIED) {
            return MutationResult.failed(MutationStatus.FAILED);
        }
        if (operation.getResult() == EquipmentOrbOperationResultType.NOT_ELIGIBLE) {
            return MutationResult.failed(MutationStatus.TARGET_CHANGED);
        }
        if (operation.getResult() != EquipmentOrbOperationResultType.APPLIED) {
            return MutationResult.failed(MutationStatus.FAILED);
        }
        ItemModel model = itemService.findLoadedById(instance.getItemId());
        if (model == null) {
            model = target.model;
        }
        return switch (operation.getOperationType().trim().toUpperCase(Locale.ROOT)) {
            case "ENHANCE" -> MutationResult.enhancement(
                model,
                instance,
                operation.getEnhancementSucceeded(),
                Objects.requireNonNullElse(operation.getFailAction(), ItemEquipmentEnhanceFailAction.NONE),
                Objects.requireNonNullElse(operation.getSuccessRate(), 0.0D)
            );
            case "REPAIR" -> MutationResult.repair(
                model,
                instance,
                Objects.requireNonNullElse(operation.getRepairedAmount(), 0)
            );
            case "ENCHANT" -> MutationResult.enchant(model, instance);
            case "RUNE_ATTACH", "RUNE_DETACH" -> MutationResult.rune(model, instance);
            case "TRANSCENDENCE" -> {
                String name = operation.getTransitionName();
                if (name == null || name.isBlank()) {
                    OrbEligibility.TranscendencePlan plan = OrbEligibility.resolveTranscendence(
                        orbModel.getOrb().getEffect(), target.model, target.instance);
                    name = plan == null ? "次の状態" : transitionName(plan.definition());
                }
                yield MutationResult.transcendence(model, instance, name);
            }
            default -> MutationResult.failed(MutationStatus.FAILED);
        };
    }

    /** 装備処理結果を業務失敗表示または即時の結果反映へ収束させます。 */
    private void completeMutation(@NotNull OrbSession session, @NotNull MutationResult result) {
        session.operationFuture = null;
        session.operationId = null;
        session.externalOperationStarted = false;
        if (result.status != MutationStatus.SUCCESS) {
            session.interactionLock.release();
            if (session.detached) {
                return;
            }
            // Failure responses still carry authoritative origin-entry reconciliation.
            // Redraw BAG/HOTBAR before closing or re-rendering the orb screen so a removed
            // orb cannot remain as a ghost Bukkit ItemStack.
            inventoryService.refreshManagedInventoryUi(session.astPlayer);
            if (result.status == MutationStatus.TARGET_UNAVAILABLE
                || result.status == MutationStatus.TARGET_CHANGED) {
                inventoryService.refreshEquipmentDisplaysForSave(session.astPlayer);
                if (statusService != null) {
                    statusService.refreshStatus(session.astPlayer);
                }
            }
            if (session.player.isOnline()) {
                PlayerMessageService.getInstance().send(
                    session.player,
                    switch (result.status) {
                        case NO_CANDIDATE -> PlayerMsgId.P_5294;
                        case PAYMENT_UNAVAILABLE -> session.screen == OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM
                            ? PlayerMsgId.P_5291
                            : PlayerMsgId.P_5289;
                        case TARGET_UNAVAILABLE, TARGET_CHANGED -> PlayerMsgId.P_5290;
                        default -> PlayerMsgId.P_5295;
                    }
                );
                GuiSound.DENY.play(session.player);
            }
            if (session.uiClosed) {
                sessions.remove(session.player.getUniqueId(), session);
                return;
            }
            refreshCurrentScreen(session);
            return;
        }

        useSuccessListener.accept(session.astPlayer, session.orbItemId);

        if (result.instance != null && session.player.isOnline()) {
            inventoryService.refreshManagedInventoryUi(session.astPlayer);
            inventoryService.refreshEquipmentInstanceDisplay(session.astPlayer, result.instance);
            if (statusService != null) {
                statusService.refreshStatus(session.astPlayer);
            }
        }
        if (session.uiClosed
            || !session.player.isOnline()
            || !session.reopening && !isCurrentInventory(session.player, session)) {
            if (session.player.isOnline()) {
                sendMutationResult(session.player, result);
            }
            sessions.remove(session.player.getUniqueId(), session);
            return;
        }
        finishSuccessfulMutation(session, result);
    }

    /**
     * API と正本照合が完了した成功結果を同じ tick で表示へ反映します。
     *
     * @param session 操作セッション
     * @param result 成功した装備処理結果
     */
    private void finishSuccessfulMutation(@NotNull OrbSession session, @NotNull MutationResult result) {
        if (session.detached
            || sessions.get(session.player.getUniqueId()) != session
            || !session.player.isOnline()) {
            sessions.remove(session.player.getUniqueId(), session);
            return;
        }
        if (!isCurrentInventory(session.player, session)) {
            if (session.reopening) {
                plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> finishSuccessfulMutation(session, result)
                );
                return;
            }
            sessions.remove(session.player.getUniqueId(), session);
            return;
        }
        sendMutationResult(session.player, result);
        if (result.kind == MutationKind.ENHANCEMENT && !result.enhancementSucceeded) {
            GuiSound.DENY.play(session.player);
        } else {
            GuiSound.SUCCESS.play(session.player);
        }

        ItemModel orbModel = result.kind == MutationKind.TRANSCENDENCE ? null : resolveCurrentOrb(session);
        if (shouldCloseAfterRefresh(result.kind, orbModel != null)) {
            closeAndRemove(session);
            return;
        }
        session.interactionLock.release();
        session.page = 0;
        ItemModel remainingOrb = Objects.requireNonNull(orbModel);
        renderList(session, remainingOrb, session.inventory, collectCandidates(session, remainingOrb));
    }

    /**
     * API 操作中であることを選択スロットの時計アイコンで一度だけ表示します。
     *
     * @param session 操作セッション
     */
    private void showProcessingIcon(@NotNull OrbSession session) {
        if (session.inventory == null
            || session.processingSlot < 0
            || session.processingSlot >= session.inventory.getSize()) {
            return;
        }
        session.inventory.setItem(session.processingSlot, GuiItems.create(
            PROCESSING_ICON,
            Component.text("装備を更新しています", NamedTextColor.LIGHT_PURPLE),
            List.of(Component.text("操作が完了するまでお待ちください。", NamedTextColor.GRAY))
        ));
    }

    /**
     * 失敗後に正本を再収集し、現在の一覧または状態変化確認画面を更新します。
     *
     * @param session 操作セッション
     */
    private void refreshCurrentScreen(@NotNull OrbSession session) {
        ItemModel orbModel = resolveCurrentOrb(session);
        if (orbModel == null) {
            closeAndRemove(session);
            return;
        }
        if (session.screen == OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM
            && session.selectedTargetId != null) {
            OrbCandidate target = collectCandidates(session, orbModel).stream()
                .filter(candidate -> candidate.instance.getEquipmentInstanceId()
                    .equalsIgnoreCase(session.selectedTargetId))
                .findFirst()
                .orElse(null);
            if (target == null) {
                closeAndRemove(session);
                return;
            }
            renderTranscendenceConfirmation(session, orbModel, target, session.inventory);
            return;
        }
        renderList(session, orbModel, session.inventory, collectCandidates(session, orbModel));
    }

    /**
     * 効果種別と結果に対応するresourceメッセージを送信します。
     *
     * @param player 送信先プレイヤー
     * @param result 成功した装備処理結果
     */
    private void sendMutationResult(@NotNull Player player, @NotNull MutationResult result) {
        if (result.model == null || result.instance == null || result.kind == null) {
            return;
        }
        String displayName = result.model.getName() == null || result.model.getName().isBlank()
            ? "装備"
            : result.model.getName();
        switch (result.kind) {
            case ENHANCEMENT -> {
                if (result.enhancementSucceeded) {
                    PlayerMessageService.getInstance().send(
                        player,
                        PlayerMsgId.P_5257,
                        displayName,
                        result.instance.getEnhanceLevel(),
                        formatPercent(result.successRate)
                    );
                } else if (result.failAction == ItemEquipmentEnhanceFailAction.NONE) {
                    PlayerMessageService.getInstance().send(
                        player,
                        PlayerMsgId.P_5258,
                        displayName,
                        formatPercent(result.successRate)
                    );
                } else {
                    PlayerMessageService.getInstance().send(
                        player,
                        PlayerMsgId.P_5259,
                        displayName,
                        result.instance.getEnhanceLevel(),
                        formatPercent(result.successRate)
                    );
                }
            }
            case REPAIR -> PlayerMessageService.getInstance().send(
                player,
                PlayerMsgId.P_5292,
                displayName,
                result.instance.getDurabilityValue(),
                result.instance.getDurabilityMax()
            );
            case ENCHANT -> PlayerMessageService.getInstance().send(
                player, PlayerMsgId.P_5293, displayName);
            case RUNE -> { }
            case TRANSCENDENCE -> PlayerMessageService.getInstance().send(
                player, PlayerMsgId.P_5287, displayName, result.transitionName);
        }
    }

    /**
     * 現在画面であることを先に判定してtask・session・GUIを終了します。
     *
     * @param session 終了する操作セッション
     */
    private void closeAndRemove(@NotNull OrbSession session) {
        boolean shouldClose = isCurrentInventory(session.player, session);
        sessions.remove(session.player.getUniqueId(), session);
        cancelReopenTask(session);
        session.interactionLock.close();
        if (shouldClose) {
            session.player.closeInventory();
        }
    }

    /**
     * holderの所有者・世代tokenとmemory上の現在セッションを照合します。
     *
     * @param player 操作プレイヤー
     * @param inventory 操作対象GUI
     * @return 同じ世代のセッション。不一致なら {@code null}
     */
    private @Nullable OrbSession currentSession(@NotNull Player player, @NotNull Inventory inventory) {
        if (!(inventory.getHolder() instanceof OrbGuiHolder holder)
            || !holder.ownerId().equals(player.getUniqueId())) {
            return null;
        }
        OrbSession session = sessions.get(player.getUniqueId());
        return session != null && session.token.equals(holder.sessionToken()) ? session : null;
    }

    /**
     * プレイヤーが同じ世代のオーブGUIを現在開いているか判定します。
     *
     * @param player 操作プレイヤー
     * @param session 比較セッション
     * @return 現在画面なら {@code true}
     */
    private boolean isCurrentInventory(@NotNull Player player, @NotNull OrbSession session) {
        return currentSession(player, player.getOpenInventory().getTopInventory()) == session;
    }

    /**
     * Bukkit task と表示だけを切り離します。operation 自体が account save lane 内にあるため、
     * logout save はその後ろで待機し、同じ lane へ待機 job を再登録しません。
     */
    private void detachForSave(@NotNull OrbSession session) {
        session.detached = true;
        session.uiClosed = true;
        cancelReopenTask(session);
        session.interactionLock.close();
    }

    /**
     * 既存の非通信セッションを無効化して表示taskを止めます。
     *
     * @param playerId 対象プレイヤーUUID
     */
    private void removeSession(@NotNull UUID playerId) {
        OrbSession previous = sessions.remove(playerId);
        if (previous != null) {
            previous.detached = true;
            cancelReopenTask(previous);
            previous.interactionLock.close();
        }
    }

    /** Escape close 後の再表示 task を取消します。 */
    private void cancelReopenTask(@NotNull OrbSession session) {
        BukkitTask task = session.reopenTask;
        session.reopenTask = null;
        session.reopening = false;
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 結果反映時にGUIを閉じるべきか判定します。
     *
     * @param kind 実行したオーブ効果
     * @param hasRemainingOrb 同じオーブをまだ所持している場合 {@code true}
     * @return 状態変化・ルーン操作、または残オーブなしなら {@code true}
     */
    static boolean shouldCloseAfterRefresh(@NotNull MutationKind kind, boolean hasRemainingOrb) {
        return kind == MutationKind.TRANSCENDENCE || kind == MutationKind.RUNE || !hasRemainingOrb;
    }

    enum MutationStatus {
        SUCCESS,
        NO_CANDIDATE,
        PAYMENT_UNAVAILABLE,
        TARGET_UNAVAILABLE,
        TARGET_CHANGED,
        FAILED,
    }

    enum MutationKind {
        ENHANCEMENT,
        REPAIR,
        ENCHANT,
        RUNE,
        TRANSCENDENCE,
    }

    @FunctionalInterface
    interface OrbInventoryOpener {
        void open(
            @NotNull Player player,
            @NotNull Inventory inventory,
            @NotNull Runnable onOpened,
            @NotNull Runnable onCancelled
        );
    }

    /** API台帳の再照会backoffをテストから制御する境界です。 */
    @FunctionalInterface
    interface OrbRetryWaiter {
        void await(@NotNull UUID operationId, long delayMillis);
    }

    private record MutationResult(
        @NotNull MutationStatus status,
        @Nullable MutationKind kind,
        @Nullable ItemModel model,
        @Nullable EquipmentInstance instance,
        boolean enhancementSucceeded,
        @Nullable ItemEquipmentEnhanceFailAction failAction,
        double successRate,
        int repairedAmount,
        @Nullable String transitionName
    ) {

        /**
         * 装備内容を持たない失敗結果を作成します。
         *
         * @param status 失敗区分
         * @return 失敗結果
         */
        private static @NotNull MutationResult failed(@NotNull MutationStatus status) {
            return new MutationResult(status, null, null, null, false, null, 0.0D, 0, null);
        }

        /**
         * 強化抽選の確定結果を作成します。
         *
         * @param model 装備マスタ
         * @param instance 更新後装備個体
         * @param succeeded 抽選成功時 {@code true}
         * @param failAction 失敗時動作
         * @param successRate 使用した成功率
         * @return 強化結果
         */
        private static @NotNull MutationResult enhancement(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance,
            boolean succeeded,
            @NotNull ItemEquipmentEnhanceFailAction failAction,
            double successRate
        ) {
            return new MutationResult(
                MutationStatus.SUCCESS,
                MutationKind.ENHANCEMENT,
                model,
                instance,
                succeeded,
                failAction,
                successRate,
                0,
                null
            );
        }

        /**
         * 耐久回復の確定結果を作成します。
         *
         * @param model 装備マスタ
         * @param instance 更新後装備個体
         * @param repairedAmount 回復量
         * @return 修理結果
         */
        private static @NotNull MutationResult repair(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance,
            int repairedAmount
        ) {
            return new MutationResult(
                MutationStatus.SUCCESS,
                MutationKind.REPAIR,
                model,
                instance,
                false,
                null,
                0.0D,
                repairedAmount,
                null
            );
        }

        /**
         * エンチャント適用の確定結果を作成します。
         *
         * @param model 装備マスタ
         * @param instance 更新後装備個体
         * @return エンチャント結果
         */
        private static @NotNull MutationResult enchant(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance
        ) {
            return new MutationResult(
                MutationStatus.SUCCESS,
                MutationKind.ENCHANT,
                model,
                instance,
                false,
                null,
                0.0D,
                0,
                null
            );
        }

        private static @NotNull MutationResult rune(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance
        ) {
            return new MutationResult(
                MutationStatus.SUCCESS, MutationKind.RUNE, model, instance,
                false, null, 0.0D, 0, null
            );
        }

        /**
         * 状態変化の確定結果を作成します。
         *
         * @param model 装備マスタ
         * @param instance 更新後装備個体
         * @param transitionName 状態変化名
         * @return 状態変化結果
         */
        private static @NotNull MutationResult transcendence(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance,
            @NotNull String transitionName
        ) {
            return new MutationResult(
                MutationStatus.SUCCESS,
                MutationKind.TRANSCENDENCE,
                model,
                instance,
                false,
                null,
                0.0D,
                0,
                transitionName
            );
        }
    }

    private record OrbCandidate(
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        boolean equipped
    ) {
    }

    private record OrbInventoryEntry(
        @NotNull String itemId,
        @NotNull ItemModel model,
        long quantity
    ) {
    }

    private static final class OrbInventoryListSession {
        private final AstPlayer astPlayer;
        private final UUID accountId;
        private final UUID token;
        private final Inventory inventory;
        private Map<Integer, String> displayedOrbItemIds = Map.of();
        private int page;

        private OrbInventoryListSession(
            @NotNull AstPlayer astPlayer,
            @NotNull UUID accountId,
            @NotNull UUID token,
            @NotNull Inventory inventory
        ) {
            this.astPlayer = astPlayer;
            this.accountId = accountId;
            this.token = token;
            this.inventory = inventory;
        }
    }

    private static final class OrbSession {
        private final Player player;
        private final AstPlayer astPlayer;
        private final UUID accountId;
        private final UUID token;
        private UUID orbEntryId;
        private final String orbItemId;
        private final boolean returnToInventoryOrbListOnFailure;
        private OrbGuiHolder.Screen screen = OrbGuiHolder.Screen.LIST;
        private Inventory inventory;
        private Map<Integer, String> displayedTargets = Map.of();
        private int page;
        private int materialPage;
        private String selectedTargetId;
        private String selectedRuneItemId;
        private int selectedRuneSlot = -1;
        private int runePage;
        private final OrbInteractionLock interactionLock = new OrbInteractionLock();
        private boolean transitioning;
        private boolean uiClosed;
        private boolean reopening;
        private boolean detached;
        private UUID operationId;
        private volatile boolean externalOperationStarted;
        private int processingSlot = -1;
        private CompletableFuture<ItemService.EquipmentPreloadResult> preloadFuture;
        private CompletableFuture<MutationResult> operationFuture;
        private BukkitTask reopenTask;

        /**
         * 通常インベントリで検証したオーブentryを新しい世代へ固定します。
         *
         * @param player 操作プレイヤー
         * @param astPlayer 操作中ログイン世代
         * @param accountId アカウントID
         * @param token GUI世代token
         * @param orbEntryId 共通消費順で直近に解決したオーブentry ID
         * @param orbItemId 起点オーブitem ID
         * @param returnToInventoryOrbListOnFailure 対象装備がない場合に所持オーブ一覧へ戻すか
         */
        private OrbSession(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull UUID accountId,
            @NotNull UUID token,
            @NotNull UUID orbEntryId,
            @NotNull String orbItemId,
            boolean returnToInventoryOrbListOnFailure
        ) {
            this.player = player;
            this.astPlayer = astPlayer;
            this.accountId = accountId;
            this.token = token;
            this.orbEntryId = orbEntryId;
            this.orbItemId = orbItemId;
            this.returnToInventoryOrbListOnFailure = returnToInventoryOrbListOnFailure;
        }
    }
}
