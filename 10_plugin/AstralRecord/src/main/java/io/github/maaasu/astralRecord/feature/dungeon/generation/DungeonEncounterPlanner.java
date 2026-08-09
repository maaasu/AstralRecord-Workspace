package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.SplittableRandom;

/** 部屋ごとの Mob 構成を seed から決める純粋な抽選器です。 */
public final class DungeonEncounterPlanner {
    private static final long ENCOUNTER_SALT = 0xA54FF53A5F1D36F1L;

    /**
     * 通常部屋の Mob を抽選します。開始部屋だけレベル上限を先に適用します。
     *
     * @param definition ダンジョン定義
     * @param pool 受付時点の Mob テンプレート
     * @param firstCombatRoom 開始部屋か
     * @param seed ダンジョン seed
     * @param roomId 部屋 ID
     * @return スポーン順の Mob テンプレート
     */
    public @NotNull List<MobTemplate> planNormalRoom(
            @NotNull DungeonDefinition definition,
            @NotNull List<WeightedTemplate> pool,
            boolean firstCombatRoom,
            long seed,
            int roomId
    ) {
        List<WeightedTemplate> eligible = firstCombatRoom
                ? pool.stream()
                .filter(entry -> entry.template().level()
                        <= definition.encounter().firstCombatRoomMaxMobLevel())
                .toList()
                : List.copyOf(pool);
        if (eligible.isEmpty()) {
            throw new IllegalArgumentException("No eligible normal mob for room " + roomId);
        }

        SplittableRandom random = random(seed, roomId);
        DungeonDefinition.IntRange range = definition.encounter().mobsPerRoom();
        int count = range.min() + random.nextInt(range.max() - range.min() + 1);
        java.util.ArrayList<MobTemplate> result = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(choose(eligible, random).template());
        }
        return List.copyOf(result);
    }

    private @NotNull WeightedTemplate choose(
            @NotNull List<WeightedTemplate> pool,
            @NotNull SplittableRandom random
    ) {
        int total = pool.stream().mapToInt(WeightedTemplate::weight).sum();
        int roll = random.nextInt(total);
        for (WeightedTemplate mob : pool) {
            roll -= mob.weight();
            if (roll < 0) {
                return mob;
            }
        }
        return pool.getLast();
    }

    private @NotNull SplittableRandom random(long seed, int roomId) {
        return new SplittableRandom(seed ^ ENCOUNTER_SALT * (roomId + 1L));
    }

    /** 重み付き Mob テンプレートです。 */
    public record WeightedTemplate(@NotNull MobTemplate template, int weight) {
    }
}
