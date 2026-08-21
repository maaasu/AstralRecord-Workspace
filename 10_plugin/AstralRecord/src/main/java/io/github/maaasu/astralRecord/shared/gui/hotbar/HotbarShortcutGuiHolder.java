package io.github.maaasu.astralRecord.shared.gui.hotbar;

import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationHolder;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiCloseSoundHolder;

/**
 * プレイヤーインベントリ側のホットバーに共通 GUI ショートカットを表示する holder marker です。
 * <p>
 * open / close 時のホットバーショートカットモード判定はこの marker へ集約し、
 * クリック処理は各 GUI handler から {@link HotbarShortcutClickSupport} へ委譲します。
 */
public interface HotbarShortcutGuiHolder extends GuiNavigationHolder, GuiCloseSoundHolder {
}
