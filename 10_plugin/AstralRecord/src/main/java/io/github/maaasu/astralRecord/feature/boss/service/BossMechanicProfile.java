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
                    Mechanic.SUNBIRD_SOLAR_NOVA,
                    Mechanic.SUNBIRD_SUNSTRIKE,
                    Mechanic.SUNBIRD_SOLAR_NOVA,
                    Mechanic.SUNBIRD_SOLAR_BEAM
                ),
                List.of(
                    Mechanic.SUNBIRD_SOLAR_NOVA,
                    Mechanic.SUNBIRD_SOLAR_BEAM,
                    Mechanic.SUNBIRD_SOLAR_NOVA,
                    Mechanic.SUNBIRD_SUNSTRIKE,
                    Mechanic.SUNBIRD_SOLAR_NOVA,
                    Mechanic.SUNBIRD_SOLAR_FLARE
                )
            ),
            List.of(60L, 46L),
            List.of(0.30D)
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

    @NotNull Mechanic mechanic(int phase, int actionIndex) {
        List<Mechanic> rotation = rotations.get(Math.clamp(phase, 1, rotations.size()) - 1);
        return rotation.get(Math.floorMod(actionIndex, rotation.size()));
    }

    long intervalTicks(int phase) {
        return intervals.get(Math.clamp(phase, 1, intervals.size()) - 1);
    }

    enum Mechanic {
        COLOSSUS_QUAKE,
        COLOSSUS_RUNE_LANES,
        COLOSSUS_COLLAPSE,
        SUNBIRD_SOLAR_FLARE,
        SUNBIRD_SUNSTRIKE,
        SUNBIRD_SOLAR_BEAM,
        SUNBIRD_SOLAR_NOVA,
        SUNBIRD_CORONA_COLLAPSE,
        SUNBIRD_RETURN_TACKLE
    }
}
