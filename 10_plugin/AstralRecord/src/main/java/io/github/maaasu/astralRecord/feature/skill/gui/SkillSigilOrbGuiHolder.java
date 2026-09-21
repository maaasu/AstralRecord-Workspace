package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * シジルオーブ GUI を所有者・セッション世代・画面種別へ結び付けます。
 *
 * @param ownerId GUI を操作できるプレイヤー UUID
 * @param sessionToken 画面遅延イベントを拒否する世代トークン
 * @param screen 表示中の画面種別
 */
public record SkillSigilOrbGuiHolder(
    @NotNull UUID ownerId,
    @NotNull UUID sessionToken,
    @NotNull Screen screen
) implements HotbarShortcutGuiHolder {
    public static final int LIST_SIZE = 54;
    public static final int OPERATION_SIZE = 27;

    @Override
    public @NotNull GuiNavigationDestination getNavigationDestination() {
        return switch (screen) {
            case LIST -> new GuiNavigationDestination(Material.BOOK, "シジル対象スキル");
            case ATTACH -> new GuiNavigationDestination(Material.ENCHANTING_TABLE, "シジル装着");
            case DETACH -> new GuiNavigationDestination(Material.ENCHANTING_TABLE, "シジル脱着");
            case DETACH_SELECT -> new GuiNavigationDestination(Material.AMETHYST_SHARD, "脱着シジル選択");
        };
    }

    /**
     * 画面種別に対応するインベントリサイズを返します。
     *
     * @param screen 画面種別
     * @return Bukkit インベントリのスロット数
     */
    public static int sizeFor(@NotNull Screen screen) {
        return screen == Screen.LIST ? LIST_SIZE : OPERATION_SIZE;
    }

    /**
     * holder API 用の空インベントリを返します。
     *
     * @return 現在画面サイズの空インベントリ
     */
    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, sizeFor(screen));
    }

    public enum Screen {
        LIST,
        ATTACH,
        DETACH,
        DETACH_SELECT,
    }
}
