package io.github.maaasu.astralRecord.feature.market.gui;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.market.model.MarketAccountSummary;
import io.github.maaasu.astralRecord.feature.market.model.MarketListing;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingDraft;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** マーケットの閲覧・出品・購入確認を描画する GUI ビューです。 */
public final class MarketGui {
    public static final int LIST_SIZE = 54;
    public static final int DIALOG_SIZE = 27;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BROWSE_SLOT = 46;
    public static final int MY_LISTINGS_SLOT = 47;
    public static final int SELL_SLOT = 48;
    public static final int CLOSE_SLOT = 49;
    public static final int SUMMARY_SLOT = 50;
    public static final int REFRESH_SLOT = 51;
    public static final int NEXT_SLOT = 53;
    public static final int BACK_SLOT = 21;
    public static final int CONFIRM_SLOT = 23;
    public static final int QUANTITY_DOWN_SLOT = 10;
    public static final int QUANTITY_SLOT = 11;
    public static final int ITEM_SLOT = 13;
    public static final int PRICE_SLOT = 15;
    public static final int QUANTITY_UP_SLOT = 16;

    private static final int LISTING_COUNT = 45;
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter
        .ofPattern("yyyy/MM/dd HH:mm", Locale.JAPAN)
        .withZone(ZoneId.systemDefault());

    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;

    public MarketGui(@NotNull ItemService itemService, @NotNull ItemStackFactory itemStackFactory) {
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
    }

    public void openLoading(@NotNull Player viewer, @NotNull UUID sessionId) {
        Inventory inventory = create(viewer, sessionId, MarketScreen.LOADING, DIALOG_SIZE, "マーケット");
        fill(inventory);
        inventory.setItem(ITEM_SLOT, item(
            Material.CLOCK,
            "読み込み中",
            NamedTextColor.YELLOW,
            List.of("マーケット情報を取得しています。")
        ));
        open(viewer, inventory);
    }

    public void openListings(
        @NotNull Player viewer,
        @NotNull UUID sessionId,
        @NotNull MarketScreen screen,
        @NotNull List<MarketListing> listings,
        @Nullable MarketAccountSummary summary,
        int page,
        long goldAmount
    ) {
        String title = screen == MarketScreen.MY_LISTINGS ? "マーケット: あなたの出品" : "マーケット";
        Inventory inventory = create(viewer, sessionId, screen, LIST_SIZE, title);
        fill(inventory);
        for (int index = 0; index < Math.min(LISTING_COUNT, listings.size()); index++) {
            inventory.setItem(index, listingItem(listings.get(index), screen == MarketScreen.MY_LISTINGS));
        }
        inventory.setItem(PREVIOUS_SLOT, item(
            Material.SPECTRAL_ARROW,
            "前のページ",
            NamedTextColor.YELLOW,
            List.of("ページ: " + Math.max(1, page - 1))
        ));
        inventory.setItem(BROWSE_SLOT, item(
            Material.COMPASS,
            "出品を探す",
            NamedTextColor.AQUA,
            List.of("公開中の出品を表示します。")
        ));
        inventory.setItem(MY_LISTINGS_SLOT, item(
            Material.WRITABLE_BOOK,
            "自分の出品",
            NamedTextColor.GOLD,
            List.of("出品中・取り下げ済みの出品を確認します。")
        ));
        inventory.setItem(SELL_SLOT, item(
            Material.CHEST,
            "出品する",
            NamedTextColor.GREEN,
            List.of("手持ちのアイテムを選択して出品します。")
        ));
        inventory.setItem(REFRESH_SLOT, item(
            Material.CLOCK,
            "更新",
            NamedTextColor.YELLOW,
            List.of("最新の出品情報を取得します。")
        ));
        inventory.setItem(SUMMARY_SLOT, summaryItem(summary, goldAmount));
        inventory.setItem(CLOSE_SLOT, GuiItems.closeButton());
        inventory.setItem(NEXT_SLOT, item(
            Material.ARROW,
            "次のページ",
            NamedTextColor.YELLOW,
            List.of("ページ: " + (Math.max(1, page) + 1))
        ));
        open(viewer, inventory);
    }

    /**
     * 所持品から出品対象を選ぶ画面を開きます。
     *
     * @param viewer 表示対象プレイヤー
     * @param sessionId 操作セッションID
     * @param summary 出品枠の利用状況。未取得時は {@code null}
     * @param goldAmount 表示する現在のGold残高
     */
    public void openSellSelect(
        @NotNull Player viewer,
        @NotNull UUID sessionId,
        @Nullable MarketAccountSummary summary,
        long goldAmount
    ) {
        Inventory inventory = create(viewer, sessionId, MarketScreen.SELL_SELECT, LIST_SIZE, "マーケット: 出品するアイテムを選択");
        fill(inventory);
        inventory.setItem(ITEM_SLOT, item(
            Material.CHEST,
            "出品アイテムを選択",
            NamedTextColor.GREEN,
            List.of(
                "1. 下のバッグまたはホットバーからアイテムをクリックします。",
                "2. 数量と1個あたりの価格を設定して出品を確定します。",
                "バッグは表示内の矢印で上下にスクロールできます。",
                "取引不可アイテムとGoldは出品できません。"
            )
        ));
        inventory.setItem(BROWSE_SLOT, item(
            Material.SPECTRAL_ARROW,
            "出品一覧へ戻る",
            NamedTextColor.WHITE,
            List.of("公開中の出品一覧へ戻ります。")
        ));
        inventory.setItem(SUMMARY_SLOT, summaryItem(summary, goldAmount));
        inventory.setItem(CLOSE_SLOT, GuiItems.closeButton());
        open(viewer, inventory);
    }

    public void openSellConfig(
        @NotNull Player viewer,
        @NotNull UUID sessionId,
        @NotNull MarketListingDraft draft
    ) {
        Inventory inventory = create(viewer, sessionId, MarketScreen.SELL_CONFIG, DIALOG_SIZE, "マーケット: 出品設定");
        fill(inventory);
        inventory.setItem(QUANTITY_DOWN_SLOT, item(
            Material.RED_CONCRETE,
            "数量を減らす",
            NamedTextColor.RED,
            List.of("Shiftクリックで 16 個減らします。")
        ));
        inventory.setItem(QUANTITY_SLOT, item(
            Material.HOPPER,
            "数量: " + format(draft.quantity()),
            NamedTextColor.YELLOW,
            List.of("最大: " + format(draft.maxQuantity()))
        ));
        inventory.setItem(ITEM_SLOT, draftItem(draft));
        inventory.setItem(PRICE_SLOT, item(
            Material.GOLD_INGOT,
            "1個あたり: " + format(draft.unitPrice()) + " Gold",
            NamedTextColor.GOLD,
            List.of(
                "合計: " + format(draft.totalPrice()) + " Gold",
                "クリックして価格を設定します。"
            )
        ));
        inventory.setItem(QUANTITY_UP_SLOT, item(
            Material.LIME_CONCRETE,
            "数量を増やす",
            NamedTextColor.GREEN,
            List.of("Shiftクリックで 16 個増やします。")
        ));
        inventory.setItem(BACK_SLOT, GuiItems.backButton());
        inventory.setItem(CONFIRM_SLOT, item(
            Material.EMERALD_BLOCK,
            "出品を確定",
            NamedTextColor.GREEN,
            List.of("合計 " + format(draft.totalPrice()) + " Gold で出品します。")
        ));
        open(viewer, inventory);
    }

    public void openPurchaseConfirm(
        @NotNull Player viewer,
        @NotNull UUID sessionId,
        @NotNull MarketListing listing,
        long goldAmount
    ) {
        Inventory inventory = create(viewer, sessionId, MarketScreen.PURCHASE_CONFIRM, DIALOG_SIZE, "マーケット: 購入確認");
        fill(inventory);
        inventory.setItem(ITEM_SLOT, listingItem(listing, false));
        inventory.setItem(11, item(
            Material.GOLD_INGOT,
            "購入額: " + format(listing.totalPrice()) + " Gold",
            NamedTextColor.GOLD,
            List.of("所持 Gold: " + format(goldAmount))
        ));
        inventory.setItem(BACK_SLOT, GuiItems.backButton());
        inventory.setItem(CONFIRM_SLOT, item(
            Material.EMERALD_BLOCK,
            "購入を確定",
            NamedTextColor.GREEN,
            List.of("Gold とアイテムを即時交換します。")
        ));
        open(viewer, inventory);
    }

    public void openCancelConfirm(
        @NotNull Player viewer,
        @NotNull UUID sessionId,
        @NotNull MarketListing listing
    ) {
        Inventory inventory = create(viewer, sessionId, MarketScreen.CANCEL_CONFIRM, DIALOG_SIZE, "マーケット: 取り下げ確認");
        fill(inventory);
        inventory.setItem(ITEM_SLOT, listingItem(listing, true));
        inventory.setItem(BACK_SLOT, GuiItems.backButton());
        inventory.setItem(CONFIRM_SLOT, item(
            Material.ORANGE_CONCRETE,
            "出品を取り下げる",
            NamedTextColor.GOLD,
            List.of("アイテムは所持品へ返却されます。", "取り下げ済み出品も出品枠を使用します。")
        ));
        open(viewer, inventory);
    }

    public boolean isMarketInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof MarketHolder;
    }

    public @Nullable MarketHolder getHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof MarketHolder holder ? holder : null;
    }

    private @NotNull Inventory create(
        @NotNull Player viewer,
        @NotNull UUID sessionId,
        @NotNull MarketScreen screen,
        int size,
        @NotNull String title
    ) {
        return Bukkit.createInventory(
            new MarketHolder(sessionId, viewer.getUniqueId(), screen),
            size,
            Component.text(title, NamedTextColor.WHITE)
        );
    }

    private void open(@NotNull Player viewer, @NotNull Inventory inventory) {
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory);
    }

    private void fill(@NotNull Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY, List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private @NotNull ItemStack listingItem(@NotNull MarketListing listing, boolean ownListing) {
        ItemModel model = itemService.findLoadedById(listing.itemId());
        ItemStack stack = model == null
            ? new ItemStack(Material.CHEST)
            : itemStackFactory.createShopDisplay(model, Math.max(1, (int) Math.min(64L, listing.quantity())));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (model == null) {
            meta.displayName(Component.text("未解決の出品アイテム", NamedTextColor.RED, TextDecoration.BOLD));
        }
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.add(Component.empty());
        lore.add(Component.text("数量: " + format(listing.quantity()), NamedTextColor.WHITE));
        lore.add(Component.text("単価: " + format(listing.unitPrice()) + " Gold", NamedTextColor.GOLD));
        lore.add(Component.text("合計: " + format(listing.totalPrice()) + " Gold", NamedTextColor.YELLOW));
        lore.add(Component.text("出品日時: " + DATE_TIME_FORMAT.format(listing.listedAt()), NamedTextColor.GRAY));
        if (ownListing) {
            lore.add(Component.text("状態: " + displayStatus(listing.status()), statusColor(listing.status())));
            lore.add(Component.text(
                listing.status().equalsIgnoreCase("ACTIVE") ? "クリックして取り下げます。" : "取り下げ済み出品も枠を使用します。",
                NamedTextColor.GRAY
            ));
        } else {
            lore.add(Component.text("クリックして購入確認へ進みます。", NamedTextColor.GREEN));
        }
        meta.lore(lore.stream().map(GuiItems::noItalic).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private @NotNull ItemStack draftItem(@NotNull MarketListingDraft draft) {
        ItemModel model = itemService.findLoadedById(draft.itemId());
        ItemStack stack = model == null
            ? new ItemStack(Material.CHEST)
            : itemStackFactory.createShopDisplay(model, Math.max(1, (int) Math.min(64L, draft.quantity())));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            lore.add(Component.empty());
            lore.add(Component.text("出品予定数: " + format(draft.quantity()), NamedTextColor.YELLOW));
            meta.lore(lore.stream().map(GuiItems::noItalic).toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private @NotNull ItemStack summaryItem(@Nullable MarketAccountSummary summary, long goldAmount) {
        if (summary == null) {
            return item(Material.BOOK, "出品枠を取得中", NamedTextColor.YELLOW, List.of());
        }
        return item(
            Material.BOOK,
            "マーケット利用状況",
            NamedTextColor.AQUA,
            List.of(
                "出品枠: " + summary.usedListingSlotCount() + " / " + summary.maxListingSlotCount(),
                "出品中: " + summary.activeListingCount(),
                "所持 Gold: " + format(goldAmount),
                "取り下げ済み出品も出品枠を使用します。"
            )
        );
    }

    private @NotNull ItemStack item(
        @NotNull Material material,
        @NotNull String name,
        @NotNull NamedTextColor color,
        @NotNull List<String> lore
    ) {
        return GuiItems.create(
            material,
            Component.text(name, color, TextDecoration.BOLD),
            lore.stream().<Component>map(line -> Component.text(line, NamedTextColor.GRAY)).toList()
        );
    }

    private static @NotNull String format(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    private static @NotNull String displayStatus(@NotNull String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "出品中";
            case "SUSPENDED" -> "停止中";
            case "CANCELED" -> "取り下げ済み";
            case "SOLD" -> "売却済み";
            case "EXPIRED" -> "期限切れ";
            default -> status;
        };
    }

    private static @NotNull NamedTextColor statusColor(@NotNull String status) {
        return status.equalsIgnoreCase("ACTIVE") ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
    }

    public record MarketHolder(
        @NotNull UUID sessionId,
        @NotNull UUID viewerUuid,
        @NotNull MarketScreen screen
    ) implements HotbarShortcutGuiHolder {
        /**
         * 一覧系画面で共通ナビゲーションが扱う閉じるボタンのスロットを返します。
         *
         * @return 閉じるボタンのスロット。確認・設定画面では {@code -1}
         */
        @Override
        public int getBackSlot() {
            return switch (screen) {
                case BROWSE, MY_LISTINGS, SELL_SELECT -> CLOSE_SLOT;
                default -> -1;
            };
        }

        /**
         * 一覧系画面の共通ナビゲーションボタンを常に閉じる操作として扱うか返します。
         *
         * @return 一覧系画面の場合は {@code true}
         */
        @Override
        public boolean isAlwaysCloseNavigation() {
            return screen == MarketScreen.BROWSE
                || screen == MarketScreen.MY_LISTINGS
                || screen == MarketScreen.SELL_SELECT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }
}
