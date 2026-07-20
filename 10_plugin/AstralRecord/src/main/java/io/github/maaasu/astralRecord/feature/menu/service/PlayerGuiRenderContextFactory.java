package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
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
        this.currencyService = currencyService;
        this.statusService = statusService;
        this.skillTreeService = skillTreeService;
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
        return new PlayerGuiRenderContext(
            account,
            statusService.getStatus(astPlayer),
            skillTreeService.availableClassPoints(astPlayer),
            skillTreeService.availablePassivePoints(astPlayer),
            goldAmount,
            ReturnToBaseService.calculateGoldCost(accountLevel),
            captureEquipment(astPlayer.getBukkit().getInventory()),
            captureCurrencyBalances(account.getUuid())
        );
    }

    private @NotNull List<CurrencyDisplayEntry> captureCurrencyBalances(@NotNull UUID accountId) {
        List<CurrencyDisplayEntry> balances = new ArrayList<>();
        Map<String, Component> displayNamesById = new LinkedHashMap<>();
        for (ItemStack itemStack : currencyService.getCurrencyItemStacks(accountId)) {
            String currencyId = currencyService.getCurrencyItemId(itemStack);
            if (currencyId == null) {
                continue;
            }
            String canonicalId = ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(currencyId)
                ? ItemService.DEFAULT_CURRENCY_ITEM_ID
                : currencyId.toLowerCase(Locale.ROOT);
            Component displayName = GoldDenomination.GOLD.itemId().equalsIgnoreCase(canonicalId)
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
