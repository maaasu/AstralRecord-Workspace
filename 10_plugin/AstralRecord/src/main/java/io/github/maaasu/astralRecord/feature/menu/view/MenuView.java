package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.view.CurrencyGuiView;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BuffScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.ClassScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.GuideScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.MainMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.SellConfirmScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashConfirmScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashScreenView;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassViewEntry;
import io.github.maaasu.astralRecord.feature.sell.view.SellScreenView;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
import io.github.maaasu.astralRecord.feature.storage.view.StorageScreenView;
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
    public static final int BACK_SLOT = BaseMenuScreenView.BACK_SLOT;
    public static final int STATUS_SLOT = MainMenuScreenView.STATUS_SLOT;
    public static final int QUEST_SLOT = MainMenuScreenView.QUEST_SLOT;
    public static final int PLAYER_SETTING_SLOT = MainMenuScreenView.PLAYER_SETTING_SLOT;
    public static final int EQUIPMENT_GUI_SLOT = MainMenuScreenView.EQUIPMENT_GUI_SLOT;
    public static final int TRASH_SLOT = MainMenuScreenView.TRASH_SLOT;
    public static final int GUIDE_SLOT = MainMenuScreenView.GUIDE_SLOT;
    public static final int RETURN_TO_BASE_SLOT = MainMenuScreenView.RETURN_TO_BASE_SLOT;
    public static final int SKILL_BIND_SLOT = MainMenuScreenView.SKILL_BIND_SLOT;
    public static final int CURRENCY_SLOT = MainMenuScreenView.CURRENCY_SLOT;
    public static final int PARTY_SLOT = MainMenuScreenView.PARTY_SLOT;
    public static final int PLAYER_INFO_SLOT = MainMenuScreenView.PLAYER_INFO_SLOT;
    public static final int ADVENTURE_RECORD_SLOT = MainMenuScreenView.ADVENTURE_RECORD_SLOT;
    public static final int MAIL_SLOT = MainMenuScreenView.MAIL_SLOT;
    public static final int EQUIPMENT_PLAYER_STATUS_SLOT = EquipmentMenuScreenView.PLAYER_STATUS_SLOT;
    public static final int EQUIPMENT_BACK_SLOT = EquipmentMenuScreenView.EQUIPMENT_BACK_SLOT;
    public static final int EQUIPMENT_MAIN_HAND_SLOT = EquipmentMenuScreenView.EQUIPMENT_MAIN_HAND_SLOT;
    public static final int EQUIPMENT_HEAD_SLOT = EquipmentMenuScreenView.EQUIPMENT_HEAD_SLOT;
    public static final int EQUIPMENT_CHEST_SLOT = EquipmentMenuScreenView.EQUIPMENT_CHEST_SLOT;
    public static final int EQUIPMENT_LEGS_SLOT = EquipmentMenuScreenView.EQUIPMENT_LEGS_SLOT;
    public static final int EQUIPMENT_FEET_SLOT = EquipmentMenuScreenView.EQUIPMENT_FEET_SLOT;
    public static final int EQUIPMENT_OFF_HAND_SLOT = EquipmentMenuScreenView.EQUIPMENT_OFF_HAND_SLOT;
    public static final int EQUIPMENT_AMULET_SLOT = EquipmentMenuScreenView.EQUIPMENT_AMULET_SLOT;
    public static final int EQUIPMENT_TALISMAN_1_SLOT = EquipmentMenuScreenView.EQUIPMENT_TALISMAN_1_SLOT;
    public static final int EQUIPMENT_TALISMAN_2_SLOT = EquipmentMenuScreenView.EQUIPMENT_TALISMAN_2_SLOT;
    public static final int EQUIPMENT_CHARM_1_SLOT = EquipmentMenuScreenView.EQUIPMENT_CHARM_1_SLOT;
    public static final int EQUIPMENT_CHARM_2_SLOT = EquipmentMenuScreenView.EQUIPMENT_CHARM_2_SLOT;
    public static final int EQUIPMENT_CHARM_3_SLOT = EquipmentMenuScreenView.EQUIPMENT_CHARM_3_SLOT;
    public static final int EQUIPMENT_CORE_SLOT = EquipmentMenuScreenView.EQUIPMENT_CORE_SLOT;
    public static final int EQUIPMENT_RELIC_1_SLOT = EquipmentMenuScreenView.EQUIPMENT_RELIC_1_SLOT;
    public static final int EQUIPMENT_RELIC_2_SLOT = EquipmentMenuScreenView.EQUIPMENT_RELIC_2_SLOT;
    public static final int PAGING_PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int PAGING_BACK_SLOT = PagedGuiView.BACK_SLOT;
    public static final int PAGING_NEXT_SLOT = PagedGuiView.NEXT_SLOT;
    public static final int TRASH_PREVIOUS_SLOT = TrashScreenView.PREVIOUS_SLOT;
    public static final int TRASH_GUIDE_SLOT = TrashScreenView.GUIDE_SLOT;
    public static final int TRASH_CONFIRM_SLOT = TrashScreenView.CONFIRM_SLOT;
    public static final int TRASH_NEXT_SLOT = TrashScreenView.NEXT_SLOT;
    public static final int TRASH_CONFIRM_PREVIOUS_SLOT = TrashConfirmScreenView.PREVIOUS_SLOT;
    public static final int TRASH_CONFIRM_DISPOSE_SLOT = TrashConfirmScreenView.DISPOSE_SLOT;
    public static final int TRASH_CONFIRM_RETURN_SLOT = TrashConfirmScreenView.RETURN_TO_TRASH_SLOT;
    public static final int TRASH_CONFIRM_NEXT_SLOT = TrashConfirmScreenView.NEXT_SLOT;
    public static final int SELL_PREVIOUS_SLOT = SellScreenView.PREVIOUS_SLOT;
    public static final int SELL_GUIDE_SLOT = SellScreenView.GUIDE_SLOT;
    public static final int SELL_CONFIRM_SLOT = SellScreenView.CONFIRM_SLOT;
    public static final int SELL_NEXT_SLOT = SellScreenView.NEXT_SLOT;
    public static final int SELL_CONFIRM_RETURN_SLOT = SellConfirmScreenView.RETURN_TO_SELL_SLOT;
    public static final int SELL_CONFIRM_SELL_SLOT = SellConfirmScreenView.SELL_SLOT;
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
    private static final Component EQUIPMENT_TITLE = Component.text("装備", NamedTextColor.GOLD);
    private static final Component BUFF_TITLE = Component.text("バフ", NamedTextColor.AQUA);
    private static final String CURRENCY_TITLE = "通貨";

    private final MainMenuScreenView mainMenuScreenView;
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
    private final GuideService guideService;

    public MenuView(@NotNull AstralRecord plugin, @NotNull GuideService guideService) {
        this.guideService = guideService;
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
        this.equipmentMenuScreenView = new EquipmentMenuScreenView(equipmentPlaceholderKey);
        this.buffScreenView = new BuffScreenView();
        this.classScreenView = new ClassScreenView(classIdKey);
        this.currencyGuiView = new CurrencyGuiView();
        this.guideScreenView = new GuideScreenView();
        this.trashScreenView = new TrashScreenView(trashPlaceholderKey);
        this.trashConfirmScreenView = new TrashConfirmScreenView(trashPlaceholderKey);
        this.sellScreenView = new SellScreenView(sellPlaceholderKey, itemService);
        this.sellConfirmScreenView = new SellConfirmScreenView(sellPlaceholderKey, itemService);
        this.storageScreenView = new StorageScreenView(storagePlaceholderKey, storageEntryIdKey);
        this.craftShortcutView = new CraftShortcutView(craftShortcutKey, craftActionKey);
    }

    /**
     * 指定プレイヤーのメインメニューを、取得済みの同一描画コンテキストで開きます。
     *
     * @param astPlayer 表示対象プレイヤー
     * @param context GUI 描画コンテキスト
     */
    public void open(@NotNull AstPlayer astPlayer, @NotNull PlayerGuiRenderContext context) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.MAIN), SIZE, MAIN_TITLE);
        mainMenuScreenView.render(inventory, astPlayer.getBukkit(), context);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(astPlayer.getBukkit(), inventory);
    }

    public void openEquipmentGui(@NotNull Player player) {
        openEquipmentGui(player, new ItemStack[0]);
    }

    public void openEquipmentGui(@NotNull Player player, @NotNull ItemStack[] accessories) {
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.EQUIPMENT_GUI), SIZE, EQUIPMENT_TITLE);
        equipmentMenuScreenView.render(inventory, player, accessories);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 指定プレイヤーのバフ一覧 GUI を開きます。
     *
     * @param viewer 閲覧者
     * @param targetId バフの表示対象プレイヤーID
     * @param activeBuffs 現在有効なバフ一覧
     */
    public void openBuff(
        @NotNull Player viewer,
        @NotNull UUID targetId,
        @NotNull List<ActiveBuff> activeBuffs
    ) {
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.BUFF, -1, 0, targetId.toString()),
            SIZE,
            BUFF_TITLE
        );
        buffScreenView.render(inventory, activeBuffs);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory);
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
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 通貨一覧GUIを指定ページで開きます。
     *
     * @param player 表示対象プレイヤー
     * @param currencyItems 表示する所持通貨
     * @param pageIndex 0始まりのページ番号
     * @param exchangeUnlocked カレンシー画面から両替所を開ける場合はtrue
     */
    public void openCurrency(
        @NotNull Player player,
        @NotNull List<ItemStack> currencyItems,
        int pageIndex,
        boolean exchangeUnlocked
    ) {
        int normalizedPage = currencyGuiView.normalizePage(pageIndex, currencyItems.size());
        int totalPages = currencyGuiView.totalPages(currencyItems.size());
        Component title = Component.text(CURRENCY_TITLE + " " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.GOLD);
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.CURRENCY, -1, normalizedPage), PagedGuiView.SIZE, title);
        currencyGuiView.render(inventory, currencyItems, normalizedPage, exchangeUnlocked);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public void openGuide(@NotNull Player player) {
        openGuide(player, 0);
    }

    public void openGuide(@NotNull Player player, int pageIndex) {
        List<GuideEntry> guides = guideService.getAll();
        AstPlayer astPlayer = AstPlayerCache.get(player);
        UUID accountId = astPlayer == null ? null : astPlayer.getAccount().getUuid();
        int normalizedPage = guideScreenView.normalizePage(pageIndex, guides.size());
        int totalPages = guideScreenView.totalPages(guides.size());
        Component title = Component.text("ガイド " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.LIGHT_PURPLE);
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.GUIDE, -1, normalizedPage, null),
            SIZE,
            title
        );
        guideScreenView.renderList(inventory, guides, normalizedPage, guideService, accountId);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public void openGuideDetail(@NotNull Player player, @NotNull GuideEntry guide, int returnPageIndex) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        UUID accountId = astPlayer == null ? null : astPlayer.getAccount().getUuid();
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.GUIDE, -1, returnPageIndex, guide.id()),
            SIZE,
            Component.text("ガイド", NamedTextColor.LIGHT_PURPLE)
        );
        guideScreenView.renderDetail(inventory, guide, guideService, accountId);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public void openTrash(@NotNull Player player, @NotNull List<ItemStack> trashItems, int pageIndex) {
        int normalizedPage = trashScreenView.normalizePage(pageIndex, trashItems.size());
        int totalPages = trashScreenView.totalPages(trashItems.size());
        Component title = Component.text("ゴミ箱 " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.RED);
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.TRASH, -1, normalizedPage), SIZE, title);
        trashScreenView.render(inventory, trashItems, normalizedPage);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
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
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public void openSell(@NotNull Player player, @NotNull List<ItemStack> sellItems, int pageIndex) {
        int normalizedPage = sellScreenView.normalizePage(pageIndex, sellItems.size());
        int totalPages = sellScreenView.totalPages(sellItems.size());
        Component title = Component.text("売却 " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.GOLD);
        Inventory inventory = Bukkit.createInventory(new MenuInventoryHolder(MenuScreen.SELL, -1, normalizedPage), SIZE, title);
        sellScreenView.render(inventory, sellItems, normalizedPage);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 売却 GUI の内容を既存 inventory に再描画します。
     *
     * @param inventory 再描画対象の売却 GUI inventory
     * @param sellItems 表示する売却候補アイテム
     * @param pageIndex 表示ページ番号
     */
    public void renderSell(@NotNull Inventory inventory, @NotNull List<ItemStack> sellItems, int pageIndex) {
        sellScreenView.render(inventory, sellItems, pageIndex);
    }

    public void openSellConfirm(@NotNull Player player, @NotNull List<ItemStack> sellItems, int pageIndex) {
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.SELL_CONFIRM, -1, 0),
            io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView.SIZE,
            SellConfirmScreenView.CONFIRM_MESSAGE
        );
        sellConfirmScreenView.render(inventory, sellItems, 0);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
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
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public void renderStorage(
        @NotNull Inventory inventory,
        @NotNull List<StorageViewEntry> storageItems,
        @NotNull StorageViewOptions options,
        int pageIndex
    ) {
        storageScreenView.render(inventory, storageItems, options, pageIndex);
    }

    /**
     * ストレージのフィルター候補一覧GUIを開きます。
     *
     * @param player 表示対象プレイヤー
     * @param filterType 候補種別
     * @param selectedValue 現在選択中の値
     */
    public void openStorageFilter(
        @NotNull Player player,
        @NotNull StorageScreenView.FilterType filterType,
        @Nullable String selectedValue
    ) {
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.STORAGE, -1, 0, "filter:" + filterType.name()),
            PagedGuiView.SIZE,
            Component.text(filterType.title(), filterType.color())
        );
        storageScreenView.renderFilterOptions(inventory, filterType, selectedValue);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public @NotNull ItemStack createCraftResultIcon() {
        return craftShortcutView.createCraftResultIcon();
    }

    public void renderCraftShortcuts(
        @NotNull AstPlayer astPlayer,
        @NotNull MenuShortcutSettings settings,
        @NotNull PlayerGuiRenderContext context
    ) {
        craftShortcutView.renderCraftShortcuts(
            astPlayer.getBukkit(),
            settings,
            context
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

    /**
     * プレイヤークラフト欄に表示するメニューショートカット用ダミーアイテムかを判定します。
     *
     * @param itemStack 判定対象の ItemStack。null または AIR の場合は false
     * @return メニューショートカット用ダミーアイテムであれば true
     */
    public boolean isCraftShortcutItem(@Nullable ItemStack itemStack) {
        return craftShortcutView.isCraftShortcutIcon(itemStack);
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

    public @Nullable String getContentId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof MenuInventoryHolder holder) {
            return holder.contentId();
        }
        return null;
    }

    public @Nullable EquipmentType getEquipmentTypeAtSlot(int rawSlot) {
        return equipmentMenuScreenView.getEquipmentTypeAtSlot(rawSlot);
    }

    public boolean isExtendedAccessorySlot(int rawSlot) {
        return equipmentMenuScreenView.isExtendedAccessorySlot(rawSlot);
    }

    /**
     * 装備 GUI の物理スロットから種類別アクセサリ種別を解決します。
     *
     * @param rawSlot GUI の物理スロット
     * @return 対応する種別。対象外の場合は null
     */
    public @Nullable AccessorySlotType getAccessorySlotTypeAtSlot(int rawSlot) {
        return equipmentMenuScreenView.getAccessorySlotTypeAtSlot(rawSlot);
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

    /**
     * 指定した種類のアクセサリを配置できる最初の空き枠を返します。
     *
     * @param inventory 装備 GUI
     * @param accessoryType 配置するアクセサリ種別
     * @return 空き GUI スロット。空きがない場合は -1
     */
    public int firstEmptyAccessorySlot(
        @NotNull Inventory inventory,
        @NotNull AccessorySlotType accessoryType
    ) {
        return equipmentMenuScreenView.firstEmptyAccessorySlot(inventory, accessoryType);
    }

    public @NotNull ItemStack[] getAccessoryItems(@NotNull Inventory inventory) {
        return equipmentMenuScreenView.getAccessoryItems(inventory);
    }

    /**
     * 開いている装備 GUI のメインスロット表示を更新します。
     *
     * @param inventory 装備 GUI
     * @param itemStack 選択中ホットバーのアイテム
     */
    public void updateEquipmentMainHandItem(@NotNull Inventory inventory, @Nullable ItemStack itemStack) {
        equipmentMenuScreenView.updateMainHandItem(inventory, itemStack);
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

    public boolean hasPreviousGuidePage(int pageIndex) {
        return guideScreenView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextGuidePage(int pageIndex) {
        return guideScreenView.hasNextPage(pageIndex, guideService.getAll().size());
    }

    public @Nullable GuideEntry getGuideAtSlot(int rawSlot, int pageIndex) {
        if (!guideScreenView.isContentSlot(rawSlot)) {
            return null;
        }
        List<GuideEntry> guides = guideService.getAll();
        int index = guideScreenView.normalizePage(pageIndex, guides.size()) * GuideScreenView.CONTENT_SLOT_COUNT + rawSlot;
        return index >= 0 && index < guides.size() ? guides.get(index) : null;
    }

    /**
     * ガイド詳細画面の物理スロットから手順を解決します。
     *
     * @param guideId 詳細表示中のガイド ID
     * @param rawSlot クリックされた物理スロット
     * @return 対応する手順。対象外の場合は null
     */
    public @Nullable GuideStep getGuideStepAtSlot(@NotNull String guideId, int rawSlot) {
        GuideEntry guide = guideService.getById(guideId);
        return guide == null ? null : guideScreenView.getStepAtSlot(guide, rawSlot);
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

}
