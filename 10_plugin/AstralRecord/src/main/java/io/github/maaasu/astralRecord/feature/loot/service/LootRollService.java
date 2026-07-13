package io.github.maaasu.astralRecord.feature.loot.service;

import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootRollResult;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * 解決済みルートテーブルの独立確率抽選を担当します。
 */
public class LootRollService {

    /**
     * ルートテーブルを抽選します。
     *
     * <p>各 content を {@code rate}% で独立判定し、成功件数が pool の {@code pick}
     * 上限を超えた場合だけ無作為に絞り込みます。抽選回数と採用上限の範囲は実行ごとに確定します。</p>
     *
     * @param lootModel 参照解決済みルートテーブル
     * @return 選択順を維持した抽選結果
     */
    public @NotNull List<LootRollResult> roll(@NotNull LootModel lootModel) {
        return roll(lootModel, ThreadLocalRandom.current());
    }

    @NotNull
    List<LootRollResult> roll(@NotNull LootModel lootModel, @NotNull RandomGenerator random) {
        List<LootRollResult> rewards = new ArrayList<>();
        int rolls = rollRange(lootModel.getMinRolls(), lootModel.getMaxRolls(), random);
        for (int rollIndex = 0; rollIndex < rolls; rollIndex++) {
            for (LootPoolModel pool : lootModel.getPools()) {
                int pickLimit = Math.min(
                    pool.getContents().size(),
                    rollRange(pool.getMinPick(), pool.getMaxPick(), random)
                );
                if (pickLimit <= 0) {
                    continue;
                }

                List<LootContent> successfulContents = new ArrayList<>();
                for (LootContent content : pool.getContents()) {
                    double configuredRate = clampRate(content.getRate());
                    if (configuredRate >= 100.0D
                        || (configuredRate > 0.0D && random.nextDouble(100.0D) < configuredRate)) {
                        successfulContents.add(content);
                    }
                }

                selectUpToLimit(successfulContents, pickLimit, random);
                int resultCount = Math.min(pickLimit, successfulContents.size());
                for (int resultIndex = 0; resultIndex < resultCount; resultIndex++) {
                    LootContent content = successfulContents.get(resultIndex);
                    int amount = rollAmount(content, random);
                    if (amount <= 0) {
                        continue;
                    }
                    rewards.add(new LootRollResult(
                        content.getItemId(),
                        amount,
                        clampRate(content.getRate())
                    ));
                }
            }
        }
        return List.copyOf(rewards);
    }

    private void selectUpToLimit(
        @NotNull List<LootContent> successfulContents,
        int pickLimit,
        @NotNull RandomGenerator random
    ) {
        if (successfulContents.size() <= pickLimit) {
            return;
        }
        for (int index = 0; index < pickLimit; index++) {
            int selectedIndex = random.nextInt(index, successfulContents.size());
            Collections.swap(successfulContents, index, selectedIndex);
        }
    }

    private int rollAmount(@NotNull LootContent content, @NotNull RandomGenerator random) {
        int minAmount = Math.min(content.getMinAmount(), content.getMaxAmount());
        int maxAmount = Math.max(content.getMinAmount(), content.getMaxAmount());
        if (maxAmount <= 0) {
            return 0;
        }
        return minAmount == maxAmount ? minAmount : random.nextInt(minAmount, maxAmount + 1);
    }

    private int rollRange(int first, int second, @NotNull RandomGenerator random) {
        int minValue = Math.max(0, Math.min(first, second));
        int maxValue = Math.max(0, Math.max(first, second));
        return minValue == maxValue ? minValue : random.nextInt(minValue, maxValue + 1);
    }

    private double clampRate(double rate) {
        return Math.max(0.0D, Math.min(100.0D, rate));
    }
}
