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
    static final String MIDGARD_SAVANNA_SUNBIRD = "midgard_savanna_sunbird";
    static final String FORGOTTEN_ALDA_COLOSSUS = "forgotten_alda_colossus";

    private static final Map<String, BossMechanicProfile> PROFILES = Map.of(
        TWILIGHT_COLOSSUS,
        new BossMechanicProfile(
            List.of(
                List.of(Mechanic.COLOSSUS_QUAKE, Mechanic.COLOSSUS_RUNE_LANES),
                List.of(Mechanic.COLOSSUS_RUNE_LANES, Mechanic.COLOSSUS_COLLAPSE, Mechanic.COLOSSUS_QUAKE),
                List.of(Mechanic.COLOSSUS_COLLAPSE, Mechanic.COLOSSUS_QUAKE, Mechanic.COLOSSUS_RUNE_LANES)
            ),
            List.of(105L, 90L, 75L),
            List.of(0.70D, 0.35D)
        ),
        MIDGARD_SAVANNA_SUNBIRD,
        new BossMechanicProfile(
            List.of(
                List.of(
                    Mechanic.SUNBIRD_SOLAR_NOVA,
                    Mechanic.SUNBIRD_SOLAR_FLARE,
                    Mechanic.SUNBIRD_SUNSTRIKE,
                    Mechanic.SUNBIRD_SOLAR_BEAM,
                    Mechanic.SUNBIRD_SOLAR_FLARE,
                    Mechanic.SUNBIRD_SUNSTRIKE
                ),
                List.of(
                    Mechanic.SUNBIRD_SOLAR_NOVA,
                    Mechanic.SUNBIRD_SOLAR_BEAM,
                    Mechanic.SUNBIRD_SUNSTRIKE,
                    Mechanic.SUNBIRD_SOLAR_FLARE,
                    Mechanic.SUNBIRD_SOLAR_BEAM,
                    Mechanic.SUNBIRD_SUNSTRIKE
                )
            ),
            List.of(60L, 46L),
            List.of(0.30D)
        ),
        FORGOTTEN_ALDA_COLOSSUS,
        new BossMechanicProfile(
            List.of(
                List.of(),
                List.of(Mechanic.ALDA_RUIN_SHOCKWAVE),
                List.of(Mechanic.ALDA_PRIMORDIAL_COLLAPSE)
            ),
            List.of(120L, 105L, 90L),
            List.of(0.70D, 0.35D)
        )
    );

    private final List<List<Mechanic>> rotations;
    private final List<Long> intervals;
    private final List<Double> healthThresholds;

    private BossMechanicProfile(
        @NotNull List<List<Mechanic>> rotations,
        @NotNull List<Long> intervals,
        @NotNull List<Double> healthThresholds
    ) {
        this.rotations = rotations.stream().map(List::copyOf).toList();
        this.intervals = List.copyOf(intervals);
        this.healthThresholds = List.copyOf(healthThresholds);
    }

    static @Nullable BossMechanicProfile find(@NotNull String bossId) {
        return PROFILES.get(bossId);
    }

    static int phaseFor(double currentHealth, double maxHealth) {
        return PROFILES.get(TWILIGHT_COLOSSUS).phaseForHealth(currentHealth, maxHealth);
    }

    int phaseForHealth(double currentHealth, double maxHealth) {
        if (maxHealth <= 0.0D) {
            return 1;
        }
        double ratio = Math.clamp(currentHealth / maxHealth, 0.0D, 1.0D);
        for (int index = healthThresholds.size() - 1; index >= 0; index--) {
            if (ratio <= healthThresholds.get(index)) {
                return index + 2;
            }
        }
        return 1;
    }

    @Nullable Mechanic mechanic(int phase, int actionIndex) {
        List<Mechanic> rotation = rotations.get(Math.clamp(phase, 1, rotations.size()) - 1);
        if (rotation.isEmpty()) {
            return null;
        }
        return rotation.get(Math.floorMod(actionIndex, rotation.size()));
    }

    long intervalTicks(int phase) {
        return intervals.get(Math.clamp(phase, 1, intervals.size()) - 1);
    }

    enum Mechanic {
        COLOSSUS_QUAKE,
        COLOSSUS_RUNE_LANES,
        COLOSSUS_COLLAPSE,
        ALDA_RUIN_SHOCKWAVE,
        ALDA_PRIMORDIAL_COLLAPSE,
        ALDA_PRIMORDIAL_COLLAPSE_FOLLOW_UP,
        SUNBIRD_SOLAR_FLARE,
        SUNBIRD_SUNSTRIKE,
        SUNBIRD_SOLAR_BEAM,
        SUNBIRD_SOLAR_NOVA,
        SUNBIRD_BIRD_METEOR,
        SUNBIRD_RETURN_TACKLE
    }
}
