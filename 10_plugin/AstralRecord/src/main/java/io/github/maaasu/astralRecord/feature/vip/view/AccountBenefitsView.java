package io.github.maaasu.astralRecord.feature.vip.view;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.vip.model.AccountBenefitsSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** プレイヤー情報とTAB用の特典表示を共通化します。 */
public final class AccountBenefitsView {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        .withZone(ZoneId.of("Asia/Tokyo"));
    private AccountBenefitsView() { }

    /** 回数が0でない場合だけ表示し、その下にVIP期限と残日数を追加します。 */
    public static void appendLore(List<Component> lore, UUID accountId) {
        var state = state(accountId);
        if (state.instancePriorityUses() > 0) lore.add(line("インスタンス優先接続回数: "
            + state.instancePriorityUses() + "回", NamedTextColor.AQUA));
        if (state.expiresAt() == null) return;
        lore.add(line("VIP: " + state.tier() + " / 残り" + state.remainingDays() + "日", NamedTextColor.GOLD));
        lore.add(line("有効期限: " + DATE.format(state.expiresAt()) + " (日本時間)", NamedTextColor.GRAY));
        if (state.tier().equals("ASTRALDER") && state.donerExpiresAt() != null
            && state.donerExpiresAt().isAfter(state.astralderExpiresAt())) {
            lore.add(line("DONER: ASTRALDER終了後に再開", NamedTextColor.AQUA));
            lore.add(line("DONER期限: " + DATE.format(state.donerExpiresAt()), NamedTextColor.GRAY));
        }
    }

    /** TABのアカウント名だけにVIP色を適用し、スロット番号の装飾を独立させます。 */
    public static Component tabName(AccountModel account) {
        String tier = state(account.getUuid()).tier();
        Component name = Component.text(account.getAccountName());
        if (tier.equals("DONER")) name = name.color(NamedTextColor.AQUA);
        else if (tier.equals("ASTRALDER")) name = name.color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
        return Component.empty().append(name).append(Component.text("#" + account.getSlotIndex(), NamedTextColor.GRAY)
            .decoration(TextDecoration.BOLD, false));
    }

    /** 初期化前は通常状態として描画します。 */
    private static AccountBenefitsSnapshot state(UUID accountId) {
        var plugin = AstralRecord.getInstance();
        return plugin == null || plugin.getAccountBenefitsService() == null ? AccountBenefitsSnapshot.EMPTY
            : plugin.getAccountBenefitsService().current(accountId);
    }

    /** GUI loreの既定斜体を解除した表示行を作ります。 */
    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
