package io.github.maaasu.astralRecord.shared.gui.hotbar;

import org.bukkit.inventory.InventoryHolder;

/**
 * プレイヤーインベントリ側のホットバーに共通 GUI ショートカットを表示する holder marker です。
 * <p>
 * open / close 時のホットバーショートカットモード判定はこの marker へ集約し、
 * クリック処理は各 GUI handler から {@link HotbarShortcutClickSupport} へ委譲します。
 */
public interface HotbarShortcutGuiHolder extends InventoryHolder {
}
