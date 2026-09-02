package io.github.maaasu.astralRecord.feature.shop.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.model.ShopMode;
import io.github.maaasu.astralRecord.feature.shop.model.ShopPurchasePreview;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ShopGui {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final String LORE_DIVIDER = "━━━━━━━━━━━━";

    public static final int LIST_SIZE = 54;
    public static final int CONFIRM_SIZE = 27;
    public static final int MAX_LOGICAL_SLOT = 27;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int NEXT_PAGE_SLOT = 53;
    public static final int ITEM_PREVIEW_SLOT = 13;
    public static final int QUANTITY_MINUS_10_SLOT = 9;
    public static final int QUANTITY_MINUS_1_SLOT = 10;
    public static final int QUANTITY_PLUS_1_SLOT = 16;
    public static final int QUANTITY_PLUS_10_SLOT = 17;
    public static final int CONFIRM_BACK_SLOT = 18;
    public static final int BUY_SLOT = 22;

    private final ShopService shopService;
    private final ItemStackFactory itemStackFactory;
    private final NamespacedKey entryIdKey;

    public ShopGui(
        @NotNull AstralRecord plugin,
        @NotNull ShopService shopService,
        @NotNull ItemStackFactory itemStackFactory
    ) {
        this(new NamespacedKey(plugin, "shop_entry_id"), shopService, itemStackFactory);
    }

    ShopGui(
        @NotNull NamespacedKey entryIdKey,
        @NotNull ShopService shopService,
        @NotNull ItemStackFactory itemStackFactory
    ) {
        this.shopService = shopService;
        this.itemStackFactory = itemStackFactory;
        this.entryIdKey = entryIdKey;
    }

    /**
     * ショップの商品一覧 GUI の先頭ページを開きます。
     *
     * @param player 表示対象プレイヤー
     * @param shop 表示するショップ定義
     */
    public void openList(@NotNull Player player, @NotNull ShopDefinition shop) {
        openList(player, shop, 0);
    }

    /**
     * ショップの商品一覧 GUI の指定ページを開きます。
     *
     * @param player 表示対象プレイヤー
     * @param shop 表示するショップ定義
     * @param pageIndex 表示ページ。0-based で、範囲外の場合は有効範囲に丸める
     */
    public void openList(@NotNull Player player, @NotNull ShopDefinition shop, int pageIndex) {
        int normalizedPage = normalizePage(pageIndex, shop);
        Inventory inventory = Bukkit.createInventory(
            new ListHolder(shop.id(), normalizedPage),
            LIST_SIZE,
            LEGACY_SERIALIZER.deserialize(ColorCodeUtil.toLegacyText(shop.name(), shop.id()) + pageSuffix(shop, normalizedPage))
        );
        fillFrame(inventory);
        shop.entries().stream()
            .filter(entry -> toPageIndex(entry) == normalizedPage)
            .sorted(Comparator.comparingInt(this::entrySortSlot))
            .forEach(entry -> {
                int guiSlot = toGuiSlot(entry);
                if (guiSlot < 0) {
                    return;
                }
                ItemModel model = shopService.resolveItem(entry);
                if (model == null) {
                    return;
                }
                inventory.setItem(
                    guiSlot,
                    createShopItem(model, shop, entry)
                );
            });
        renderPagination(inventory, shop, normalizedPage);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * ショップ購入確認 GUI を先頭ページへ戻る前提で開きます。
     *
     * @param player 表示対象プレイヤー
     * @param shop 表示元ショップ定義
     * @param entry 購入対象の商品定義
     * @param quantity 購入数
     * @param preview 購入条件のプレビュー結果
     */
    public void openConfirm(
        @NotNull Player player,
        @NotNull ShopDefinition shop,
        @NotNull ShopEntry entry,
        int quantity,
        @NotNull ShopPurchasePreview preview
    ) {
        openConfirm(player, shop, entry, quantity, preview, 0);
    }

    /**
     * 一覧へ戻るページを保持してショップ購入確認 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param shop 表示元ショップ定義
     * @param entry 購入対象の商品定義
     * @param quantity 購入数
     * @param preview 購入条件のプレビュー結果
     * @param returnPageIndex 戻る操作で開き直す商品一覧ページ。0-based
     */
    public void openConfirm(
        @NotNull Player player,
        @NotNull ShopDefinition shop,
        @NotNull ShopEntry entry,
        int quantity,
        @NotNull ShopPurchasePreview preview,
        int returnPageIndex
    ) {
        Inventory inventory = Bukkit.createInventory(
            new ConfirmHolder(shop.id(), entry.id(), preview.quantity(), normalizePage(returnPageIndex, shop)),
            CONFIRM_SIZE,
            LEGACY_SERIALIZER.deserialize(ColorCodeUtil.toLegacyText(shop.name(), shop.id())
                + " " + ColorCodeUtil.GRAY + (isExchange(shop) ? "/ 両替確認" : "/ 購入確認"))
        );
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        ItemModel model = shopService.resolveItem(entry);
        if (model != null) {
            ItemStack itemPreview = itemStackFactory.createShopDisplay(
                model,
                Math.max(1, entry.amount()) * preview.quantity()
            );
            inventory.setItem(ITEM_PREVIEW_SLOT, itemPreview);
        }
        inventory.setItem(QUANTITY_MINUS_10_SLOT, actionItem(
                Material.REDSTONE,
                quantityAdjustName("数量 ", "-10", NamedTextColor.RED),
                List.of(quantityLore(preview.quantity()))
            ));
            inventory.setItem(QUANTITY_MINUS_1_SLOT, actionItem(
                Material.REDSTONE_TORCH,
                quantityAdjustName("数量 ", "-1", NamedTextColor.RED),
                List.of(quantityLore(preview.quantity()))
            ));
            inventory.setItem(QUANTITY_PLUS_1_SLOT, actionItem(
                Material.LIME_DYE,
                quantityAdjustName("数量 ", "+1", NamedTextColor.GREEN),
                List.of(quantityLore(preview.quantity()))
            ));
            inventory.setItem(QUANTITY_PLUS_10_SLOT, actionItem(
                Material.EMERALD,
                quantityAdjustName("数量 ", "+10", NamedTextColor.GREEN),
                List.of(quantityLore(preview.quantity()))
            ));
        inventory.setItem(CONFIRM_BACK_SLOT, actionItem(
            Material.SPECTRAL_ARROW,
            isExchange(shop) ? "両替一覧へ戻る" : "商品一覧へ戻る",
            List.of(isExchange(shop) ? "両替する額面の一覧を開きます" : "ショップの商品一覧を開きます")
        ));
        inventory.setItem(BUY_SLOT, buyItem(shop, entry, preview));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public boolean isListInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof ListHolder;
    }

    public boolean isConfirmInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof ConfirmHolder;
    }

    public @Nullable String getShopId(@Nullable Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        if (inventory.getHolder() instanceof ListHolder holder) {
            return holder.shopId();
        }
        if (inventory.getHolder() instanceof ConfirmHolder holder) {
            return holder.shopId();
        }
        return null;
    }

    public @Nullable String getEntryId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof ConfirmHolder holder) {
            return holder.entryId();
        }
        return null;
    }

    public int getQuantity(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof ConfirmHolder holder) {
            return holder.quantity();
        }
        return 1;
    }

    /**
     * 商品一覧または購入確認 GUI から保持中の商品一覧ページを取得します。
     *
     * @param inventory 取得対象 inventory
     * @return 商品一覧ページ。対象外または未保持の場合は `0`
     */
    public int getPageIndex(@Nullable Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        if (inventory.getHolder() instanceof ListHolder holder) {
            return holder.pageIndex();
        }
        if (inventory.getHolder() instanceof ConfirmHolder holder) {
            return holder.returnPageIndex();
        }
        return 0;
    }

    public @Nullable String getEntryId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(entryIdKey, PersistentDataType.STRING);
    }

    private @NotNull ItemStack createShopItem(
        @NotNull ItemModel model,
        @NotNull ShopDefinition shop,
        @NotNull ShopEntry entry
    ) {
        int displayAmount = Math.max(1, entry.amount());
        ItemStack itemStack = itemStackFactory.createShopDisplay(model, displayAmount);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        boolean exchange = isExchange(shop);
        lore.add(Component.text(exchange ? "◆ 両替情報 ◆" : "◆ 販売情報 ◆", NamedTextColor.GOLD, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(exchange ? "受取数: " : "販売数: ", NamedTextColor.GRAY)
            .append(Component.text(quantityText(displayAmount), NamedTextColor.AQUA, TextDecoration.BOLD))
            .decoration(TextDecoration.ITALIC, false));
        int requiredGold = shopService.resolveGoldCost(entry);
        List<ShopCostItem> requiredItems = shopService.resolveRequiredItems(entry);
        if (requiredGold > 0) {
            lore.add(Component.text("価格: " + requiredGold + " ゴールド", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(sectionHeader(costSectionTitle(exchange, requiredItems)));
        appendMaterialList(lore, requiredItems, "なし", NamedTextColor.AQUA);
        lore.add(Component.empty());
        lore.add(Component.text(
                exchange ? "クリックで両替確認へ" : "クリックで購入確認へ",
                NamedTextColor.GREEN,
                TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(entryIdKey, PersistentDataType.STRING, entry.id());
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private @NotNull ItemStack buyItem(
        @NotNull ShopDefinition shop,
        @NotNull ShopEntry entry,
        @NotNull ShopPurchasePreview preview
    ) {
        boolean exchange = isExchange(shop);
        Material material = preview.canPurchase() ? Material.GREEN_TERRACOTTA : Material.RED_TERRACOTTA;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(exchange ? "◆ 両替確認 ◆" : "◆ 購入確認 ◆", NamedTextColor.GOLD, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(exchange ? "受取通貨: " : "購入品: ", NamedTextColor.GRAY)
            .append(Component.text(shopService.resolveItemDisplayName(entry), NamedTextColor.WHITE))
            .append(Component.text(
                " " + quantityText(Math.max(1, entry.amount()) * preview.quantity()),
                NamedTextColor.AQUA
            ))
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(exchange ? "両替口数: " : "購入数量: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(preview.quantity()), NamedTextColor.YELLOW))
            .decoration(TextDecoration.ITALIC, false));
        if (preview.requiredGold() > 0) {
            lore.add(Component.text("ゴールド: ", NamedTextColor.GRAY)
                .append(Component.text("必要 " + preview.requiredGold(), NamedTextColor.GOLD))
                .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                .append(Component.text("所持 " + preview.ownedGold(), NamedTextColor.YELLOW))
                .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(sectionHeader(costSectionTitle(exchange, preview.requiredItems())));
        appendMaterialList(lore, preview.requiredItems(), "なし", NamedTextColor.AQUA);
        if (!preview.canPurchase() && !preview.missingItems().isEmpty()) {
            lore.add(sectionHeader(missingCostSectionTitle(exchange, preview)));
            appendMaterialList(lore, preview.missingItems(), "不足なし", NamedTextColor.RED);
        } else if (preview.canPurchase()) {
            lore.add(sectionHeader(exchange ? "両替可能" : "購入可能"));
            lore.add(Component.text(
                    purchaseReadyText(exchange, preview),
                    NamedTextColor.GREEN
                )
                .decoration(TextDecoration.ITALIC, false));
        }
        String actionText = purchaseActionText(exchange, preview);
        lore.add(Component.empty());
        lore.add(Component.text(actionText,
                preview.canPurchase() ? NamedTextColor.GREEN : NamedTextColor.RED,
                TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        return actionItem(material, Component.text(preview.canPurchase()
                ? exchange ? "両替する" : "購入する"
                : purchaseUnavailableLabel(exchange, preview),
                preview.canPurchase() ? NamedTextColor.GREEN : NamedTextColor.RED,
                TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false), lore);
    }

    private int toGuiSlot(@NotNull ShopEntry entry) {
        Integer logicalSlot = entry.slot();
        if (logicalSlot == null && entry.row() != null && entry.column() != null) {
            logicalSlot = (entry.row() - 1) * 7 + (entry.column() - 1);
        }
        if (logicalSlot == null || logicalSlot < 0 || logicalSlot > MAX_LOGICAL_SLOT) {
            return -1;
        }
        int row = logicalSlot / 7;
        int column = logicalSlot % 7;
        return (row + 1) * 9 + column + 1;
    }

    /**
     * 指定ページの前ページが存在するかを返します。
     *
     * @param pageIndex 現在の 0-based ページ
     * @return 前ページが存在する場合は true
     */
    public boolean hasPreviousPage(int pageIndex) {
        return pageIndex > 0;
    }

    /**
     * 指定ショップで現在ページの次ページが存在するかを返します。
     *
     * @param shop 判定対象ショップ
     * @param pageIndex 現在の 0-based ページ
     * @return 次ページが存在する場合は true
     */
    public boolean hasNextPage(@NotNull ShopDefinition shop, int pageIndex) {
        return pageIndex + 1 < totalPages(shop);
    }

    /**
     * 指定ページをショップの有効ページ範囲へ丸めます。
     *
     * @param pageIndex 補正前の 0-based ページ
     * @param shop 判定対象ショップ
     * @return 有効範囲に丸めた 0-based ページ
     */
    public int normalizePage(int pageIndex, @NotNull ShopDefinition shop) {
        return Math.max(0, Math.min(pageIndex, totalPages(shop) - 1));
    }

    private int totalPages(@NotNull ShopDefinition shop) {
        return Math.max(1, shop.entries().stream()
            .mapToInt(ShopGui::toPageIndex)
            .max()
            .orElse(0) + 1);
    }

    private static int toPageIndex(@NotNull ShopEntry entry) {
        return Math.max(0, entry.page() - 1);
    }

    private int entrySortSlot(@NotNull ShopEntry entry) {
        int guiSlot = toGuiSlot(entry);
        return guiSlot < 0 ? Integer.MAX_VALUE : guiSlot;
    }

    private @NotNull String pageSuffix(@NotNull ShopDefinition shop, int pageIndex) {
        int totalPages = totalPages(shop);
        if (totalPages <= 1) {
            return "";
        }
        return " " + ColorCodeUtil.GRAY + "(" + (pageIndex + 1) + "/" + totalPages + ")";
    }

    private void renderPagination(@NotNull Inventory inventory, @NotNull ShopDefinition shop, int pageIndex) {
        int totalPages = totalPages(shop);
        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_PAGE_SLOT, actionItem(
                Material.MAP,
                Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false),
                List.of(Component.text(pageIndex + " / " + totalPages, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))
            ));
        }
        if (hasNextPage(shop, pageIndex)) {
            inventory.setItem(NEXT_PAGE_SLOT, actionItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false),
                List.of(Component.text((pageIndex + 2) + " / " + totalPages, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))
            ));
        }
    }

    private void fillFrame(@NotNull Inventory inventory) {
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        for (int logical = 0; logical <= MAX_LOGICAL_SLOT; logical++) {
            int row = logical / 7;
            int column = logical % 7;
            inventory.setItem((row + 1) * 9 + column + 1, new ItemStack(Material.AIR));
        }
    }

    private void fill(@NotNull Inventory inventory, @NotNull Material material) {
        ItemStack filler = actionItem(material, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private @NotNull ItemStack actionItem(@NotNull Material material, @NotNull String name, @NotNull List<String> lore) {
        return actionItem(material, Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false), lore.stream()
            .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            .toList());
    }

    private @NotNull ItemStack actionItem(@NotNull Material material, @NotNull Component name, @NotNull List<? extends Component> lore) {
        return GuiItems.create(material, name, new ArrayList<>(lore));
    }

    private @NotNull Component quantityAdjustName(@NotNull String label, @NotNull String amount, @NotNull NamedTextColor accentColor) {
        return Component.text(label, NamedTextColor.WHITE, TextDecoration.BOLD)
            .append(Component.text(amount, accentColor, TextDecoration.BOLD))
            .decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull Component quantityLore(int quantity) {
        return Component.text("現在の数量: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(quantity), NamedTextColor.YELLOW))
            .decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull String quantityText(int quantity) {
        return "×" + Math.max(1, quantity);
    }

    private @NotNull Component sectionHeader(@NotNull String title) {
        return Component.text(LORE_DIVIDER, NamedTextColor.DARK_GRAY)
            .append(Component.text(" " + title + " ", NamedTextColor.GRAY))
            .append(Component.text(LORE_DIVIDER, NamedTextColor.DARK_GRAY))
            .decoration(TextDecoration.ITALIC, false);
    }

    private void appendMaterialList(
        @NotNull List<Component> lore,
        @NotNull List<ShopCostItem> materials,
        @NotNull String emptyLabel,
        @NotNull NamedTextColor accentColor
    ) {
        if (materials.isEmpty()) {
            lore.add(Component.text("• " + emptyLabel, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
            return;
        }
        for (ShopCostItem material : materials) {
            lore.add(Component.text("• ", accentColor)
                .append(Component.text(shopService.resolveItemDisplayName(material), NamedTextColor.WHITE))
                .append(Component.text(" " + quantityText(material.amount()), accentColor))
                .decoration(TextDecoration.ITALIC, false));
        }
    }

    private @NotNull String costSectionTitle(boolean exchange, @NotNull List<ShopCostItem> costs) {
        if (exchange) {
            return "交換元通貨";
        }
        if (costs.stream().allMatch(this::isCurrencyCost) && !costs.isEmpty()) {
            return "必要な通貨";
        }
        if (costs.stream().noneMatch(this::isCurrencyCost) && !costs.isEmpty()) {
            return "必要素材";
        }
        return "必要な対価";
    }

    private @NotNull String missingCostSectionTitle(
        boolean exchange,
        @NotNull ShopPurchasePreview preview
    ) {
        if (exchange) {
            return "不足通貨";
        }
        if (preview.requiredGold() <= 0
            && !preview.requiredItems().isEmpty()
            && preview.requiredItems().stream().allMatch(this::isCurrencyCost)) {
            return "不足している通貨";
        }
        if (preview.requiredGold() <= 0
            && !preview.requiredItems().isEmpty()
            && preview.requiredItems().stream().noneMatch(this::isCurrencyCost)) {
            return "不足素材";
        }
        return "不足している対価";
    }

    private @NotNull String purchaseReadyText(
        boolean exchange,
        @NotNull ShopPurchasePreview preview
    ) {
        if (exchange) {
            return "• 交換元通貨を消費して等価交換します";
        }
        boolean hasGold = preview.requiredGold() > 0;
        boolean hasCurrency = preview.requiredItems().stream().anyMatch(this::isCurrencyCost);
        boolean hasMaterials = preview.requiredItems().stream().anyMatch(cost -> !isCurrencyCost(cost));
        if (hasGold && hasMaterials) {
            return "• 素材とゴールドを消費して購入します";
        }
        if (hasGold && hasCurrency) {
            return "• 必要な通貨とゴールドを消費して購入します";
        }
        if (hasGold) {
            return "• ゴールドを消費して購入します";
        }
        if (hasCurrency && !hasMaterials) {
            return "• 必要な通貨を消費して購入します";
        }
        if (hasMaterials) {
            return "• 必要素材を消費して購入します";
        }
        return "• 対価なしで受け取ります";
    }

    private @NotNull String purchaseActionText(
        boolean exchange,
        @NotNull ShopPurchasePreview preview
    ) {
        if (preview.canPurchase()) {
            return exchange ? "◆ クリックして両替する ◆" : "◆ クリックして購入する ◆";
        }
        if (exchange) {
            return "◆ 通貨不足で両替できません ◆";
        }
        boolean onlyCurrency = preview.requiredGold() <= 0
            && !preview.requiredItems().isEmpty()
            && preview.requiredItems().stream().allMatch(this::isCurrencyCost);
        return onlyCurrency
            ? "◆ 必要な通貨が不足して購入できません ◆"
            : "◆ 必要な対価が不足して購入できません ◆";
    }

    private @NotNull String purchaseUnavailableLabel(
        boolean exchange,
        @NotNull ShopPurchasePreview preview
    ) {
        if (exchange) {
            return "通貨が不足しています";
        }
        boolean onlyCurrency = preview.requiredGold() <= 0
            && !preview.requiredItems().isEmpty()
            && preview.requiredItems().stream().allMatch(this::isCurrencyCost);
        return onlyCurrency ? "通貨が不足しています" : "必要な対価が不足しています";
    }

    private boolean isCurrencyCost(@NotNull ShopCostItem cost) {
        return "currency".equalsIgnoreCase(cost.category());
    }

    private boolean isExchange(@NotNull ShopDefinition shop) {
        return shop.mode() == ShopMode.EXCHANGE;
    }

    public record ListHolder(@NotNull String shopId, int pageIndex) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, LIST_SIZE);
        }
    }

    public record ConfirmHolder(
        @NotNull String shopId,
        @NotNull String entryId,
        int quantity,
        int returnPageIndex
    ) implements HotbarShortcutGuiHolder {
        @Override
        public int getBackSlot() {
            return CONFIRM_BACK_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, CONFIRM_SIZE);
        }
    }
}
