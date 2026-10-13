package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.guide.service.GuideCompletionRewardService;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.menu.model.GuideMenuStatus;
import io.github.maaasu.astralRecord.feature.menu.model.CurrencyDisplayEntry;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.service.ReturnToBaseService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@link AstPlayer} と各 feature の現在値から GUI 描画コンテキストを生成します。
 */
public final class PlayerGuiRenderContextFactory {
    private final CurrencyService currencyService;
    private final StatusService statusService;
    private final SkillTreeService skillTreeService;
    private final MailService mailService;
    private final GuideService guideService;
    private final GuideCompletionRewardService guideCompletionRewardService;

    /**
     * GUI 描画コンテキスト生成ファクトリを初期化します。
     *
     * @param currencyService 通貨サービス
     * @param statusService ステータスサービス
     * @param skillTreeService スキルツリーサービス
     */
    public PlayerGuiRenderContextFactory(
        @NotNull CurrencyService currencyService,
        @NotNull StatusService statusService,
        @NotNull SkillTreeService skillTreeService
    ) {
        this(currencyService, statusService, skillTreeService, null, null, null);
    }

    /**
     * メニュー通知の取得済みcacheを含む描画コンテキスト生成元を初期化します。
     *
     * @param currencyService 通貨サービス
     * @param statusService ステータスサービス
     * @param skillTreeService スキルツリーサービス
     * @param mailService 未読表示cache。未接続の場合はnull
     * @param guideService ガイド進行cache。未接続の場合はnull
     * @param guideCompletionRewardService 全達成報酬cache。未接続の場合はnull
     */
    public PlayerGuiRenderContextFactory(
        @NotNull CurrencyService currencyService,
        @NotNull StatusService statusService,
        @NotNull SkillTreeService skillTreeService,
        @Nullable MailService mailService,
        @Nullable GuideService guideService,
        @Nullable GuideCompletionRewardService guideCompletionRewardService
    ) {
        this.currencyService = currencyService;
        this.statusService = statusService;
        this.skillTreeService = skillTreeService;
        this.mailService = mailService;
        this.guideService = guideService;
        this.guideCompletionRewardService = guideCompletionRewardService;
    }

    /**
     * 指定プレイヤーの現在値を一度だけ取得して描画コンテキストへ固定します。
     *
     * @param astPlayer 描画対象プレイヤー
     * @return GUI 描画コンテキスト
     */
    public @NotNull PlayerGuiRenderContext create(@NotNull AstPlayer astPlayer) {
        var account = astPlayer.getAccount();
        int accountLevel = Math.max(1, account.getLevel());
        long goldAmount = currencyService.getGoldAmount(account.getUuid());
        MailService.UnreadMailStatus mailStatus = mailService == null
            ? new MailService.UnreadMailStatus(null, false) : mailService.getCachedUnreadStatus(account.getUuid());
        return new PlayerGuiRenderContext(
            account,
            statusService.getStatus(astPlayer),
            skillTreeService.currentClassPointLabel(astPlayer),
            skillTreeService.availableClassPoints(astPlayer),
            skillTreeService.availablePassivePoints(astPlayer),
            goldAmount,
            ReturnToBaseService.calculateGoldCost(accountLevel),
            captureEquipment(astPlayer.getBukkit().getInventory()),
            captureCurrencyBalances(account.getUuid()),
            mailStatus.count(),
            mailStatus.refreshing(),
            guideMenuStatus(account.getUuid())
        );
    }

    /**
     * メインメニュー表示完了時の期限付き未読取得を開始します。通常のcreateでは通信しません。
     *
     * @param accountId 表示対象アカウントUUID
     * @param onChanged 主スレッドで表示状態が変わった時の再描画処理
     */
    public void refreshMenuNotifications(@NotNull UUID accountId, @NotNull Runnable onChanged) {
        if (mailService != null) mailService.refreshUnreadCountAsync(accountId, onChanged);
    }

    /**
     * 既存の装備・通貨描画値を維持し、通知だけを取得済みcacheから更新します。
     *
     * @param context 同じアカウントの描画コンテキスト
     * @return 通信せず通知状態だけを更新した不変コンテキスト
     */
    public @NotNull PlayerGuiRenderContext refreshNotificationSnapshot(@NotNull PlayerGuiRenderContext context) {
        UUID accountId = context.account().getUuid();
        var mailStatus = mailService == null ? new MailService.UnreadMailStatus(null, false)
            : mailService.getCachedUnreadStatus(accountId);
        return new PlayerGuiRenderContext(context.account(), context.statusSnapshot(), context.classPointLabel(),
            context.availableClassPoints(), context.availablePassivePoints(), context.goldAmount(),
            context.returnToBaseGoldCost(), context.equipment(), context.currencyBalances(),
            mailStatus.count(), mailStatus.refreshing(), guideMenuStatus(accountId));
    }

    /**
     * 現在のマスターと読込済みcacheだけでガイド状態を判定します。
     *
     * @param accountId 表示対象アカウントUUID
     * @return 進行未読込と報酬未確認を区別した状態。通信や報酬受取は実行しない
     */
    private @NotNull GuideMenuStatus guideMenuStatus(@NotNull UUID accountId) {
        if (guideService == null || !guideService.isProgressLoaded(accountId)) return GuideMenuStatus.UNKNOWN;
        if (guideService.getAll().isEmpty()) return GuideMenuStatus.NO_GUIDES;
        if (guideService.hasIncompleteGuide(accountId)) return GuideMenuStatus.INCOMPLETE;
        if (guideCompletionRewardService == null) return GuideMenuStatus.REWARD_UNCONFIRMED;
        if (guideCompletionRewardService.canClaim(accountId)) return GuideMenuStatus.REWARD_CLAIMABLE;
        var status = guideCompletionRewardService.getCachedStatus(accountId);
        if (status == null) return GuideMenuStatus.REWARD_UNCONFIRMED;
        if (status.claimed()) return GuideMenuStatus.REWARD_CLAIMED;
        return status.configured() ? GuideMenuStatus.REWARD_UNCONFIRMED : GuideMenuStatus.COMPLETED;
    }

    private @NotNull List<CurrencyDisplayEntry> captureCurrencyBalances(@NotNull UUID accountId) {
        List<CurrencyDisplayEntry> balances = new ArrayList<>();
        Map<String, Component> displayNamesById = new LinkedHashMap<>();
        for (ItemStack itemStack : currencyService.getCurrencyItemStacks(accountId)) {
            String currencyId = currencyService.getCurrencyItemId(itemStack);
            if (currencyId == null) {
                continue;
            }
            GoldDenomination denomination = GoldDenomination.findByItemId(currencyId);
            String canonicalId = denomination == null ? currencyId.toLowerCase(Locale.ROOT) : denomination.itemId();
            Component displayName = denomination == GoldDenomination.GOLD
                ? Component.text(GoldDenomination.GOLD.displayName(), NamedTextColor.GOLD)
                : displayName(itemStack);
            displayNamesById.putIfAbsent(canonicalId, displayName);
        }
        for (Map.Entry<String, Component> entry : displayNamesById.entrySet()) {
            long amount = currencyService.getDisplayCurrencyAmount(accountId, entry.getKey());
            if (amount <= 0L) {
                continue;
            }
            balances.add(new CurrencyDisplayEntry(entry.getKey(), entry.getValue(), amount));
        }
        return balances;
    }

    private @NotNull Component displayName(@NotNull ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return meta.displayName();
        }
        return Component.text(itemStack.getType().name(), NamedTextColor.WHITE);
    }

    private @NotNull PlayerEquipmentSnapshot captureEquipment(@NotNull PlayerInventory inventory) {
        return new PlayerEquipmentSnapshot(
            itemName(inventory.getHelmet()),
            itemName(inventory.getChestplate()),
            itemName(inventory.getLeggings()),
            itemName(inventory.getBoots())
        );
    }

    private @NotNull Component itemName(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return Component.text("なし", NamedTextColor.DARK_GRAY);
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return meta.displayName();
        }
        return Component.text(itemStack.getType().name(), NamedTextColor.WHITE);
    }
}
