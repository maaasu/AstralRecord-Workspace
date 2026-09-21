package io.github.maaasu.astralRecord.feature.item.gui;

import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
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
) implements HotbarShortcutGuiHolder {

    @Override
    public @NotNull GuiNavigationDestination getNavigationDestination() {
        return switch (screen) {
            case LIST -> new GuiNavigationDestination(Material.AMETHYST_SHARD, "オーブ対象一覧");
            case INVENTORY_ORB_LIST -> new GuiNavigationDestination(Material.ENDER_CHEST, "オーブ一覧");
            case TRANSCENDENCE_CONFIRM -> new GuiNavigationDestination(Material.ANVIL, "オーブ使用確認");
            case TRANSCENDENCE_MATERIAL_LIST -> new GuiNavigationDestination(Material.CHEST, "消費アイテム一覧");
            case RUNE_ATTACH -> new GuiNavigationDestination(Material.AMETHYST_SHARD, "ルーン装着");
            case RUNE_DETACH -> new GuiNavigationDestination(Material.AMETHYST_SHARD, "ルーン脱着");
            case RUNE_DETACH_SELECT -> new GuiNavigationDestination(Material.BOOK, "脱着ルーン選択");
        };
    }

    /** オーブ一覧・消費アイテム一覧 GUI のサイズです。 */
    public static final int SIZE = 54;

    /** 状態変化確認 GUI のサイズです。 */
    public static final int TRANSCENDENCE_CONFIRM_SIZE = 27;

    /** ルーン装着・脱着 GUI のサイズです。 */
    public static final int RUNE_SIZE = 27;

    /**
     * 画面種別に対応する GUI サイズを返します。
     *
     * @param screen 画面種別
     * @return 画面に必要なスロット数
     */
    public static int sizeFor(@NotNull Screen screen) {
        return switch (screen) {
            case TRANSCENDENCE_CONFIRM -> TRANSCENDENCE_CONFIRM_SIZE;
            case RUNE_ATTACH, RUNE_DETACH, RUNE_DETACH_SELECT -> RUNE_SIZE;
            default -> SIZE;
        };
    }

    /**
     * holder API が要求する空インベントリを返します。
     *
     * @return この holder を持つ空の54スロットインベントリ
     */
    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, sizeFor(screen));
    }

    /** オーブ GUI 内の画面種別です。 */
    public enum Screen {
        LIST,
        INVENTORY_ORB_LIST,
        TRANSCENDENCE_CONFIRM,
        TRANSCENDENCE_MATERIAL_LIST,
        RUNE_ATTACH,
        RUNE_DETACH,
        RUNE_DETACH_SELECT,
    }
}
