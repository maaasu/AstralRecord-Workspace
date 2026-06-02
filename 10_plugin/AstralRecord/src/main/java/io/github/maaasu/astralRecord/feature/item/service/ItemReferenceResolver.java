package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 業務ロジックが {@link ItemStack} を直接読み解かずに済むように、
 * アイテム参照情報への変換を集約する resolver です。
 */
public final class ItemReferenceResolver {

    private final ItemService itemService;

    /**
     * resolver を生成します。
     *
     * @param itemService アイテム定義とインスタンス解決に利用するサービス
     */
    public ItemReferenceResolver(@NotNull ItemService itemService) {
        this.itemService = itemService;
    }

    /**
     * {@link ItemStack} を業務ロジック用の参照情報へ変換します。
     * <p>
     * category が PDC に存在しない古いデータでも、item 定義を解決できる場合は
     * category を補完して参照情報を返します。
     *
     * @param itemStack 変換対象の ItemStack
     * @return 変換できた参照情報。AstralRecord アイテムでない場合は null
     */
    public @Nullable ItemReference resolve(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }

        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        if (itemId == null || itemId.isBlank()) {
            return null;
        }

        String category = ItemStackFactory.getCategory(itemStack);
        if (category == null || category.isBlank()) {
            ItemModel model = resolveItemModel(itemId, null);
            if (model == null) {
                return null;
            }
            category = model.getCategory();
        }

        return new ItemReference(
            itemId,
            category,
            ItemStackFactory.getEquipmentInstanceId(itemStack),
            ItemStackFactory.getRuneInstanceId(itemStack)
        );
    }

    /**
     * {@link ItemStack} が AstralRecord アイテムか判定します。
     *
     * @param itemStack 判定対象の ItemStack
     * @return AstralRecord アイテムの場合 true
     */
    public boolean isAstralItem(@Nullable ItemStack itemStack) {
        return resolve(itemStack) != null;
    }

    /**
     * 参照情報からアイテム定義を解決します。
     *
     * @param reference 参照情報
     * @return 解決できたアイテム定義。見つからない場合は null
     */
    public @Nullable ItemModel resolveItemModel(@Nullable ItemReference reference) {
        if (reference == null) {
            return null;
        }
        return resolveItemModel(reference.itemId(), reference.category());
    }

    /**
     * {@link ItemStack} から参照情報を解決し、そのアイテム定義を返します。
     *
     * @param itemStack 解決対象の ItemStack
     * @return 解決できたアイテム定義。見つからない場合は null
     */
    public @Nullable ItemModel resolveItemModel(@Nullable ItemStack itemStack) {
        return resolveItemModel(resolve(itemStack));
    }

    /**
     * 参照情報から装備インスタンスを解決します。
     *
     * @param reference 参照情報
     * @return 解決できた装備インスタンス。装備参照でない場合や見つからない場合は null
     */
    public @Nullable EquipmentInstance resolveEquipmentInstance(@Nullable ItemReference reference) {
        if (reference == null || !reference.hasEquipmentInstanceId()) {
            return null;
        }
        return itemService.findEquipmentInstanceById(reference.equipmentInstanceId());
    }

    /**
     * 参照情報からルーンインスタンスを解決します。
     *
     * @param reference 参照情報
     * @return 解決できたルーンインスタンス。ルーン参照でない場合や見つからない場合は null
     */
    public @Nullable RuneInstance resolveRuneInstance(@Nullable ItemReference reference) {
        if (reference == null || !reference.hasRuneInstanceId()) {
            return null;
        }
        return itemService.findRuneInstanceById(reference.runeInstanceId());
    }

    private @Nullable ItemModel resolveItemModel(@NotNull String itemId, @Nullable String category) {
        ItemModel loaded = itemService.findLoadedById(itemId);
        if (loaded != null) {
            return loaded;
        }
        if (category != null && !category.isBlank()) {
            ItemModel resolved = itemService.loadItem(itemId, category);
            if (resolved != null) {
                return resolved;
            }
        }
        return itemService.loadItem(itemId);
    }
}
