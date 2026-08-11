package io.github.maaasu.astralRecord.feature.item.gui;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * オーブ専用 GUI を所有セッション・画面種別へ結び付ける holder です。
 *
 * @param ownerId GUI 所有プレイヤー UUID
 * @param sessionToken セッション世代トークン
 * @param screen 表示画面
 */
public record OrbGuiHolder(
    @NotNull UUID ownerId,
    @NotNull UUID sessionToken,
    @NotNull Screen screen
) implements InventoryHolder {

    /** オーブ GUI の固定サイズです。 */
    public static final int SIZE = 54;

    /**
     * holder API が要求する空インベントリを返します。
     *
     * @return この holder を持つ空の54スロットインベントリ
     */
    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, SIZE);
    }

    /** オーブ GUI 内の画面種別です。 */
    public enum Screen {
        LIST,
        TRANSCENDENCE_CONFIRM,
    }
}
