package io.github.maaasu.astralRecord.shared.gui.navigation;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * GUI 履歴の戻るボタンに表示する戻り先画面情報です。
 *
 * @param icon 戻り先 GUI を表すアイコン素材
 * @param screenName 「画面へ戻る」の前に表示する画面名
 * @param iconTexture {@link Material#PLAYER_HEAD} に適用する固定 textures 値
 */
public record GuiNavigationDestination(
    @NotNull Material icon,
    @NotNull String screenName,
    @Nullable String iconTexture
) {
    /**
     * 通常アイコンの戻り先情報を生成します。
     *
     * @param icon 戻り先 GUI のアイコン素材
     * @param screenName 戻り先画面名
     */
    public GuiNavigationDestination(@NotNull Material icon, @NotNull String screenName) {
        this(icon, screenName, null);
    }
}
