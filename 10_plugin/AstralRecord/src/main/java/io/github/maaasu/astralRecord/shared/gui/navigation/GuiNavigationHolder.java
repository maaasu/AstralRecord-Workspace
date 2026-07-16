package io.github.maaasu.astralRecord.shared.gui.navigation;

import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー単位の GUI 履歴で管理する inventory holder です。
 */
public interface GuiNavigationHolder extends InventoryHolder {

    /**
     * 同じ画面を再描画したか判定するための識別子を返します。
     *
     * @return ページ番号など再描画で変わる値を含まない画面識別子
     */
    default @NotNull String getNavigationId() {
        return getClass().getName();
    }

    /**
     * 戻るボタンのスロットを返します。
     *
     * @return 戻るボタンの raw slot。戻るボタンを持たない場合は {@code -1}
     */
    default int getBackSlot() {
        return -1;
    }

    /**
     * 共通ナビゲーションが戻るクリックを直接処理するか返します。
     *
     * @return 直接一つ前の GUI を開く場合は {@code true}
     */
    default boolean isDirectBackNavigation() {
        return true;
    }
}
