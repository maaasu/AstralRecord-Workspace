package io.github.maaasu.astralRecord.feature.currency.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * ゴールド額面の合計値計算と高額面優先の分解を提供します。
 */
public final class GoldCurrencyCalculator {
    private GoldCurrencyCalculator() {
    }

    /**
     * 各額面の所持数をゴールド価値へ換算し、合計します。
     * 演算結果が {@link Long#MAX_VALUE} を超える場合は上限値へ丸めます。
     *
     * @param amountResolver 額面ごとの所持数を返す関数
     * @return 合計ゴールド価値
     */
    public static long totalValue(@NotNull ToLongFunction<GoldDenomination> amountResolver) {
        long total = 0L;
        for (GoldDenomination denomination : GoldDenomination.values()) {
            long amount = Math.max(0L, amountResolver.applyAsLong(denomination));
            total = saturatingAdd(total, saturatingMultiply(amount, denomination.goldValue()));
        }
        return total;
    }

    /**
     * 指定ゴールド値を最上位額面から順に最少個数へ分解します。
     *
     * @param goldValue 分解する0以上のゴールド値
     * @return 額面ごとの個数
     */
    public static @NotNull Map<GoldDenomination, Long> decompose(long goldValue) {
        long remaining = Math.max(0L, goldValue);
        Map<GoldDenomination, Long> amounts = new LinkedHashMap<>();
        GoldDenomination[] denominations = GoldDenomination.values();
        for (int index = denominations.length - 1; index >= 0; index--) {
            GoldDenomination denomination = denominations[index];
            long amount = remaining / denomination.goldValue();
            if (amount > 0L) {
                amounts.put(denomination, amount);
                remaining %= denomination.goldValue();
            }
        }
        return amounts;
    }

    /**
     * 低額面から優先して指定額を支払い、必要な場合だけ最小限の上位額面を崩した残高を返します。
     * 所持額が不足する場合は変更後残高を生成しません。
     *
     * @param amountResolver 額面ごとの所持数を返す関数
     * @param spendGoldValue 支払う0以上のゴールド値
     * @return 支払後の額面別残高。不足または桁あふれ時は {@code null}
     */
    public static @Nullable Map<GoldDenomination, Long> spendSmallestFirst(
        @NotNull ToLongFunction<GoldDenomination> amountResolver,
        long spendGoldValue
    ) {
        if (spendGoldValue < 0L) {
            return null;
        }
        EnumMap<GoldDenomination, Long> balances = new EnumMap<>(GoldDenomination.class);
        for (GoldDenomination denomination : GoldDenomination.values()) {
            balances.put(denomination, Math.max(0L, amountResolver.applyAsLong(denomination)));
        }
        if (totalValue(denomination -> balances.getOrDefault(denomination, 0L)) < spendGoldValue) {
            return null;
        }

        long remaining = spendGoldValue;
        for (GoldDenomination denomination : GoldDenomination.values()) {
            if (remaining == 0L) {
                break;
            }
            long value = denomination.goldValue();
            long balance = balances.get(denomination);
            long wholeAmount = Math.min(balance, remaining / value);
            if (wholeAmount > 0L) {
                balance -= wholeAmount;
                remaining -= wholeAmount * value;
                balances.put(denomination, balance);
            }
            if (remaining == 0L || balance == 0L) {
                continue;
            }

            balances.put(denomination, balance - 1L);
            long changeValue = value - remaining;
            for (Map.Entry<GoldDenomination, Long> change : decompose(changeValue).entrySet()) {
                long current = balances.get(change.getKey());
                if (current > Long.MAX_VALUE - change.getValue()) {
                    return null;
                }
                balances.put(change.getKey(), current + change.getValue());
            }
            remaining = 0L;
        }
        if (remaining != 0L) {
            return null;
        }

        Map<GoldDenomination, Long> result = new LinkedHashMap<>();
        for (GoldDenomination denomination : GoldDenomination.values()) {
            long amount = balances.get(denomination);
            if (amount > 0L) {
                result.put(denomination, amount);
            }
        }
        return result;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
