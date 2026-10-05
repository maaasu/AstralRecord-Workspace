package io.github.maaasu.astralRecord.feature.fishing.model;

import org.bukkit.entity.EntityType;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** 魚、ワールド別釣り場、操作ルールのAPI契約。生成時に不正な定義を拒否します。 */
public final class FishingMasters {
    private FishingMasters() { }

    /** @param value IDまたは参照 @return 前後空白・参照接頭辞を除いた小文字ID */
    public static String id(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing fishing reference");
        String trimmed = value.trim();
        int colon = trimmed.indexOf(':');
        String result = (colon < 0 ? trimmed : trimmed.substring(colon + 1)).trim().toLowerCase(Locale.ROOT);
        if (result.isEmpty()) throw new IllegalArgumentException("Empty fishing ID");
        return result;
    }

    /** 魚種の見た目・釣り性能・確定釣果。追加Lootは省略可能です。 */
    public record FishDefinition(int schemaVersion, String id, String name, EntityType entityType,
                                 double maxHealth, double swimSpeed, double fleeSpeed, double pullPerTick,
                                 double biteChancePercent, double landFleeSpeed, double hopSpeed,
                                 String caughtItemId, int caughtAmount, String lootTableId) {
        public FishDefinition {
            version(schemaVersion);
            id = FishingMasters.id(id);
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Missing fish name: " + id);
            if (entityType != EntityType.COD && entityType != EntityType.SALMON
                && entityType != EntityType.TROPICAL_FISH && entityType != EntityType.PUFFERFISH) {
                throw new IllegalArgumentException("Unsupported fishing entity: " + id);
            }
            positive(maxHealth, 100000, "maxHealth");
            positive(swimSpeed, 1, "swimSpeed");
            positive(fleeSpeed, 1, "fleeSpeed");
            nonnegative(pullPerTick, 100, "pullPerTick");
            positive(biteChancePercent, 100, "biteChancePercent");
            positive(landFleeSpeed, 1, "landFleeSpeed");
            positive(hopSpeed, 1, "hopSpeed");
            caughtItemId = FishingMasters.id(caughtItemId);
            integer(caughtAmount, 1, 64, "caughtAmount");
            lootTableId = lootTableId == null || lootTableId.isBlank() ? null : FishingMasters.id(lootTableId);
        }
    }

    /** 操作周期・探索・時間制限。衝突判定の精度と探索予算はPluginで固定します。 */
    public record Rules(int schemaVersion, String id, double searchRadius, int fishCountMin, int fishCountMax,
                        int waitingTicks, int attackTicks, int turnTicks, double reelMultiplier,
                        double slackDistance, int landingTicks, int hangingTicks, int escapeTicks,
                        int nibbleTicks, int nibbleCycleTicks, double nibbleDistance, int reelCooldownTicks) {
        public Rules {
            version(schemaVersion);
            id = FishingMasters.id(id);
            positive(searchRadius, 32, "searchRadius");
            integer(fishCountMin, 1, 32, "fishCountMin");
            integer(fishCountMax, fishCountMin, 32, "fishCountMax");
            integer(waitingTicks, 1, 72000, "waitingTicks");
            integer(attackTicks, 2, 72000, "attackTicks");
            integer(turnTicks, attackTicks, 72000, "turnTicks");
            positive(reelMultiplier, 100, "reelMultiplier");
            nonnegative(slackDistance, 100, "slackDistance");
            integer(landingTicks, 1, 72000, "landingTicks");
            integer(hangingTicks, 1, 72000, "hangingTicks");
            integer(escapeTicks, 1, 72000, "escapeTicks");
            integer(nibbleTicks, 1, 72000, "nibbleTicks");
            integer(nibbleCycleTicks, 1, nibbleTicks, "nibbleCycleTicks");
            nonnegative(nibbleDistance, 1, "nibbleDistance");
            integer(reelCooldownTicks, 1, 72000, "reelCooldownTicks");
        }
    }

    /** 1回の魚生成に使う出現率。正の百分率で指定します。 */
    public record FishChance(String fishId, double chancePercent) {
        public FishChance {
            fishId = FishingMasters.id(fishId);
            positive(chancePercent, 100, "chancePercent");
        }
    }

    /** 両端を含む釣りレベル範囲。魚の出現率合計は100%です。 */
    public record LevelRange(int minLevel, int maxLevel, List<FishChance> fish) {
        public LevelRange {
            integer(minLevel, 0, Integer.MAX_VALUE, "minLevel");
            integer(maxLevel, minLevel, Integer.MAX_VALUE, "maxLevel");
            fish = List.copyOf(fish);
            if (fish.isEmpty()) throw new IllegalArgumentException("Empty fishing distribution");
            var ids = new HashSet<String>();
            double total = 0;
            for (FishChance chance : fish) {
                if (!ids.add(chance.fishId())) throw new IllegalArgumentException("Duplicate fish: " + chance.fishId());
                total += chance.chancePercent();
            }
            if (Math.abs(total - 100.0) > 1.0E-6) throw new IllegalArgumentException("Fishing chances must total 100%");
        }
    }

    /** ワールドと竿の釣りレベルだけで選択する釣り場。座標範囲は持ちません。 */
    public record Area(int schemaVersion, String id, String name, String worldId, String rulesId,
                       boolean enabled, List<LevelRange> levelRanges) {
        public Area {
            version(schemaVersion);
            id = FishingMasters.id(id);
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Missing fishing area name");
            worldId = FishingMasters.id(worldId);
            rulesId = FishingMasters.id(rulesId);
            levelRanges = levelRanges.stream().sorted(Comparator.comparingInt(LevelRange::minLevel)).toList();
            if (levelRanges.isEmpty()) throw new IllegalArgumentException("Missing fishing level ranges");
            for (int index = 1; index < levelRanges.size(); index++) {
                if (levelRanges.get(index).minLevel() <= levelRanges.get(index - 1).maxLevel()) {
                    throw new IllegalArgumentException("Overlapping fishing level ranges: " + id);
                }
            }
        }
    }

    private static void version(int value) {
        if (value != 1) throw new IllegalArgumentException("Unsupported fishing schemaVersion");
    }

    private static void integer(int value, int min, int max, String field) {
        if (value < min || value > max) throw new IllegalArgumentException("Invalid fishing " + field);
    }

    private static void positive(double value, double max, String field) {
        if (!Double.isFinite(value) || value <= 0 || value > max) throw new IllegalArgumentException("Invalid fishing " + field);
    }

    private static void nonnegative(double value, double max, String field) {
        if (!Double.isFinite(value) || value < 0 || value > max) throw new IllegalArgumentException("Invalid fishing " + field);
    }
}
