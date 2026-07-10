package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobMoneyDrop;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob 撃破時のドロップ抽選を担うサービス。
 *
 * <p>{@code drops.items} は確率抽選、{@code drops.lootTable} は別途 loot feature の参照解決を行う想定。
 * 本実装ではまず {@code drops.items} のみを抽選し、{@code lootTable} は将来統合する。</p>
 */
public class MobDropService {

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
        return roll(drops, killer);
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
            double effectiveRate = item.rate() + (item.luckAffected() ? luckBonus : 0.0);
            if (rng.nextDouble(0.0, 100.0) >= effectiveRate) continue;

            int amount = parseAmount(item.amount(), rng);
            if (amount <= 0) continue;

            items.add(new MobDropResultItem(item.itemId(), amount, item.rate()));
        }

        int money = 0;
        MobMoneyDrop moneyConfig = drops.money();
        if (moneyConfig != null) {
            int min = Math.min(moneyConfig.min(), moneyConfig.max());
            int max = Math.max(moneyConfig.min(), moneyConfig.max());
            money = min == max ? min : rng.nextInt(min, max + 1);
        }

        return new MobDropResult(items, drops.exp(), money);
    }

    /**
     * キラーの幸運ステータスを解決します。{@link AstPlayer} 側に対応 API が無ければ 0 を返します。
     * 将来的に {@code StatusService.getValue(killer, StatusType.LUCK)} を呼ぶ想定です。
     *
     * @param killer プレイヤー
     * @return LUCK ステータス値（現状は常に 0.0）
     */
    @SuppressWarnings("unused")
    private double resolveLuck(@NotNull AstPlayer killer) {
        // AstPlayer 側のステータスサービスが揃うまでは 0 で固定。
        // StatusType.LUCK への将来参照を意図的に維持する。
        StatusType ignored = StatusType.LUCK;
        return 0.0;
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
