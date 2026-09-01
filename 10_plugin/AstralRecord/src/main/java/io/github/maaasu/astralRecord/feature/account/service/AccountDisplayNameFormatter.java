package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

/** アカウント名とスロット番号を、ゲーム内表示用の形式へ変換します。 */
public final class AccountDisplayNameFormatter {
    private AccountDisplayNameFormatter() {
    }

    /**
     * 色指定を含まない表示名を返します。
     *
     * @param account 表示対象アカウント
     * @return {@code accountName#slotIndex}
     */
    public static @NotNull String toPlain(@NotNull AccountModel account) {
        return toPlain(account.getAccountName(), account.getSlotIndex());
    }

    /**
     * チャットや GUI へ埋め込める Adventure コンポーネントを返します。
     * {@code #slotIndex} の部分だけを灰色で装飾します。
     *
     * @param account 表示対象アカウント
     * @return アカウント表示コンポーネント
     */
    public static @NotNull Component toComponent(@NotNull AccountModel account) {
        return toComponent(account.getAccountName(), account.getSlotIndex());
    }

    /**
     * オーバーヘッド表示など、既存のレガシーカラーコードを受け取る API 用の値を返します。
     * {@code &7} は内部表現であり、プレイヤーへ文字列として送信しません。
     *
     * @param account 表示対象アカウント
     * @return {@code accountName&7#slotIndex}
     */
    public static @NotNull String toLegacy(@NotNull AccountModel account) {
        return account.getAccountName() + "&7#" + account.getSlotIndex();
    }

    public static @NotNull String toPlain(@NotNull String accountName, int slotIndex) {
        return accountName + "#" + slotIndex;
    }

    public static @NotNull Component toComponent(@NotNull String accountName, int slotIndex) {
        return Component.text(accountName)
            .append(Component.text("#" + slotIndex, NamedTextColor.GRAY));
    }
}
