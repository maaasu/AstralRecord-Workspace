package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentProcessingMode;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 修理・強化が共有する装備加工 GUI の表示シェルです。 */
public final class EquipmentProcessingMenuScreenView extends BaseMenuScreenView {
    public static final int MODE_EMBLEM_SLOT = 4;
    public static final int GUIDE_SLOT = 10;
    public static final int REPAIR_TAB_SLOT = 12;
    public static final int ENHANCEMENT_TAB_SLOT = 14;
    public static final int INFO_SLOT = 16;
    public static final int TARGET_SLOT = 20;
    public static final int MATERIAL_START_SLOT = 21;
    public static final int MATERIAL_SLOT_COUNT = 3;
    public static final int MATERIAL_LIST_SLOT = 25;
    /** 3段目・右から3枠目。必要ゴールドを含む加工実行ボタンです。 */
    public static final int EXECUTE_SLOT = 24;
    public static final int MATERIAL_LIST_PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int MATERIAL_LIST_BACK_SLOT = PagedGuiView.BACK_SLOT;
    public static final int MATERIAL_LIST_NEXT_SLOT = PagedGuiView.NEXT_SLOT;
    private final PagedGuiView materialListView = new PagedGuiView();

    /**
     * 加工モードをタイトルに含めた通常画面名を返します。
     *
     * @param mode 現在の加工モード
     * @return カーソルを合わせずにモードを判別できる画面名
     */
    public @NotNull String processingTitle(@NotNull EquipmentProcessingMode mode) {
        return "装備加工｜" + mode.displayName();
    }

    /**
     * 強化用の必要素材一覧画面名を返します。
     *
     * @return 必要素材一覧であることを示す画面名
     */
    public @NotNull String materialListTitle() {
        return "装備加工｜強化｜必要素材一覧";
    }

    /**
     * 共通の加工画面を描画します。素材は実アイテムを最大3枠で先行表示し、全種類は素材一覧ボタンから確認できます。
     *
     * @param inventory 描画先 inventory
     * @param mode 現在の加工モード
     * @param selectedEquipment 一時退避中の対象装備。未選択時は {@code null}
     * @param guideItem 操作ガイド表示
     * @param infoItem 修理または強化の情報表示
     * @param materialItems 必要素材の実アイテム表示
     * @param materialListItem 全素材一覧を開く操作アイテム
     * @param executeItem 加工実行操作アイテム
     */
    public void render(
        @NotNull Inventory inventory,
        @NotNull EquipmentProcessingMode mode,
        @Nullable ItemStack selectedEquipment,
        @NotNull ItemStack guideItem,
        @NotNull ItemStack infoItem,
        @NotNull List<ItemStack> materialItems,
        @NotNull ItemStack materialListItem,
        @NotNull ItemStack executeItem
    ) {
        fill(inventory);
        applyModeIdentity(inventory, mode);
        inventory.setItem(GUIDE_SLOT, guideItem);
        inventory.setItem(REPAIR_TAB_SLOT, tabItem(EquipmentProcessingMode.REPAIR, mode));
        inventory.setItem(ENHANCEMENT_TAB_SLOT, tabItem(EquipmentProcessingMode.ENHANCEMENT, mode));
        inventory.setItem(INFO_SLOT, infoItem);
        inventory.setItem(TARGET_SLOT, selectedEquipment == null ? targetPlaceholder() : selectedEquipment);
        for (int index = 0; index < MATERIAL_SLOT_COUNT; index++) {
            inventory.setItem(MATERIAL_START_SLOT + index,
                index < materialItems.size() ? materialItems.get(index) : emptyPanel());
        }
        inventory.setItem(EXECUTE_SLOT, executeItem);
        inventory.setItem(MATERIAL_LIST_SLOT, materialListItem);
    }

    /**
     * 必要素材をページ単位で実アイテム表示する一覧画面を描画します。
     *
     * @param inventory 描画先 inventory
     * @param materialItems 必要素材の実アイテム表示
     * @param pageIndex 0 始まりの表示ページ
     * @param pageCount 全ページ数
     */
    public void renderMaterialList(
        @NotNull Inventory inventory,
        @NotNull List<ItemStack> materialItems,
        int pageIndex,
        int pageCount
    ) {
        List<ItemStack> displayItems = materialItems.isEmpty()
            ? List.of(createItem(Material.LIME_CONCRETE, Component.text("必要素材なし", NamedTextColor.GREEN, TextDecoration.BOLD), List.of(
                Component.text("この強化では消費アイテムは必要ありません。", NamedTextColor.GRAY))))
            : materialItems;
        int normalizedPageCount = Math.max(1, pageCount);
        int normalizedPageIndex = Math.max(0, Math.min(pageIndex, normalizedPageCount - 1));
        materialListView.render(inventory, displayItems, normalizedPageIndex);
    }

    /**
     * 画面上部の帯とアイコンを加工モードに合わせて描画します。
     *
     * @param inventory 描画先 inventory
     * @param mode 現在の加工モード
     */
    private void applyModeIdentity(@NotNull Inventory inventory, @NotNull EquipmentProcessingMode mode) {
        ItemStack accent = createItem(mode == EquipmentProcessingMode.REPAIR
            ? Material.LIME_STAINED_GLASS_PANE
            : Material.PURPLE_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 1; slot <= 7; slot++) {
            inventory.setItem(slot, accent);
        }
        Material emblem = mode == EquipmentProcessingMode.REPAIR ? Material.ANVIL : Material.ENCHANTING_TABLE;
        NamedTextColor color = mode == EquipmentProcessingMode.REPAIR ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE;
        inventory.setItem(MODE_EMBLEM_SLOT, createItem(emblem,
            Component.text(mode.displayName() + "モード", color, TextDecoration.BOLD),
            List.of(Component.text("画面上部の色とタイトルで現在のモードを示します。", NamedTextColor.GRAY))));
    }

    /**
     * 修理または強化へ切り替えるタブアイテムを生成します。
     *
     * @param tabMode このタブが示す加工モード
     * @param activeMode 現在選択中の加工モード
     * @return タブ表示アイテム
     */
    private @NotNull ItemStack tabItem(
        @NotNull EquipmentProcessingMode tabMode,
        @NotNull EquipmentProcessingMode activeMode
    ) {
        boolean active = tabMode == activeMode;
        Material material = tabMode == EquipmentProcessingMode.REPAIR ? Material.ANVIL : Material.ENCHANTING_TABLE;
        NamedTextColor color = tabMode == EquipmentProcessingMode.REPAIR ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE;
        String title = tabMode.displayName() + "モード";
        return createItem(material, Component.text((active ? "▶ " : "") + title, active ? color : NamedTextColor.GRAY,
                TextDecoration.BOLD), List.of(Component.text(
                active ? "現在選択中です。" : "クリックしてこのモードへ切り替えます。", NamedTextColor.GRAY)));
    }

    private @NotNull ItemStack targetPlaceholder() {
        return createItem(Material.ARMOR_STAND, Component.text("加工する装備", NamedTextColor.YELLOW), List.of(
            Component.text("下の所持品から装備をクリックしてセットします。", NamedTextColor.GRAY),
            Component.text("セット中の装備はこの枠をクリックして戻せます。", NamedTextColor.GRAY)
        ));
    }

    private @NotNull ItemStack emptyPanel() {
        return createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }
}
