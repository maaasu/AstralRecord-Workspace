package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import io.github.maaasu.astralRecord.feature.menu.MenuMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.feature.menu.model.CurrencyDisplayEntry;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 共通メニュー定義と描画時点の値から GUI 用 ItemStack を生成します。
 */
public final class MenuIconFactory {
    private static final int MAX_CURRENCY_DETAILS = 10;

    private MenuIconFactory() {
    }

    /**
     * 定義済みの基本説明だけを持つメニューアイコンを生成します。
     *
     * @param definition メニューアイコン定義
     * @return 新しく生成した GUI 用 ItemStack
     */
    public static @NotNull ItemStack create(@NotNull MenuIconDefinition definition) {
        return create(definition, List.of());
    }

    /**
     * 定義済みの基本説明へ画面固有の lore を追加してメニューアイコンを生成します。
     * 呼び出しごとに独立した ItemStack を生成します。
     *
     * @param definition メニューアイコン定義
     * @param additionalLore 画面固有の追加 lore
     * @return 新しく生成した GUI 用 ItemStack
     */
    public static @NotNull ItemStack create(
        @NotNull MenuIconDefinition definition,
        @NotNull List<Component> additionalLore
    ) {
        List<Component> lore = new ArrayList<>();
        if (!definition.getDescriptionJa().isBlank()) {
            lore.add(Component.text(definition.getDescriptionJa(), NamedTextColor.GRAY));
        }
        lore.addAll(additionalLore);
        return GuiItems.create(
            definition.getMaterial(),
            Component.text(definition.getDisplayNameJa(), definition.getColor()),
            lore,
            definition.getIconTexture()
        );
    }

    /**
     * プレイヤー情報アイコンを対象プレイヤーのスキン付きで生成します。
     *
     * @param player スキンを表示する対象プレイヤー
     * @return 対象プレイヤーのスキンを持つ GUI 用 ItemStack
     */
    public static @NotNull ItemStack createPlayerInfo(@NotNull Player player) {
        return createPlayerInfo(player, List.of());
    }

    /**
     * プレイヤー情報アイコンへ画面固有の lore を追加して生成します。
     *
     * @param player スキンを表示する対象プレイヤー
     * @param additionalLore 画面固有の追加 lore
     * @return 対象プレイヤーのスキンを持つ GUI 用 ItemStack
     */
    public static @NotNull ItemStack createPlayerInfo(
        @NotNull Player player,
        @NotNull List<Component> additionalLore
    ) {
        ItemStack itemStack = create(MenuIconDefinition.ACCOUNT_INFO, additionalLore);
        if (itemStack.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setPlayerProfile(player.getPlayerProfile());
            itemStack.setItemMeta(skullMeta);
        }
        return itemStack;
    }

    /**
     * 現在装備の共通表示行を生成します。
     *
     * @param context GUI 描画コンテキスト
     * @return 頭・胴・脚・足とアクセサリーの装備表示行
     */
    public static @NotNull List<Component> equipmentDetails(@NotNull PlayerGuiRenderContext context) {
        PlayerEquipmentSnapshot equipment = context.equipment();
        List<Component> details = new ArrayList<>(9);
        details.addAll(List.of(
            equipmentLine("頭", equipment.helmet()),
            equipmentLine("胴", equipment.chestplate()),
            equipmentLine("脚", equipment.leggings()),
            equipmentLine("足", equipment.boots())
        ));
        details.add(Component.text("アクセサリー", NamedTextColor.DARK_AQUA));
        details.add(Component.text("  アミュレット / タリスマン", NamedTextColor.GRAY));
        details.add(Component.text("  チャーム / コア / レリック", NamedTextColor.GRAY));
        return details;
    }

    /**
     * 合計ゴールドと所持通貨種別を仕切って表示する共通行を生成します。
     *
     * @param context GUI 描画コンテキスト
     * @return 所持通貨表示行
     */
    public static @NotNull List<Component> currencyDetails(@NotNull PlayerGuiRenderContext context) {
        List<CurrencyDisplayEntry> balances = context.currencyBalances();
        int visibleCount = Math.min(MAX_CURRENCY_DETAILS, balances.size());
        List<Component> details = new ArrayList<>(visibleCount + 5);
        details.add(Component.text("◆ 合計ゴールド ◆", NamedTextColor.GOLD, TextDecoration.BOLD));
        details.add(Component.text(
            String.format(Locale.JAPAN, "%,d G", context.goldAmount()),
            NamedTextColor.YELLOW
        ));
        details.add(Component.empty());
        details.add(Component.text("◆ 所持カレンシー ◆", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (int index = 0; index < visibleCount; index++) {
            CurrencyDisplayEntry balance = balances.get(index);
            details.add(Component.empty()
                .append(balance.displayName())
                .append(Component.text(
                    ": " + String.format(Locale.JAPAN, "%,d", balance.amount()),
                    NamedTextColor.YELLOW
                )));
        }
        if (balances.size() > MAX_CURRENCY_DETAILS) {
            details.add(Component.text("…", NamedTextColor.GRAY));
        }
        return details;
    }

    /**
     * 拠点帰還費用の共通表示行を生成します。
     *
     * @param context GUI 描画コンテキスト
     * @return 必要ゴールド表示行
     */
    public static @NotNull List<Component> returnToBaseDetails(@NotNull PlayerGuiRenderContext context) {
        return List.of(Component.text(
            "必要ゴールド " + context.returnToBaseGoldCost()
                + " (100 x Lv." + Math.max(1, context.account().getLevel()) + ")",
            NamedTextColor.YELLOW
        ));
    }

    /**
     * 未取得・更新中・直近取得の未読件数を区別して表示します。
     *
     * @param context 取得済み通知状態を含む描画コンテキスト
     * @return メールアイコンへ追加する表示行
     */
    public static @NotNull List<Component> mailDetails(@NotNull PlayerGuiRenderContext context) {
        List<Component> details = new ArrayList<>();
        if (context.unreadMailCount() == null) {
            details.add(PlayerMsgResource.getComponent(MenuMsgId.P_5641.getId()));
        } else {
            details.add(ColorCodeUtil.toComponent(PlayerMsgResource.format(
                MenuMsgId.P_5640.getId(), context.unreadMailCount()
            ), ""));
        }
        if (context.unreadMailRefreshing()) details.add(PlayerMsgResource.getComponent(MenuMsgId.P_5642.getId()));
        return details;
    }

    /**
     * ガイド進行と報酬の未確認・受取可能・受取済みを区別します。
     *
     * @param context 取得済みガイド状態を含む描画コンテキスト
     * @return ガイドアイコンへ追加する表示行
     */
    public static @NotNull List<Component> guideDetails(@NotNull PlayerGuiRenderContext context) {
        MenuMsgId id = switch (context.guideMenuStatus()) {
            case UNKNOWN -> MenuMsgId.P_5643;
            case INCOMPLETE -> MenuMsgId.P_5644;
            case REWARD_CLAIMABLE -> MenuMsgId.P_5645;
            case REWARD_CLAIMED -> MenuMsgId.P_5646;
            case COMPLETED -> MenuMsgId.P_5647;
            case REWARD_UNCONFIRMED -> MenuMsgId.P_5648;
            case NO_GUIDES -> MenuMsgId.P_5649;
        };
        return List.of(PlayerMsgResource.getComponent(id.getId()));
    }

    /**
     * 画面を開くショートカット用の操作案内を返します。
     *
     * @return 操作案内
     */
    public static @NotNull Component openHint() {
        return Component.text("クリックして開く", NamedTextColor.YELLOW);
    }

    /**
     * 即時実行ショートカット用の操作案内を返します。
     *
     * @return 操作案内
     */
    public static @NotNull Component executeHint() {
        return Component.text("クリックして実行", NamedTextColor.YELLOW);
    }

    private static @NotNull Component equipmentLine(@NotNull String label, @NotNull Component itemName) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(itemName);
    }
}
