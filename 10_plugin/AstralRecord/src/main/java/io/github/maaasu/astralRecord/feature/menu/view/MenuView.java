package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.view.CurrencyGuiView;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BuffScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.ClassScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.GuideScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.MainMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.SellConfirmScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.StatusScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashConfirmScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashScreenView;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassViewEntry;
import io.github.maaasu.astralRecord.feature.sell.view.SellScreenView;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
import io.github.maaasu.astralRecord.feature.storage.view.StorageScreenView;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class MenuView {
    public static final int SIZE = BaseMenuScreenView.SIZE;
    public static final int CLOSE_SLOT = BaseMenuScreenView.CLOSE_SLOT;
    public static final int BACK_SLOT = BaseMenuScreenView.BACK_SLOT;
    public static final int STATUS_SLOT = MainMenuScreenView.STATUS_SLOT;
    public static final int PLAYER_SETTING_SLOT = MainMenuScreenView.PLAYER_SETTING_SLOT;
    public static final int EQUIPMENT_GUI_SLOT = MainMenuScreenView.EQUIPMENT_GUI_SLOT;
    public static final int TRASH_SLOT = MainMenuScreenView.TRASH_SLOT;
    public static final int GUIDE_SLOT = MainMenuScreenView.GUIDE_SLOT;
    public static final int RETURN_TO_BASE_SLOT = MainMenuScreenView.RETURN_TO_BASE_SLOT;
    public static final int BUFF_SLOT = MainMenuScreenView.BUFF_SLOT;
    public static final int SKILL_BIND_SLOT = MainMenuScreenView.SKILL_BIND_SLOT;
    public static final int CURRENCY_SLOT = MainMenuScreenView.CURRENCY_SLOT;
    public static final int PARTY_SLOT = MainMenuScreenView.PARTY_SLOT;
    public static final int PLAYER_INFO_SLOT = MainMenuScreenView.PLAYER_INFO_SLOT;
    public static final int ADVENTURE_RECORD_SLOT = MainMenuScreenView.ADVENTURE_RECORD_SLOT;
    public static final int MAIL_SLOT = MainMenuScreenView.MAIL_SLOT;
    public static final int EQUIPMENT_HEAD_SLOT = EquipmentMenuScreenView.EQUIPMENT_HEAD_SLOT;
    public static final int EQUIPMENT_CHEST_SLOT = EquipmentMenuScreenView.EQUIPMENT_CHEST_SLOT;
    public static final int EQUIPMENT_LEGS_SLOT = EquipmentMenuScreenView.EQUIPMENT_LEGS_SLOT;
    public static final int EQUIPMENT_FEET_SLOT = EquipmentMenuScreenView.EQUIPMENT_FEET_SLOT;
    public static final int EQUIPMENT_OFF_HAND_SLOT = EquipmentMenuScreenView.EQUIPMENT_OFF_HAND_SLOT;
    public static final int EQUIPMENT_ACCESSORY_2_SLOT = EquipmentMenuScreenView.EQUIPMENT_ACCESSORY_2_SLOT;
    public static final int EQUIPMENT_ACCESSORY_3_SLOT = EquipmentMenuScreenView.EQUIPMENT_ACCESSORY_3_SLOT;
    public static final int EQUIPMENT_ACCESSORY_4_SLOT = EquipmentMenuScreenView.EQUIPMENT_ACCESSORY_4_SLOT;
    public static final int EQUIPMENT_ACCESSORY_5_SLOT = EquipmentMenuScreenView.EQUIPMENT_ACCESSORY_5_SLOT;
    public static final int EQUIPMENT_ACCESSORY_6_SLOT = EquipmentMenuScreenView.EQUIPMENT_ACCESSORY_6_SLOT;
    public static final int EQUIPMENT_ACCESSORY_7_SLOT = EquipmentMenuScreenView.EQUIPMENT_ACCESSORY_7_SLOT;
    public static final int PAGING_PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int PAGING_BACK_SLOT = PagedGuiView.BACK_SLOT;
    public static final int PAGING_CLOSE_SLOT = PagedGuiView.CLOSE_SLOT;
    public static final int PAGING_NEXT_SLOT = PagedGuiView.NEXT_SLOT;
    public static final int TRASH_PREVIOUS_SLOT = TrashScreenView.PREVIOUS_SLOT;
    public static final int TRASH_GUIDE_SLOT = TrashScreenView.GUIDE_SLOT;
    public static final int TRASH_CLOSE_SLOT = TrashScreenView.CLOSE_SLOT;
    public static final int TRASH_NEXT_SLOT = TrashScreenView.NEXT_SLOT;
    public static final int TRASH_CONFIRM_PREVIOUS_SLOT = TrashConfirmScreenView.PREVIOUS_SLOT;
    public static final int TRASH_CONFIRM_DISPOSE_SLOT = TrashConfirmScreenView.DISPOSE_SLOT;
    public static final int TRASH_CONFIRM_RETURN_SLOT = TrashConfirmScreenView.RETURN_TO_TRASH_SLOT;
    public static final int TRASH_CONFIRM_NEXT_SLOT = TrashConfirmScreenView.NEXT_SLOT;
    public static final int STORAGE_PREVIOUS_SLOT = StorageScreenView.PREVIOUS_SLOT;
    public static final int STORAGE_CATEGORY_FILTER_SLOT = StorageScreenView.CATEGORY_FILTER_SLOT;
    public static final int STORAGE_RARITY_FILTER_SLOT = StorageScreenView.RARITY_FILTER_SLOT;
    public static final int STORAGE_SORT_KEY_SLOT = StorageScreenView.SORT_KEY_SLOT;
    public static final int STORAGE_SORT_DIRECTION_SLOT = StorageScreenView.SORT_DIRECTION_SLOT;
    public static final int STORAGE_GUIDE_SLOT = StorageScreenView.GUIDE_SLOT;
    public static final int STORAGE_NEXT_SLOT = StorageScreenView.NEXT_SLOT;
    public static final int CRAFT_RESULT_RAW_SLOT = CraftShortcutView.CRAFT_RESULT_RAW_SLOT;
    public static final int CRAFT_SHORTCUT_RAW_SLOT_START = CraftShortcutView.CRAFT_SHORTCUT_RAW_SLOT_START;

    private static final Component MAIN_TITLE = Component.text("AstralRecord メニュー", NamedTextColor.DARK_AQUA);
    private static final Component ACCOUNT_INFO_TITLE = Component.text("アカウント情報", NamedTextColor.GOLD);
    private static final Component EQUIPMENT_TITLE = Component.text("装備", NamedTextColor.GOLD);
    private static final Component BUFF_TITLE = Component.text("バフ", NamedTextColor.AQUA);
    private static final String CURRENCY_TITLE = "通貨";

    private final MainMenuScreenView mainMenuScreenView;
    private final StatusScreenView statusScreenView;
    private final EquipmentMenuScreenView equipmentMenuScreenView;
    private final BuffScreenView buffScreenView;
    private final ClassScreenView classScreenView;
    private final CurrencyGuiView currencyGuiView;
    private final GuideScreenView guideScreenView;
    private final TrashScreenView trashScreenView;
    private final TrashConfirmScreenView trashConfirmScreenView;
    private final SellScreenView sellScreenView;
    private final SellConfirmScreenView sellConfirmScreenView;
    private final StorageScreenView storageScreenView;
    private final CraftShortcutView craftShortcutView;
    private final AstralRecord plugin;

    public MenuView(@NotNull AstralRecord plugin) {
        this.plugin = plugin;
        NamespacedKey craftShortcutKey = new NamespacedKey(plugin, "menu_shortcut_slot");
        NamespacedKey craftActionKey = new NamespacedKey(plugin, "menu_shortcut_action");
        NamespacedKey equipmentPlaceholderKey = new NamespacedKey(plugin, "equipment_placeholder");
        NamespacedKey classIdKey = new NamespacedKey(plugin, "menu_class_id");
        NamespacedKey trashPlaceholderKey = new NamespacedKey(plugin, "trash_content_placeholder");
        NamespacedKey sellPlaceholderKey = new NamespacedKey(plugin, "sell_content_placeholder");
        NamespacedKey storagePlaceholderKey = new NamespacedKey(plugin, "storage_content_placeholder");
        NamespacedKey storageEntryIdKey = new NamespacedKey(plugin, "storage_entry_id");
        ItemService itemService = plugin.getItemService();
        this.mainMenuScreenView = new MainMenuScreenView();
        this.statusScreenView = new StatusScreenView();
        this.equipmentMenuScreenView = new EquipmentMenuScreenView(equipmentPlaceholderKey);
        this.buffScreenView = new BuffScreenView();
        this.classScreenView = new ClassScreenView(classIdKey);
        this.currencyGuiView = new CurrencyGuiView();
        this.guideScreenView = new GuideScreenView();
        this.trashScreenView = new TrashScreenView(trashPlaceholderKey);
        this.trashConfirmScreenView = new TrashConfirmScreenView(trashPlaceholderKey);
        this.sellScreenView = new SellScreenView(sellPlaceholderKey, itemService);
        this.sellConfirmScreenView = new SellConfirmScreenView(sellPlaceholderKey);
        this.storageScreenView = new StorageScreenView(storagePlaceholderKey, storageEntryIdKey);
        this.craftShortcutView = new CraftShortcutView(craftShortcutKey, craftActionKey);
    }

    public void open(@NotNull Player player) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.MAIN), SIZE, MAIN_TITLE);
        mainMenuScreenView.render(inventory, player, plugin.getCurrencyService().getGoldAmount(player), activeBuffNames(player));
        player.openInventory(inventory);
    }

    public void openStatus(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull StatusSnapshot snapshot) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.STATUS), SIZE, ACCOUNT_INFO_TITLE);
        statusScreenView.render(inventory, astPlayer, snapshot);
        player.openInventory(inventory);
    }

    public void openEquipmentGui(@NotNull Player player) {
        openEquipmentGui(player, new ItemStack[0]);
    }

    public void openEquipmentGui(@NotNull Player player, @NotNull ItemStack[] accessories) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.EQUIPMENT_GUI), SIZE, EQUIPMENT_TITLE);
        equipmentMenuScreenView.render(inventory, player, accessories);
        player.openInventory(inventory);
    }

    public void openBuff(@NotNull Player player, @NotNull List<ActiveBuff> activeBuffs) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.BUFF), SIZE, BUFF_TITLE);
        buffScreenView.render(inventory, activeBuffs);
        player.openInventory(inventory);
    }

    /**
     * バフ一覧 GUI の内容を再描画します。
     *
     * @param inventory   再描画するインベントリ
     * @param activeBuffs 現在有効なバフ一覧
     */
    public void renderBuff(@NotNull Inventory inventory, @NotNull List<ActiveBuff> activeBuffs) {
        buffScreenView.render(inventory, activeBuffs);
    }

    public void openClass(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull List<ClassViewEntry> classes) {
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.CLASS),
            SIZE,
            Component.text("クラス", NamedTextColor.YELLOW)
        );
        classScreenView.render(inventory, astPlayer, classes);
        player.openInventory(inventory);
    }

    public void openCurrency(@NotNull Player player, @NotNull List<ItemStack> currencyItems, int pageIndex) {
        int normalizedPage = currencyGuiView.normalizePage(pageIndex, currencyItems.size());
        int totalPages = currencyGuiView.totalPages(currencyItems.size());
        Component title = Component.text(CURRENCY_TITLE + " " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.GOLD);
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.CURRENCY, -1, normalizedPage), PagedGuiView.SIZE, title);
        currencyGuiView.render(inventory, currencyItems, normalizedPage);
        player.openInventory(inventory);
    }

    public void openGuide(@NotNull Player player) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.GUIDE), SIZE, Component.text("ガイド", NamedTextColor.LIGHT_PURPLE));
        guideScreenView.render(inventory);
        player.openInventory(inventory);
    }

    public void openTrash(@NotNull Player player, @NotNull List<ItemStack> trashItems, int pageIndex) {
        int normalizedPage = trashScreenView.normalizePage(pageIndex, trashItems.size());
        int totalPages = trashScreenView.totalPages(trashItems.size());
        Component title = Component.text("ゴミ箱 " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.RED);
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.TRASH, -1, normalizedPage), SIZE, title);
        trashScreenView.render(inventory, trashItems, normalizedPage);
        player.openInventory(inventory);
    }

    public void renderTrash(@NotNull Inventory inventory, @NotNull List<ItemStack> trashItems, int pageIndex) {
        trashScreenView.render(inventory, trashItems, pageIndex);
    }

    public void openTrashConfirm(@NotNull Player player, @NotNull List<ItemStack> trashItems, int pageIndex) {
        Component title = TrashConfirmScreenView.CONFIRM_MESSAGE;
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.TRASH_CONFIRM, -1, 0),
            io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView.SIZE,
            title
        );
        trashConfirmScreenView.render(inventory, trashItems, 0);
        player.openInventory(inventory);
    }

    public void openSell(@NotNull Player player, @NotNull List<ItemStack> sellItems, int pageIndex) {
        int normalizedPage = sellScreenView.normalizePage(pageIndex, sellItems.size());
        int totalPages = sellScreenView.totalPages(sellItems.size());
        Component title = Component.text("売却 " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.GOLD);
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.SELL, -1, normalizedPage), SIZE, title);
        sellScreenView.render(inventory, sellItems, normalizedPage);
        player.openInventory(inventory);
    }

    public void openSellConfirm(@NotNull Player player, @NotNull List<ItemStack> sellItems, int pageIndex) {
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.SELL_CONFIRM, -1, 0),
            io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView.SIZE,
            SellConfirmScreenView.CONFIRM_MESSAGE
        );
        sellConfirmScreenView.render(inventory, sellItems, 0);
        player.openInventory(inventory);
    }

    public void openStorage(
        @NotNull Player player,
        @NotNull List<StorageViewEntry> storageItems,
        @NotNull StorageViewOptions options,
        int pageIndex
    ) {
        int normalizedPage = storageScreenView.normalizePage(pageIndex, storageItems.size());
        int totalPages = storageScreenView.totalPages(storageItems.size());
        Component title = Component.text("ストレージ " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.GOLD);
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.STORAGE, -1, normalizedPage), SIZE, title);
        storageScreenView.render(inventory, storageItems, options, normalizedPage);
        player.openInventory(inventory);
    }

    public void renderStorage(
        @NotNull Inventory inventory,
        @NotNull List<StorageViewEntry> storageItems,
        @NotNull StorageViewOptions options,
        int pageIndex
    ) {
        storageScreenView.render(inventory, storageItems, options, pageIndex);
    }

    public @NotNull ItemStack createCraftResultIcon() {
        return craftShortcutView.createCraftResultIcon();
    }

    public void renderCraftShortcuts(
        @NotNull Player player,
        @NotNull MenuShortcutSettings settings,
        @Nullable InventoryType selectedType,
        @Nullable StatusSnapshot snapshot,
        @NotNull AccountModel selectedAccount,
        int skillPoints,
        @NotNull List<AccountModel> accounts
    ) {
        craftShortcutView.renderCraftShortcuts(
            player,
            settings,
            selectedType,
            snapshot,
            selectedAccount,
            skillPoints,
            accounts
        );
    }

    public void clearCraftShortcuts(@NotNull Player player) {
        craftShortcutView.clearCraftShortcuts(player);
    }

    public void clearCraftShortcuts(@NotNull CraftingInventory inventory) {
        craftShortcutView.clearCraftShortcuts(inventory);
    }

    public void removeCraftShortcutItems(@NotNull Player player) {
        craftShortcutView.removeCraftShortcutItems(player);
    }

    public boolean isMenuInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof MenuInventoryHolder;
    }

    public @Nullable MenuScreen getMenuScreen(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof MenuInventoryHolder holder) {
            return holder.screen();
        }
        return null;
    }

    public int getPageIndex(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof MenuInventoryHolder holder) {
            return holder.pageIndex();
        }
        return 0;
    }

    public @Nullable EquipmentType getEquipmentTypeAtSlot(int rawSlot) {
        return equipmentMenuScreenView.getEquipmentTypeAtSlot(rawSlot);
    }

    public boolean isExtendedAccessorySlot(int rawSlot) {
        return equipmentMenuScreenView.isExtendedAccessorySlot(rawSlot);
    }

    public boolean isEquipmentItemSlot(int rawSlot) {
        return equipmentMenuScreenView.isEquipmentItemSlot(rawSlot);
    }

    public @Nullable ItemStack getEquipmentGuiItem(@NotNull Inventory inventory, int slot) {
        return equipmentMenuScreenView.getEquipmentGuiItem(inventory, slot);
    }

    public @Nullable ItemStack getEquipmentSlotPlaceholder(int slot) {
        return equipmentMenuScreenView.createPlaceholderForSlot(slot);
    }

    public int getSlotForEquipmentType(@NotNull EquipmentType equipmentType) {
        return equipmentMenuScreenView.getSlotForEquipmentType(equipmentType);
    }

    public int firstEmptyAccessorySlot(@NotNull Inventory inventory) {
        return equipmentMenuScreenView.firstEmptyAccessorySlot(inventory);
    }

    public @NotNull ItemStack[] getAccessoryItems(@NotNull Inventory inventory) {
        return equipmentMenuScreenView.getAccessoryItems(inventory);
    }

    public int getCraftShortcutIndex(int rawSlot) {
        return craftShortcutView.getCraftShortcutIndex(rawSlot);
    }

    public boolean hasPreviousCurrencyPage(int pageIndex) {
        return currencyGuiView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextCurrencyPage(@NotNull List<ItemStack> currencyItems, int pageIndex) {
        return currencyGuiView.hasNextPage(pageIndex, currencyItems.size());
    }

    public boolean hasPreviousTrashPage(int pageIndex) {
        return trashScreenView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextTrashPage(@NotNull List<ItemStack> trashItems, int pageIndex) {
        return trashScreenView.hasNextPage(pageIndex, trashItems.size());
    }

    public boolean hasPreviousTrashConfirmPage(int pageIndex) {
        return trashConfirmScreenView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextTrashConfirmPage(@NotNull List<ItemStack> trashItems, int pageIndex) {
        return trashConfirmScreenView.hasNextPage(pageIndex, trashItems.size());
    }

    public boolean hasPreviousSellPage(int pageIndex) {
        return sellScreenView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextSellPage(@NotNull List<ItemStack> sellItems, int pageIndex) {
        return sellScreenView.hasNextPage(pageIndex, sellItems.size());
    }

    public boolean hasPreviousStoragePage(int pageIndex) {
        return storageScreenView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextStoragePage(@NotNull List<StorageViewEntry> storageItems, int pageIndex) {
        return storageScreenView.hasNextPage(pageIndex, storageItems.size());
    }

    public boolean isTrashContentPlaceholder(@Nullable ItemStack itemStack) {
        return trashScreenView.isContentPlaceholder(itemStack);
    }

    public boolean isTrashConfirmContentPlaceholder(@Nullable ItemStack itemStack) {
        return trashConfirmScreenView.isContentPlaceholder(itemStack);
    }

    public boolean isSellContentPlaceholder(@Nullable ItemStack itemStack) {
        return sellScreenView.isContentPlaceholder(itemStack);
    }

    public boolean isSellConfirmContentPlaceholder(@Nullable ItemStack itemStack) {
        return sellConfirmScreenView.isContentPlaceholder(itemStack);
    }

    public boolean isStorageContentPlaceholder(@Nullable ItemStack itemStack) {
        return storageScreenView.isContentPlaceholder(itemStack);
    }

    public @Nullable UUID getStorageEntryId(@Nullable ItemStack itemStack) {
        return storageScreenView.getEntryId(itemStack);
    }

    public @Nullable String getClassId(@Nullable ItemStack itemStack) {
        return classScreenView.getClassId(itemStack);
    }

    private @NotNull List<String> activeBuffNames(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return List.of();
        }
        return plugin.getStatusService().getActiveBuffs(astPlayer).stream()
            .map(buff -> ColorCodeUtil.stripColor(ColorCodeUtil.translateAlternateColorCodes(buff.getType().getDisplayName())))
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .limit(5)
            .toList();
    }
}

