package io.github.maaasu.astralRecord.feature.world.gui;

import io.github.maaasu.astralRecord.feature.world.model.InstanceTeleportDestination;
import io.github.maaasu.astralRecord.feature.world.model.InstanceTeleportFilter;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** ボス・ダンジョンの受付を一つの画面で表示する共通 GUI です。 */
public final class InstanceTeleportGui {
    public static final int SIZE = 54;
    public static final int CONTENT_SIZE = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int FILTER_SLOT = 46;
    public static final int RESET_SLOT = 48;
    public static final int BACK_SLOT = 49;
    public static final int GUIDE_SLOT = 51;
    public static final int NEXT_SLOT = 53;
    public static final int FILTER_SIZE = 27;
    public static final int FILTER_BACK_SLOT = 22;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /**
     * 指定フィルターとページで共通一覧を開きます。空一覧でもフィルターを操作できます。
     * @param player 表示対象
     * @param accountId 操作対象 account UUID
     * @param filter 現在の表示種別
     * @param entries 絞り込み済みの表示候補
     * @param requestedPage 0 始まりの要求ページ
     * @param available 受付定義が現在使用可能かを判定する処理
     */
    public void open(
            @NotNull Player player, @NotNull UUID accountId, @NotNull InstanceTeleportFilter filter,
            @NotNull List<InstanceTeleportDestination> entries, int requestedPage,
            @NotNull Predicate<InstanceTeleportDestination> available
    ) {
        int page = GuiPagination.normalizePage(requestedPage, entries.size(), CONTENT_SIZE);
        int start = GuiPagination.pageStart(page, CONTENT_SIZE);
        int end = GuiPagination.pageEnd(page, entries.size(), CONTENT_SIZE);
        List<InstanceTeleportDestination.Key> visibleKeys = entries.subList(start, end).stream()
                .map(InstanceTeleportDestination::key).toList();
        Inventory inventory = Bukkit.createInventory(
                new Holder(player.getUniqueId(), accountId, filter, page, entries.size(), visibleKeys), SIZE,
                Component.text("インスタンス受付転送", NamedTextColor.AQUA, TextDecoration.BOLD)
        );
        for (int index = start; index < end; index++) {
            InstanceTeleportDestination destination = entries.get(index);
            inventory.setItem(index - start, destinationItem(destination, available.test(destination)));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, GuiItems.create(Material.PAPER,
                    Component.text("該当する受付がありません", NamedTextColor.GRAY),
                    List.of(Component.text("種別フィルターから表示を切り替えられます", NamedTextColor.GRAY))));
        }
        renderNavigation(inventory, filter, page, entries.size());
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * ストレージと同様に、現在値を示す種別候補一覧を開きます。
     * @param player 表示対象
     * @param source 戻り先となる一覧の表示状態
     */
    public void openFilter(@NotNull Player player, @NotNull Holder source) {
        Inventory inventory = Bukkit.createInventory(
                new FilterHolder(source.playerId(), source.accountId(), source.filter(), source.pageIndex()),
                FILTER_SIZE, Component.text("種別を選択", NamedTextColor.AQUA, TextDecoration.BOLD)
        );
        ItemStack spacer = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < FILTER_SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }
        for (InstanceTeleportFilter option : InstanceTeleportFilter.values()) {
            inventory.setItem(option.ordinal(), GuiItems.create(option.icon(),
                    Component.text(option.displayName(), NamedTextColor.AQUA),
                    List.of(Component.text(option == source.filter() ? "現在選択中" : "クリックで適用",
                            option == source.filter() ? NamedTextColor.GREEN : NamedTextColor.GRAY))));
        }
        inventory.setItem(FILTER_BACK_SLOT, GuiItems.backButton(
                new GuiNavigationDestination(Material.COMPASS, "インスタンス受付一覧")));
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * 一覧 holder を取得します。
     * @param inventory 判定するインベントリ
     * @return 一覧 holder。対象外なら null
     */
    public @Nullable Holder holder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder ? holder : null;
    }

    /**
     * フィルター候補 holder を取得します。
     * @param inventory 判定するインベントリ
     * @return 候補 holder。対象外なら null
     */
    public @Nullable FilterHolder filterHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof FilterHolder holder ? holder : null;
    }

    /**
     * 共通一覧またはそのフィルター候補画面かを返します。
     * @param inventory 判定対象
     * @return 本 GUI のいずれかの画面なら true
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return holder(inventory) != null || filterHolder(inventory) != null;
    }

    /**
     * 下段にページ移動、現在フィルター、解除、共通閉じる操作を描画します。
     * @param inventory 描画先
     * @param filter 現在の種別
     * @param page 現在ページ
     * @param count 表示件数
     */
    private void renderNavigation(
            @NotNull Inventory inventory, @NotNull InstanceTeleportFilter filter, int page, int count
    ) {
        ItemStack spacer = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SIZE; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }
        int totalPages = GuiPagination.totalPages(count, CONTENT_SIZE);
        inventory.setItem(PREVIOUS_SLOT, GuiItems.previousPageButton(
                Component.text("前のページ", NamedTextColor.WHITE), List.of(),
                GuiPagination.hasPreviousPage(page)));
        inventory.setItem(FILTER_SLOT, GuiItems.create(Material.HOPPER,
                Component.text("種別フィルター", NamedTextColor.AQUA), List.of(
                        Component.text("◇ 種別", NamedTextColor.DARK_AQUA),
                        Component.text("  現在: " + filter.displayName(), NamedTextColor.WHITE),
                        Component.text("  クリックで候補一覧を表示", NamedTextColor.DARK_GRAY))));
        inventory.setItem(RESET_SLOT, GuiItems.create(Material.BARRIER,
                Component.text("フィルターを解除", NamedTextColor.YELLOW),
                List.of(Component.text("ボスとダンジョンをすべて表示", NamedTextColor.GRAY))));
        inventory.setItem(BACK_SLOT, GuiItems.closeButton());
        inventory.setItem(GUIDE_SLOT, GuiItems.create(Material.PAPER,
                Component.text("受付転送ガイド", NamedTextColor.YELLOW), List.of(
                        Component.text("一覧クリックで受付へ移動", NamedTextColor.AQUA),
                        Component.text("受付で挑戦を開始できます", NamedTextColor.GRAY),
                        Component.text("挑戦参加中は転送できません", NamedTextColor.GRAY),
                        Component.text(count + "件 / " + (page + 1) + " / " + totalPages + "ページ", NamedTextColor.WHITE))));
        inventory.setItem(NEXT_SLOT, GuiItems.nextPageButton(
                Component.text("次のページ", NamedTextColor.WHITE), List.of(),
                GuiPagination.hasNextPage(page, count, CONTENT_SIZE)));
    }

    /**
     * マスタの表示情報から受付アイコンを作成します。内部 ID は表示しません。
     * @param destination 表示候補
     * @param available 受付定義の可用性
     * @return 表示専用アイコン
     */
    private @NotNull ItemStack destinationItem(@NotNull InstanceTeleportDestination destination, boolean available) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("種別: " + destination.key().kind().displayName(), NamedTextColor.GRAY));
        String levelLabel = destination.key().kind() == InstanceTeleportFilter.DUNGEON
                ? "推奨レベル: Lv." : "ボスレベル: Lv.";
        lore.add(Component.text(levelLabel + destination.level(), NamedTextColor.GOLD));
        lore.add(Component.empty());
        for (String line : destination.overview()) {
            for (String paragraph : line.split("\\R")) {
                lore.add(legacy(paragraph, ""));
            }
        }
        if (destination.roomCount() != null) {
            lore.add(Component.text("概要: " + range(destination.roomCount().min(), destination.roomCount().max())
                    + "部屋を探索するダンジョン", NamedTextColor.GRAY));
            lore.add(Component.text("最深部ボス: ", NamedTextColor.GRAY)
                    .append(legacy(destination.bossDisplayName(), "未登録のボス")));
        }
        lore.add(Component.empty());
        lore.add(Component.text("参加人数: " + range(destination.partyMin(), destination.partyMax()) + "人",
                NamedTextColor.GREEN));
        lore.add(Component.text("制限時間: " + timeLimit(destination.timeLimitSeconds()), NamedTextColor.GRAY));
        lore.add(Component.text("復帰可能死亡回数: " + destination.deathLimit() + "回（パーティー共有）",
                NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text(available ? "クリックで受付へ移動" : "受付設定を確認できないため移動できません",
                available ? NamedTextColor.AQUA : NamedTextColor.RED));
        return GuiItems.create(destination.key().kind().icon(),
                legacy(destination.displayName(), "未登録の受付").decorate(TextDecoration.BOLD), lore);
    }

    /**
     * マスタ由来の色コードを共通ルールで正規化します。
     * @param value マスタ表示文字列
     * @param fallback 未定義時の汎用表示
     * @return 色と斜体なしを適用した表示
     */
    private @NotNull Component legacy(@Nullable String value, @NotNull String fallback) {
        return GuiItems.noItalic(LEGACY.deserialize(ColorCodeUtil.toLegacyText(value, fallback)));
    }

    /**
     * 同値の場合は単一値、異なる場合は範囲として表示します。
     * @param min 最小値
     * @param max 最大値
     * @return 表示する範囲
     */
    private @NotNull String range(int min, int max) {
        return min == max ? Integer.toString(min) : min + "～" + max;
    }

    /**
     * 制限時間を分秒表記にします。
     * @param seconds 制限秒数。null は制限なし
     * @return プレイヤー向け制限時間
     */
    private @NotNull String timeLimit(@Nullable Long seconds) {
        if (seconds == null) {
            return "制限なし";
        }
        long minutes = seconds / 60;
        long remaining = seconds % 60;
        return minutes == 0 ? remaining + "秒" : minutes + "分" + (remaining == 0 ? "" : remaining + "秒");
    }

    /**
     * 一覧の種別・ページ・表示キーと操作主体を保持します。
     * @param playerId 操作プレイヤー UUID
     * @param accountId 操作 account UUID
     * @param filter 現在の種別
     * @param pageIndex 現在ページ
     * @param entryCount 絞り込み後の総件数
     * @param visibleKeys 現在ページの受付キー
     */
    public record Holder(
            @NotNull UUID playerId, @NotNull UUID accountId, @NotNull InstanceTeleportFilter filter,
            int pageIndex, int entryCount, @NotNull List<InstanceTeleportDestination.Key> visibleKeys
    ) implements HotbarShortcutGuiHolder {
        /** 表示キーを変更不能なコピーとして保持します。 */
        public Holder {
            visibleKeys = List.copyOf(visibleKeys);
        }

        /** @return 共通ナビゲーション向け一覧の表示情報 */
        @Override public @NotNull GuiNavigationDestination getNavigationDestination() {
            return new GuiNavigationDestination(Material.COMPASS, "インスタンス受付一覧");
        }

        /** @return 共通閉じるボタンのスロット */
        @Override public int getBackSlot() { return BACK_SLOT; }

        /** @return 一覧のナビゲーションは常に閉じる操作とする */
        @Override public boolean isAlwaysCloseNavigation() { return true; }

        /** @return holder のインベントリ形式 */
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }

    /**
     * フィルター候補画面から一覧へ戻るための状態です。
     * @param playerId 操作プレイヤー UUID
     * @param accountId 操作 account UUID
     * @param filter 現在の種別
     * @param listPageIndex 選択を取り消す場合の戻り先ページ
     */
    public record FilterHolder(
            @NotNull UUID playerId, @NotNull UUID accountId,
            @NotNull InstanceTeleportFilter filter, int listPageIndex
    ) implements HotbarShortcutGuiHolder {
        /** @return 共通ナビゲーション向け候補画面の表示情報 */
        @Override public @NotNull GuiNavigationDestination getNavigationDestination() {
            return new GuiNavigationDestination(Material.HOPPER, "受付の種別フィルター");
        }

        /** @return 一覧へ戻るボタンのスロット */
        @Override public int getBackSlot() { return FILTER_BACK_SLOT; }

        /** @return 戻り先を現在マスタで再描画するため個別ハンドラへ委譲する */
        @Override public boolean isDirectBackNavigation() { return false; }

        /** @return holder のインベントリ形式 */
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, FILTER_SIZE); }
    }
}
