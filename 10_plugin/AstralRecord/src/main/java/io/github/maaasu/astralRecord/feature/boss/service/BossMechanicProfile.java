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
    static final String DARK_DRAGON = "dark_dragon";
    static final String FENRIR_WORLDBREAKER = "fenrir_worldbreaker";

    private static final Map<String, BossMechanicProfile> PROFILES = Map.of(
        TWILIGHT_COLOSSUS,
        new BossMechanicProfile(
            List.of(
                List.of(Mechanic.COLOSSUS_QUAKE, Mechanic.COLOSSUS_RUNE_LANES),
                List.of(Mechanic.COLOSSUS_RUNE_LANES, Mechanic.COLOSSUS_COLLAPSE, Mechanic.COLOSSUS_QUAKE),
                List.of(Mechanic.COLOSSUS_COLLAPSE, Mechanic.COLOSSUS_QUAKE, Mechanic.COLOSSUS_RUNE_LANES)
            ),
            List.of(105L, 90L, 75L)
        ),
        DARK_DRAGON,
        new BossMechanicProfile(
            List.of(
                List.of(Mechanic.DRAGON_SHADOW_BREATH, Mechanic.DRAGON_METEOR),
                List.of(Mechanic.DRAGON_WING_GUST, Mechanic.DRAGON_METEOR, Mechanic.DRAGON_SHADOW_BREATH),
                List.of(Mechanic.DRAGON_WORLD_BREAK, Mechanic.DRAGON_METEOR, Mechanic.DRAGON_SHADOW_BREATH)
            ),
            List.of(110L, 90L, 70L)
        ),
        FENRIR_WORLDBREAKER,
        new BossMechanicProfile(
            List.of(
                List.of(Mechanic.FENRIR_CHARGE, Mechanic.FENRIR_RIFT_HOWL),
                List.of(Mechanic.FENRIR_FALLING_RUIN, Mechanic.FENRIR_CHARGE, Mechanic.FENRIR_RIFT_HOWL),
                List.of(Mechanic.FENRIR_LAST_HUNT, Mechanic.FENRIR_FALLING_RUIN, Mechanic.FENRIR_CHARGE)
            ),
            List.of(100L, 82L, 64L)
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
        COLOSSUS_COLLAPSE,
        DRAGON_SHADOW_BREATH,
        DRAGON_METEOR,
        DRAGON_WING_GUST,
        DRAGON_WORLD_BREAK,
        FENRIR_CHARGE,
        FENRIR_RIFT_HOWL,
        FENRIR_FALLING_RUIN,
        FENRIR_LAST_HUNT
    }
}
