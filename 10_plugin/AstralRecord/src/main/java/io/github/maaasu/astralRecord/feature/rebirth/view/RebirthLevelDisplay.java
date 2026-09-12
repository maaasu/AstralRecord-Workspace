package io.github.maaasu.astralRecord.feature.rebirth.view;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

/** 転生中の現在レベルと転生前レベルを共通の色で整形します。 */
public final class RebirthLevelDisplay {
    private RebirthLevelDisplay() {
    }

    /**
     * GUI向けのレベル表示を返します。
     *
     * @param prefix レベル数値の前に付ける表示
     * @param account 表示対象アカウント
     * @return 通常時は黄色、転生中は水色の現在値と灰色括弧の転生前レベル
     */
    public static @NotNull Component component(@NotNull String prefix, @NotNull AccountModel account) {
        Component label = Component.text(prefix);
        Integer originalLevel = account.getRebirthOriginalLevel();
        if (originalLevel == null || originalLevel <= account.getLevel()) {
            return label.append(Component.text(account.getLevel(), NamedTextColor.YELLOW));
        }
        return label
            .append(Component.text(account.getLevel(), NamedTextColor.AQUA))
            .append(Component.text(" (" + originalLevel + ")", NamedTextColor.GRAY));
    }

    /**
     * legacy color codeを使うスコアボード向けのレベル数値を返します。
     *
     * @param account 表示対象アカウント
     * @return 色コードを含むレベル数値
     */
    public static @NotNull String legacy(@NotNull AccountModel account) {
        Integer originalLevel = account.getRebirthOriginalLevel();
        if (originalLevel == null || originalLevel <= account.getLevel()) {
            return ColorCodeUtil.YELLOW + Integer.toString(account.getLevel());
        }
        return ColorCodeUtil.AQUA + Integer.toString(account.getLevel())
            + ColorCodeUtil.GRAY + " (" + originalLevel + ")";
    }
}
