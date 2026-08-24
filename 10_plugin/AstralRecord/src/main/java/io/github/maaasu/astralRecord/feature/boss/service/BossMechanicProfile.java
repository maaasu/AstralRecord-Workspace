package io.github.maaasu.astralRecord.feature.boss.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * ボス固有ギミックのフェーズ別ローテーションを保持します。
 */
final class BossMechanicProfile {

    static final String TWILIGHT_COLOSSUS = "twilight_colossus";

    private static final Map<String, BossMechanicProfile> PROFILES = Map.of(
        TWILIGHT_COLOSSUS,
        new BossMechanicProfile(
            List.of(
                List.of(Mechanic.COLOSSUS_QUAKE, Mechanic.COLOSSUS_RUNE_LANES),
                List.of(Mechanic.COLOSSUS_RUNE_LANES, Mechanic.COLOSSUS_COLLAPSE, Mechanic.COLOSSUS_QUAKE),
                List.of(Mechanic.COLOSSUS_COLLAPSE, Mechanic.COLOSSUS_QUAKE, Mechanic.COLOSSUS_RUNE_LANES)
            ),
            List.of(105L, 90L, 75L)
        )
    );

    private final List<List<Mechanic>> rotations;
    private final List<Long> intervals;

    private BossMechanicProfile(
        @NotNull List<List<Mechanic>> rotations,
        @NotNull List<Long> intervals
    ) {
        this.rotations = rotations.stream().map(List::copyOf).toList();
        this.intervals = List.copyOf(intervals);
    }

    static @Nullable BossMechanicProfile find(@NotNull String bossId) {
        return PROFILES.get(bossId);
    }

    static int phaseFor(double currentHealth, double maxHealth) {
        if (maxHealth <= 0.0D) {
            return 1;
        }
        double ratio = Math.clamp(currentHealth / maxHealth, 0.0D, 1.0D);
        if (ratio <= 0.35D) {
            return 3;
        }
        if (ratio <= 0.70D) {
            return 2;
        }
        return 1;
    }

    @NotNull Mechanic mechanic(int phase, int actionIndex) {
        List<Mechanic> rotation = rotations.get(Math.clamp(phase, 1, 3) - 1);
        return rotation.get(Math.floorMod(actionIndex, rotation.size()));
    }

    long intervalTicks(int phase) {
        return intervals.get(Math.clamp(phase, 1, 3) - 1);
    }

    enum Mechanic {
        COLOSSUS_QUAKE,
        COLOSSUS_RUNE_LANES,
        COLOSSUS_COLLAPSE
    }
}
