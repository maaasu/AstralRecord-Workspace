package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

/** アカウント名とスロット番号を、ゲーム内表示用の形式へ変換します。 */
public final class AccountDisplayNameFormatter {
    private static final int ASTRALDER_GOLD = 0xCBA96B;
    private static final int ASTRALDER_GLOW = 0xFFF0CD;

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
        return legacy(account, tier(account));
    }

    private static String legacy(AccountModel account, String vipTier) {
        String prefix = switch (vipTier) {
            case "DONER" -> "&b";
            case "ASTRALDER" -> "&6&l";
            default -> "";
        };
        return prefix + account.getAccountName() + "&r&7#" + account.getSlotIndex();
    }

    /**
     * 頭上TextDisplayのASTRALDER名へ、TABと同じ金色の光を移動させます。
     *
     * @param account 表示対象アカウント
     * @param frame 光の位置を決める更新フレーム
     * @return 名前部分だけが変化し、スロット番号は灰色のレガシー文字列
     */
    public static @NotNull String toAnimatedLegacy(@NotNull AccountModel account, long frame) {
        String vipTier = tier(account);
        if (!"ASTRALDER".equals(vipTier)) return legacy(account, vipTier);
        int[] letters = account.getAccountName().codePoints().toArray();
        int center = (int) Math.floorMod(frame, letters.length + 6L) - 2;
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < letters.length; index++) {
            double strength = Math.max(0.0D, 1.0D - Math.abs(index - center) / 3.0D);
            appendHexColor(result, blend(ASTRALDER_GOLD, ASTRALDER_GLOW, strength * strength));
            result.append("&l").appendCodePoint(letters[index]);
        }
        return result.append("&r&7#").append(account.getSlotIndex()).toString();
    }

    /**
     * レガシー文字列のメッセージへ、送信時点のASTRALDER名の光を適用します。
     *
     * @param account 表示対象アカウント
     * @return 送信時点の名前装飾と灰色のスロット番号
     */
    public static @NotNull String toMessageLegacy(@NotNull AccountModel account) {
        return toAnimatedLegacy(account, System.nanoTime() / 100_000_000L);
    }

    /**
     * メッセージ送信時点のASTRALDER名を、TABと同じ金色の光で描画します。
     *
     * @param account 表示対象アカウント
     * @param frame 光の位置を決める送信時点のフレーム
     * @return 名前だけに光を適用し、スロット番号を灰色にしたComponent
     */
    public static @NotNull Component toAnimatedComponent(@NotNull AccountModel account, long frame) {
        return messageNameComponent(account.getAccountName(), tier(account), frame)
            .append(Component.text("#" + account.getSlotIndex(), NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, false));
    }

    /**
     * イベント通知でアカウント名だけを表示する場合の送信時点の装飾です。
     *
     * @param name アカウント名
     * @param tier VIP種別
     * @param frame 光の位置を決める送信時点のフレーム
     * @return ASTRALDERだけに移動する光を適用したComponent
     */
    public static @NotNull Component messageNameComponent(@NotNull String name, @NotNull String tier, long frame) {
        if (!"ASTRALDER".equals(tier)) return nameComponent(name, tier);
        int[] letters = name.codePoints().toArray();
        int center = (int) Math.floorMod(frame, letters.length + 6L) - 2;
        Component result = Component.empty();
        for (int index = 0; index < letters.length; index++) {
            double strength = Math.max(0.0D, 1.0D - Math.abs(index - center) / 3.0D);
            result = result.append(Component.text(new String(Character.toChars(letters[index])),
                TextColor.color(blend(ASTRALDER_GOLD, ASTRALDER_GLOW, strength * strength)), TextDecoration.BOLD));
        }
        return result;
    }

    private static void appendHexColor(StringBuilder target, int rgb) {
        target.append("&x");
        for (int shift = 20; shift >= 0; shift -= 4) {
            target.append('&').append(Character.toUpperCase(Character.forDigit((rgb >>> shift) & 0xF, 16)));
        }
    }

    private static int blend(int base, int highlight, double amount) {
        int red = (int) Math.round(((base >>> 16) & 0xFF)
            + (((highlight >>> 16) & 0xFF) - ((base >>> 16) & 0xFF)) * amount);
        int green = (int) Math.round(((base >>> 8) & 0xFF)
            + (((highlight >>> 8) & 0xFF) - ((base >>> 8) & 0xFF)) * amount);
        int blue = (int) Math.round((base & 0xFF) + ((highlight & 0xFF) - (base & 0xFF)) * amount);
        return (red << 16) | (green << 8) | blue;
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

    /** メッセージ向けにクラス・レベル・AFK・送信時点の名前の光・チャンネルを添えます。 */
    public static @NotNull Component playerLabel(@NotNull AstPlayer player) {
        long frame = System.nanoTime() / 100_000_000L;
        var plugin = AstralRecord.getInstance();
        if (plugin == null || plugin.getPlayerClassService() == null)
            return toAnimatedComponent(player.getAccount(), frame);
        Component name = plugin.getPlayerClassService().messageLabel(
            player, toAnimatedComponent(player.getAccount(), frame));
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
