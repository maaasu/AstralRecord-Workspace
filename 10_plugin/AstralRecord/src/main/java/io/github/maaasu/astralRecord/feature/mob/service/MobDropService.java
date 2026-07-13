package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.service.LevelDifferenceCalculator;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootRollResult;
import io.github.maaasu.astralRecord.feature.loot.service.LootRollService;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobMoneyDrop;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob 撃破時のドロップ抽選を担うサービス。
 *
 * <p>{@code drops.items} の確率抽選と、{@code drops.lootTable} の独立確率抽選を結合します。</p>
 */
public class MobDropService {

    private final LootService lootService;
    private final LootRollService lootRollService;

    /**
     * LootTable 参照を使用しない互換用サービスを構築します。
     */
    public MobDropService() {
        this(null);
    }

    /**
     * Mob・採集ドロップ用サービスを構築します。
     *
     * @param lootService 起動時ロード済み LootTable の参照サービス。未指定時は直接ドロップのみ抽選
     */
    public MobDropService(@Nullable LootService lootService) {
        this.lootService = lootService;
        this.lootRollService = new LootRollService();
    }

    /**
     * Mob テンプレートとキラーからドロップを確定します。
     *
     * @param template 撃破された Mob テンプレート
     * @param killer   キラー（{@code null} 可）
     * @return 当選元の設定確率を含むドロップ確定結果
     */
    @NotNull
    public MobDropResult roll(@NotNull MobTemplate template, @Nullable AstPlayer killer) {
        MobDropConfig drops = template.drops();
        MobDropResult result = roll(drops, killer);
        if (killer == null) {
            return result;
        }
        return new MobDropResult(
                result.items(),
                LevelDifferenceCalculator.scaleExperience(
                        result.exp(),
                        killer.getAccount().getLevel(),
                        template.level()
                ),
                result.money()
        );
    }

    /**
     * Mob 以外の feature からも同じ drops 定義形式でドロップを抽選します。
     *
     * @param drops  drops 定義
     * @param killer 取得者
     * @return 当選元の設定確率を含むドロップ確定結果
     */
    @NotNull
    public MobDropResult roll(@Nullable MobDropConfig drops, @Nullable AstPlayer killer) {
        if (drops == null) {
            return new MobDropResult(List.of(), 0, 0);
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double luckBonus = killer == null ? 0.0 : resolveLuck(killer) * 0.05;

        List<MobDropResultItem> items = new ArrayList<>();
        for (MobDropItem item : drops.items()) {
            double effectiveRate = clampRate(item.rate() + (item.luckAffected() ? luckBonus : 0.0D));
            if (rng.nextDouble(0.0, 100.0) >= effectiveRate) continue;

            int amount = parseAmount(item.amount(), rng);
            if (amount <= 0) continue;

            items.add(new MobDropResultItem(item.itemId(), amount, item.rate()));
        }
        appendLootTableDrops(items, drops.lootTable());

        int money = 0;
        MobMoneyDrop moneyConfig = drops.money();
        if (moneyConfig != null) {
            int min = Math.min(moneyConfig.min(), moneyConfig.max());
            int max = Math.max(moneyConfig.min(), moneyConfig.max());
            money = min == max ? min : rng.nextInt(min, max + 1);
        }

        return new MobDropResult(items, drops.exp(), money);
    }

    private void appendLootTableDrops(
        @NotNull List<MobDropResultItem> items,
        @Nullable String lootTableId
    ) {
        if (lootService == null || lootTableId == null || lootTableId.isBlank()) {
            return;
        }
        LootModel lootModel = lootService.getLoaded(lootTableId);
        if (lootModel == null) {
            return;
        }
        for (LootRollResult reward : lootRollService.roll(lootModel)) {
            items.add(new MobDropResultItem(
                reward.getItemId(),
                reward.getAmount(),
                reward.getConfiguredRate()
            ));
        }
    }

    /**
     * キラーの最新ステータススナップショットから幸運値を解決します。
     *
     * @param killer プレイヤー
     * @return 0 以上の LUCK ステータス値
     */
    private double resolveLuck(@NotNull AstPlayer killer) {
        StatusSnapshot snapshot = killer.getStatusSnapshot();
        return snapshot == null ? 0.0D : Math.max(0.0D, snapshot.rollValue(StatusType.LUCK));
    }

    private double clampRate(double rate) {
        return Math.max(0.0D, Math.min(100.0D, rate));
    }

    /**
     * 数量文字列を整数に解決します。{@code "1"} や {@code "1~3"} などを受け付け、
     * 不正な値は 0 を返します。
     *
     * @param amount 数量文字列
     * @param rng    乱数生成器
     * @return 解決された数量
     */
    private int parseAmount(@Nullable String amount, @NotNull ThreadLocalRandom rng) {
        if (amount == null || amount.isBlank()) return 1;
        int idx = amount.indexOf('~');
        try {
            if (idx < 0) {
                return Math.max(0, Integer.parseInt(amount.trim()));
            }
            int lo = Integer.parseInt(amount.substring(0, idx).trim());
            int hi = Integer.parseInt(amount.substring(idx + 1).trim());
            int min = Math.min(lo, hi);
            int max = Math.max(lo, hi);
            return min == max ? min : rng.nextInt(min, max + 1);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
