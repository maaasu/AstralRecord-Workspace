package io.github.maaasu.astralRecord.feature.playersetting.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.playersetting.model.DamageNumberSize;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingGuiSaveService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤー設定 GUI のイベント処理です。
 */
public final class PlayerSettingGuiEventHandler extends AbstractEventHandler {
    private static final int SUPER_MODE_TOGGLE_CLICK_COUNT = 5;

    private final PlayerSettingGui gui;
    private final PlayerSettingService playerSettingService;
    private final PlayerSettingGuiSaveService guiSaveService;
    private final InventoryService inventoryService;
    private final ConcurrentHashMap<UUID, Integer> secretClickCounts = new ConcurrentHashMap<>();
    private final Map<Inventory, EnumMap<PlayerSettingKey, Object>> draftValues = new IdentityHashMap<>();

    /**
     * プレイヤー設定 GUI の操作と保存後表示同期を行うハンドラを初期化します。
     *
     * @param gui プレイヤー設定 GUI
     * @param playerSettingService プレイヤー設定サービス
     * @param guiSaveService GUI の設定保存サービス
     * @param inventoryService hotbar shortcut 用 inventory サービス
     */
    public PlayerSettingGuiEventHandler(
        @NotNull PlayerSettingGui gui,
        @NotNull PlayerSettingService playerSettingService,
        @NotNull PlayerSettingGuiSaveService guiSaveService,
        @NotNull InventoryService inventoryService
    ) {
        this.gui = gui;
        this.playerSettingService = playerSettingService;
        this.guiSaveService = guiSaveService;
        this.inventoryService = inventoryService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            if (!(event.getWhoClicked() instanceof Player player)) {
                event.setCancelled(true);
                return;
            }
            if (handleHotbarShortcutClick(event, player)) {
                return;
            }
            event.setCancelled(true);
            handleClick(player, event.getView().getTopInventory(), event.getRawSlot());
        }, LogId.E_5313, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5313, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getInventory())) {
                return;
            }
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            persistDraftChanges(player, event.getInventory(), draftValues.remove(event.getInventory()));
            secretClickCounts.remove(player.getUniqueId());
        }, LogId.E_5313, event.getPlayer().getName());
    }

    private boolean handleHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        return HotbarShortcutClickSupport.handle(event, player, inventoryService);
    }

    /**
     * 設定の変更を実行する前に、画面の所有者と接続セッションを照合します。
     * @param player 操作中のプレイヤー
     * @param inventory 操作対象の設定画面
     * @param rawSlot 操作されたスロット
     */
    private void handleClick(@NotNull Player player, @NotNull Inventory inventory, int rawSlot) {
        UUID userId = gui.getUserId(inventory);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        long sessionToken = gui.getSessionToken(inventory);
        if (astPlayer == null || !astPlayer.getUser().getUuid().equals(userId) || sessionToken == 0L
            || playerSettingService.captureSessionToken(userId) != sessionToken) {
            return;
        }
        boolean screenHud = gui.isScreenHudInventory(inventory);
        if (rawSlot == PlayerSettingGui.BACK_TO_MENU_SLOT) {
            secretClickCounts.remove(player.getUniqueId());
            GuiSound.SELECT.play(player);
            AstralRecord.getInstance().getGuiNavigationService().openPrevious(player, () -> refreshInventory(player));
            return;
        }
        if (!screenHud && rawSlot == PlayerSettingGui.SCREEN_HUD_SETTINGS_SLOT) {
            secretClickCounts.remove(player.getUniqueId());
            GuiSound.SELECT.play(player);
            gui.openScreenHud(player);
            return;
        }
        if (!screenHud && rawSlot == PlayerSettingGui.SUPER_MODE_SECRET_SLOT) {
            handleSuperModeSecretClick(player, inventory, userId);
            return;
        }
        PlayerSettingKey key = gui.getKeyAtSlot(inventory, rawSlot);
        if (key == null || !gui.canEdit(astPlayer, key)) {
            secretClickCounts.remove(player.getUniqueId());
            GuiSound.DENY.play(player);
            return;
        }

        Object currentValue = currentValue(inventory, userId, key);
        Object nextValue = nextValue(key, currentValue);
        draftValues
            .computeIfAbsent(inventory, ignored -> new EnumMap<>(PlayerSettingKey.class))
            .put(key, nextValue);
        GuiSound.TOGGLE.play(player);
        refreshInventory(player);
    }

    /**
     * @param player 操作中のプレイヤー
     * @param inventory 秘密スロットのある通常の設定画面
     * @param userId 設定の所有者
     */
    private void handleSuperModeSecretClick(@NotNull Player player, @NotNull Inventory inventory, @NotNull UUID userId) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.hasAdminPermission()) {
            secretClickCounts.remove(player.getUniqueId());
            Map<PlayerSettingKey, Object> draft = draftValues.get(inventory);
            if (draft != null) {
                draft.remove(PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE);
            }
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5061);
            GuiSound.DENY.play(player);
            return;
        }
        int count = secretClickCounts.merge(player.getUniqueId(), 1, Integer::sum);
        if (count < SUPER_MODE_TOGGLE_CLICK_COUNT) {
            GuiSound.SELECT.play(player);
            return;
        }

        secretClickCounts.remove(player.getUniqueId());
        PlayerSettingKey key = PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE;
        Object currentValue = currentValue(inventory, userId, key);
        Object nextValue = !(currentValue instanceof Boolean enabled && enabled);
        draftValues
            .computeIfAbsent(inventory, ignored -> new EnumMap<>(PlayerSettingKey.class))
            .put(key, nextValue);
        GuiSound.TOGGLE.play(player);
        refreshInventory(player);
    }

    /**
     * @param inventory 対象の設定画面
     * @param userId 設定の所有者
     * @param key 参照する設定
     * @return 下書きがあればその値、なければ保存済みの値
     */
    private @NotNull Object currentValue(@NotNull Inventory inventory, @NotNull UUID userId, @NotNull PlayerSettingKey key) {
        Map<PlayerSettingKey, Object> values = draftValues.get(inventory);
        if (values != null && values.containsKey(key)) {
            return values.get(key);
        }
        return playerSettingService.getPlayerSetting(userId, key);
    }

    private void refreshInventory(@NotNull Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        UUID userId = gui.getUserId(inventory);
        if (userId == null) {
            return;
        }
        gui.refresh(inventory, userId, draftValues.get(inventory));
        player.updateInventory();
    }

    /**
     * 画面を開いた時点のユーザーとセッションに対してだけ下書きを保存します。
     * @param player 設定画面を閉じたプレイヤー
     * @param inventory 閉じた設定画面
     * @param pending その画面から取り出した変更候補
     */
    private void persistDraftChanges(
        @NotNull Player player, @NotNull Inventory inventory, Map<PlayerSettingKey, Object> pending
    ) {
        UUID userId = gui.getUserId(inventory);
        if (userId != null && pending != null && !pending.isEmpty()) {
            guiSaveService.save(player, userId, gui.getSessionToken(inventory), pending);
        }
    }

    /**
     * クリック後の下書き値を求めます。選択式の値は末尾から先頭へ戻ります。
     *
     * @param key クリックされた設定キー
     * @param currentValue キーに対応する型の現在値
     * @return 次の値。GUIで切替対象外のキーなら現在値
     */
    private @NotNull Object nextValue(@NotNull PlayerSettingKey key, @NotNull Object currentValue) {
        if (key.isBooleanValue()) {
            return !((Boolean) currentValue);
        }
        if (key == PlayerSettingKey.DAMAGE_NUMBER_SIZE) {
            DamageNumberSize[] values = DamageNumberSize.values();
            DamageNumberSize current = (DamageNumberSize) currentValue;
            return values[(current.ordinal() + 1) % values.length];
        }
        if (key.isParticleDensityValue()) {
            ParticleDensity[] values = ParticleDensity.values();
            ParticleDensity current = (ParticleDensity) currentValue;
            int nextIndex = (current.ordinal() + 1) % values.length;
            return values[nextIndex];
        }
        return currentValue;
    }

}
