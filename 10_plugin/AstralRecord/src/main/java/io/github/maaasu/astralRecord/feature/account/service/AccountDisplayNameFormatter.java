package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
     * 有効なVIPは名前だけに色を付け、{@code #slotIndex} は灰色で装飾します。
     *
     * @param account 表示対象アカウント
     * @return アカウント表示コンポーネント
     */
    public static @NotNull Component toComponent(@NotNull AccountModel account) {
        Component name = nameComponent(account.getAccountName(), tier(account));
        return name.append(Component.text("#" + account.getSlotIndex(), NamedTextColor.GRAY)
            .decoration(TextDecoration.BOLD, false));
    }

    /**
     * オーバーヘッド表示など、既存のレガシーカラーコードを受け取る API 用の値を返します。
     * VIP色とslotの灰色を付けます。{@code &} は内部表現であり、表示前に変換します。
     *
     * @param account 表示対象アカウント
     * @return VIP色を含み得る{@code accountName&r&7#slotIndex}
     */
    public static @NotNull String toLegacy(@NotNull AccountModel account) {
        String prefix = switch (tier(account)) {
            case "DONER" -> "&b";
            case "ASTRALDER" -> "&6&l";
            default -> "";
        };
        return prefix + account.getAccountName() + "&r&7#" + account.getSlotIndex();
    }

    public static @NotNull String toPlain(@NotNull String accountName, int slotIndex) {
        return accountName + "#" + slotIndex;
    }

    public static @NotNull Component toComponent(@NotNull String accountName, int slotIndex) {
        return Component.text(accountName)
            .append(Component.text("#" + slotIndex, NamedTextColor.GRAY));
    }

    /** APIイベント等でアカウント名だけを表示する際にも同じVIP色を適用します。 */
    public static @NotNull Component nameComponent(@NotNull String name, @NotNull String tier) {
        return switch (tier) {
            case "DONER" -> Component.text(name, NamedTextColor.AQUA);
            case "ASTRALDER" -> Component.text(name, NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
            default -> Component.text(name);
        };
    }

    /** TABと同じクラス・クラスレベル・AFK・名前色に現在チャンネルを添えます。 */
    public static @NotNull Component playerLabel(@NotNull AstPlayer player) {
        var plugin = AstralRecord.getInstance();
        if (plugin == null || plugin.getPlayerClassService() == null)
            return toComponent(player.getAccount());
        Component name = plugin.getPlayerClassService().playerListLabel(player);
        String channel = ConfigProperties.getInstance().getNetworkChannelName();
        if (channel == null || channel.isBlank()) return name;
        return Component.text("[" + channel + "] ", NamedTextColor.GRAY).append(name);
    }

    private static String tier(AccountModel account) {
        var plugin = AstralRecord.getInstance();
        return plugin == null || plugin.getAccountBenefitsService() == null ? "NONE"
            : plugin.getAccountBenefitsService().current(account.getUuid()).tier();
    }
}
