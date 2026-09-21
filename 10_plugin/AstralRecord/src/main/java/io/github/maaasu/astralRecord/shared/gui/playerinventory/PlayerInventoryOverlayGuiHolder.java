package io.github.maaasu.astralRecord.shared.gui.playerinventory;

/**
 * PlayerInventory の表示領域を一時的な GUI 操作へ置き換える top inventory holder の印です。
 * 共通の BAG/HOTBAR ショートカット処理は、この holder の表示中に下段を操作しません。
 */
public interface PlayerInventoryOverlayGuiHolder {
    /** @return この画面で下段を専用表示にする場合true */
    boolean hasPlayerInventoryOverlay();

    /**
     * 正本を変更せず下段の専用表示を描画します。
     * @param player 表示対象プレイヤー
     */
    void renderPlayerInventory(org.bukkit.entity.Player player);
}
