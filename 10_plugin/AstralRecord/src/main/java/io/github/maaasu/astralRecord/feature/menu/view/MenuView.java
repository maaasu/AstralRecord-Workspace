package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.view.CurrencyGuiView;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BuffScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.ClassScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.GuideScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.MainMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.StatusScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashConfirmScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashScreenView;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassViewEntry;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
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

public class MenuView {
    public static final int SIZE = BaseMenuScreenView.SIZE;
    public static final int CLOSE_SLOT = BaseMenuScreenView.CLOSE_SLOT;
    public static final int BACK_SLOT = BaseMenuScreenView.BACK_SLOT;
    public static final int STATUS_SLOT = MainMenuScreenView.STATUS_SLOT;
    public static final int PLAYER_SETTING_SLOT = MainMenuScreenView.PLAYER_SETTING_SLOT;
    public static final int EQUIPMENT_GUI_SLOT = MainMenuScreenView.EQUIPMENT_GUI_SLOT;
    public static final int TRASH_SLOT = MainMenuScreenView.TRASH_SLOT;
    public static final int GUIDE_SLOT = MainMenuScreenView.GUIDE_SLOT;
    public static final int BUFF_SLOT = MainMenuScreenView.BUFF_SLOT;
    public static final int SKILL_BIND_SLOT = MainMenuScreenView.SKILL_BIND_SLOT;
    public static final int CURRENCY_SLOT = MainMenuScreenView.CURRENCY_SLOT;
    public static final int PARTY_SLOT = MainMenuScreenView.PARTY_SLOT;
    public static final int PLAYER_INFO_SLOT = MainMenuScreenView.PLAYER_INFO_SLOT;
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
    public static final int CRAFT_RESULT_RAW_SLOT = CraftShortcutView.CRAFT_RESULT_RAW_SLOT;
    public static final int CRAFT_SHORTCUT_RAW_SLOT_START = CraftShortcutView.CRAFT_SHORTCUT_RAW_SLOT_START;

    private static final Component MAIN_TITLE = Component.text("AstralRecord メニュー", NamedTextColor.DARK_AQUA);
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
    private final CraftShortcutView craftShortcutView;
    private final AstralRecord plugin;

    public MenuView(@NotNull AstralRecord plugin) {
        this.plugin = plugin;
        NamespacedKey craftShortcutKey = new NamespacedKey(plugin, "menu_shortcut_slot");
        NamespacedKey craftActionKey = new NamespacedKey(plugin, "menu_shortcut_action");
        NamespacedKey equipmentPlaceholderKey = new NamespacedKey(plugin, "equipment_placeholder");
        NamespacedKey classIdKey = new NamespacedKey(plugin, "menu_class_id");
        NamespacedKey trashPlaceholderKey = new NamespacedKey(plugin, "trash_content_placeholder");
        this.mainMenuScreenView = new MainMenuScreenView();
        this.statusScreenView = new StatusScreenView();
        this.equipmentMenuScreenView = new EquipmentMenuScreenView(equipmentPlaceholderKey);
        this.buffScreenView = new BuffScreenView();
        this.classScreenView = new ClassScreenView(classIdKey);
        this.currencyGuiView = new CurrencyGuiView();
        this.guideScreenView = new GuideScreenView();
        this.trashScreenView = new TrashScreenView(trashPlaceholderKey);
        this.trashConfirmScreenView = new TrashConfirmScreenView(trashPlaceholderKey);
        this.craftShortcutView = new CraftShortcutView(craftShortcutKey, craftActionKey);
    }

    public void open(@NotNull Player player) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.MAIN), SIZE, MAIN_TITLE);
        mainMenuScreenView.render(inventory, plugin.getCurrencyService().getGoldAmount(player), activeBuffNames(player));
        player.openInventory(inventory);
    }

    public void openStatus(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull StatusSnapshot snapshot) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.STATUS), SIZE, Component.text("ステータス", NamedTextColor.GREEN));
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
    public @NotNull ItemStack createCraftResultIcon() {
        return craftShortcutView.createCraftResultIcon();
    }

    public void renderCraftShortcuts(
        @NotNull Player player,
        @NotNull MenuShortcutSettings settings,
        @Nullable InventoryType selectedType,
        @Nullable StatusSnapshot snapshot,
        @NotNull AccountModel selectedAccount,
        @NotNull List<AccountModel> accounts
    ) {
        craftShortcutView.renderCraftShortcuts(player, settings, selectedType, snapshot, selectedAccount, accounts);
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

    public boolean isTrashContentPlaceholder(@Nullable ItemStack itemStack) {
        return trashScreenView.isContentPlaceholder(itemStack);
    }

    public boolean isTrashConfirmContentPlaceholder(@Nullable ItemStack itemStack) {
        return trashConfirmScreenView.isContentPlaceholder(itemStack);
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

