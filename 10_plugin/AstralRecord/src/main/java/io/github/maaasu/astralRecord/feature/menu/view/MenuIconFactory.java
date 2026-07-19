package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 共通メニュー定義と描画時点の値から GUI 用 ItemStack を生成します。
 */
public final class MenuIconFactory {
    private MenuIconFactory() {
    }

    /**
     * 定義済みの基本説明だけを持つメニューアイコンを生成します。
     *
     * @param definition メニューアイコン定義
     * @return 新しく生成した GUI 用 ItemStack
     */
    public static @NotNull ItemStack create(@NotNull MenuIconDefinition definition) {
        return create(definition, List.of());
    }

    /**
     * 定義済みの基本説明へ画面固有の lore を追加してメニューアイコンを生成します。
     * 呼び出しごとに独立した ItemStack を生成します。
     *
     * @param definition メニューアイコン定義
     * @param additionalLore 画面固有の追加 lore
     * @return 新しく生成した GUI 用 ItemStack
     */
    public static @NotNull ItemStack create(
        @NotNull MenuIconDefinition definition,
        @NotNull List<Component> additionalLore
    ) {
        List<Component> lore = new ArrayList<>();
        if (!definition.getDescriptionJa().isBlank()) {
            lore.add(Component.text(definition.getDescriptionJa(), NamedTextColor.GRAY));
        }
        lore.addAll(additionalLore);
        return GuiItems.create(
            definition.getMaterial(),
            Component.text(definition.getDisplayNameJa(), definition.getColor()),
            lore
        );
    }

    /**
     * 現在装備の共通表示行を生成します。
     *
     * @param context GUI 描画コンテキスト
     * @return 頭・胴・脚・足の装備表示行
     */
    public static @NotNull List<Component> equipmentDetails(@NotNull PlayerGuiRenderContext context) {
        PlayerEquipmentSnapshot equipment = context.equipment();
        return List.of(
            equipmentLine("頭", equipment.helmet()),
            equipmentLine("胴", equipment.chestplate()),
            equipmentLine("脚", equipment.leggings()),
            equipmentLine("足", equipment.boots())
        );
    }

    /**
     * 所持ゴールドの共通表示行を生成します。
     *
     * @param context GUI 描画コンテキスト
     * @return 所持ゴールド表示行
     */
    public static @NotNull List<Component> currencyDetails(@NotNull PlayerGuiRenderContext context) {
        return List.of(Component.text("ゴールド: " + context.goldAmount(), NamedTextColor.YELLOW));
    }

    /**
     * 拠点帰還費用の共通表示行を生成します。
     *
     * @param context GUI 描画コンテキスト
     * @return 必要ゴールド表示行
     */
    public static @NotNull List<Component> returnToBaseDetails(@NotNull PlayerGuiRenderContext context) {
        return List.of(Component.text(
            "必要ゴールド " + context.returnToBaseGoldCost()
                + " (100 x Lv." + Math.max(1, context.account().getLevel()) + ")",
            NamedTextColor.YELLOW
        ));
    }

    /**
     * 画面を開くショートカット用の操作案内を返します。
     *
     * @return 操作案内
     */
    public static @NotNull Component openHint() {
        return Component.text("クリックして開く", NamedTextColor.YELLOW);
    }

    /**
     * 即時実行ショートカット用の操作案内を返します。
     *
     * @return 操作案内
     */
    public static @NotNull Component executeHint() {
        return Component.text("クリックして実行", NamedTextColor.YELLOW);
    }

    private static @NotNull Component equipmentLine(@NotNull String label, @NotNull Component itemName) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(itemName);
    }
}
