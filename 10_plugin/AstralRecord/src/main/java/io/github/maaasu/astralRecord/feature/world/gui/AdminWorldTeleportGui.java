package io.github.maaasu.astralRecord.feature.world.gui;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理者用ワールドテレポート先一覧 GUI を描画します。
 */
public final class AdminWorldTeleportGui {
    public static final int SIZE = PagedGuiView.SIZE;
    public static final int CONTENT_SLOT_COUNT = PagedGuiView.CONTENT_SLOT_COUNT;
    public static final int PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int BACK_SLOT = PagedGuiView.BACK_SLOT;
    public static final int NEXT_SLOT = PagedGuiView.NEXT_SLOT;

    private static final Component TITLE = Component.text(
            "ワールドテレポート",
            NamedTextColor.AQUA,
            TextDecoration.BOLD
    );

    /**
     * ワールド一覧 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param worlds 表示対象ワールド
     */
    public void open(@NotNull Player player, @NotNull List<WorldMasterData> worlds) {
        Inventory inventory = Bukkit.createInventory(new Holder(), SIZE, TITLE);
        render(inventory, worlds, 0);
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * 開いている GUI を指定ページで再描画します。
     *
     * @param inventory 再描画対象インベントリ
     * @param worlds 表示対象ワールド
     * @param requestedPage 要求ページ
     */
    public void render(
            @NotNull Inventory inventory,
            @NotNull List<WorldMasterData> worlds,
            int requestedPage
    ) {
        Holder holder = holder(inventory);
        if (holder == null) {
            return;
        }

        int pageIndex = normalizePage(requestedPage, worlds.size());
        Map<Integer, String> worldIdsBySlot = new LinkedHashMap<>();
        clear(inventory);

        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, worlds.size(), CONTENT_SLOT_COUNT);
        for (int index = start; index < end; index++) {
            int slot = index - start;
            WorldMasterData world = worlds.get(index);
            inventory.setItem(slot, destinationItem(world));
            worldIdsBySlot.put(slot, world.id());
        }

        renderNavigation(inventory, worlds.size(), pageIndex);
        holder.update(pageIndex, worldIdsBySlot);
    }

    /**
     * 指定インベントリがこの GUI か判定します。
     *
     * @param inventory 判定対象
     * @return この GUI なら {@code true}
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * GUI の Holder を取得します。
     *
     * @param inventory 対象インベントリ
     * @return Holder。対象外なら {@code null}
     */
    public @Nullable Holder holder(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder;
        }
        return null;
    }

    /**
     * 前後の範囲を超えないページ番号へ補正します。表示対象が空の場合は 0 を返します。
     *
     * @param pageIndex 補正前の0始まりページ番号
     * @param itemCount 表示対象件数
     * @return 0以上の有効なページ番号
     */
    public int normalizePage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    /**
     * 現在ページより前にページが存在するかを返します。
     *
     * @param pageIndex 現在の0始まりページ番号
     * @return 前ページが存在する場合は {@code true}
     */
    public boolean hasPreviousPage(int pageIndex) {
        return GuiPagination.hasPreviousPage(pageIndex);
    }

    /**
     * 現在ページより後にページが存在するかを返します。
     *
     * @param pageIndex 現在の0始まりページ番号
     * @param itemCount 表示対象件数
     * @return 次ページが存在する場合は {@code true}
     */
    public boolean hasNextPage(int pageIndex, int itemCount) {
        return GuiPagination.hasNextPage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    private void clear(@NotNull Inventory inventory) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
    }

    private void renderNavigation(@NotNull Inventory inventory, int itemCount, int pageIndex) {
        ItemStack spacer = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, GuiItems.previousPageButton(
                    Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                    List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(BACK_SLOT, GuiItems.closeButton());
        if (hasNextPage(pageIndex, itemCount)) {
            inventory.setItem(NEXT_SLOT, GuiItems.nextPageButton(
                    Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                    List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
    }

    private int totalPages(int itemCount) {
        return GuiPagination.totalPages(itemCount, CONTENT_SLOT_COUNT);
    }

    private @NotNull ItemStack destinationItem(@NotNull WorldMasterData world) {
        List<Component> lore = new ArrayList<>();
        if (world.description().isBlank()) {
            lore.add(Component.text("説明はありません", NamedTextColor.GRAY));
        } else {
            for (String line : world.description().split("\\R", -1)) {
                lore.add(ColorCodeUtil.toComponent(line, "説明はありません")
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        lore.add(Component.text(
                "種別: " + world.worldType().getRegionDisplayName(),
                NamedTextColor.YELLOW
        ));
        lore.add(Component.text("クリックで移動", NamedTextColor.AQUA));

        Component displayName = ColorCodeUtil.toComponent(
                world.displayName(),
                world.worldType().getRegionDisplayName()
        ).decorate(TextDecoration.BOLD);
        return GuiItems.create(resolveIcon(world.guiIconMaterial()), displayName, lore);
    }

    private @NotNull Material resolveIcon(@Nullable String materialName) {
        if (materialName != null && !materialName.isBlank()) {
            Material resolved = MaterialNameResolver.match(materialName);
            if (resolved != null && resolved.isItem()) {
                return resolved;
            }
        }
        return Material.GRASS_BLOCK;
    }

    /**
     * GUI のページとスロット別ワールド ID を保持します。
     */
    public static final class Holder implements InventoryHolder {
        private int pageIndex;
        private Map<Integer, String> worldIdsBySlot = Map.of();

        private void update(int pageIndex, @NotNull Map<Integer, String> worldIdsBySlot) {
            this.pageIndex = pageIndex;
            this.worldIdsBySlot = Map.copyOf(worldIdsBySlot);
        }

        /**
         * 現在描画されている0始まりのページ番号を返します。
         *
         * @return 現在ページ番号
         */
        public int pageIndex() {
            return pageIndex;
        }

        /**
         * 現在ページのGUIスロットと内部ワールドIDの対応を返します。
         *
         * @return スロット番号から内部ワールドIDへの不変対応表
         */
        public @NotNull Map<Integer, String> worldIdsBySlot() {
            return worldIdsBySlot;
        }

        /**
         * このHolderが所有するGUIインベントリを生成します。
         *
         * @return GUI用インベントリ
         */
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
