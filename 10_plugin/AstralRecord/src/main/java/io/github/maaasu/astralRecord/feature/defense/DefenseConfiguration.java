package io.github.maaasu.astralRecord.feature.defense;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** APIで固定した一開催分の設定です。未確定の値は省略を許可しません。 */
public record DefenseConfiguration(
    String worldName, double coreX, double coreY, double coreZ,
    double entryX, double entryY, double entryZ, List<Point> spawnPoints,
    double coreMaxHealth, double coreDefense, int baseSpawnCount, int spawnIntervalSeconds,
    int maxSpawnsPerWave, int maxSpawnsPerTick, int maxAliveEnemies,
    int retaliationSeconds, double retaliationRange,
    double playerSpawnCoefficient, double playerHealthCoefficient, double playerDamageCoefficient,
    double postMaxLevelHealthCoefficient, double postMaxLevelDamageCoefficient,
    int registrationLeadSeconds, int reconnectGraceSeconds, int rewardEveryLevels,
    int minimumActiveSeconds, List<RewardTier> rewardTiers,
    int reviveProtectionSeconds, String reviveShieldMode
) {
    private static final Gson GSON = new Gson();
    private static final List<String> REQUIRED = List.of("worldName", "coreX", "coreY", "coreZ",
        "entryX", "entryY", "entryZ", "spawnPoints", "coreMaxHealth", "coreDefense", "baseSpawnCount",
        "spawnIntervalSeconds", "maxSpawnsPerWave", "maxSpawnsPerTick", "maxAliveEnemies",
        "retaliationSeconds", "retaliationRange", "playerSpawnCoefficient", "playerHealthCoefficient",
        "playerDamageCoefficient", "postMaxLevelHealthCoefficient", "postMaxLevelDamageCoefficient",
        "registrationLeadSeconds", "reconnectGraceSeconds", "rewardEveryLevels", "minimumActiveSeconds",
        "rewardTiers", "reviveProtectionSeconds", "reviveShieldMode");

    /**
     * @param json 必須値がすべて確定した設定
     * @return 独立した設定スナップショット
     * @throws IllegalArgumentException 欠損、型違い、無限値、範囲外の場合
     */
    public static DefenseConfiguration parse(JsonObject json) {
        for (String key : REQUIRED) if (!json.has(key) || json.get(key).isJsonNull())
            throw new IllegalArgumentException("Missing defense setting: " + key);
        DefenseConfiguration value = GSON.fromJson(json, DefenseConfiguration.class);
        Objects.requireNonNull(value.worldName());
        if (value.worldName().isBlank() || value.spawnPoints() == null || value.spawnPoints().isEmpty()
            || value.rewardTiers() == null || value.rewardTiers().isEmpty())
            throw new IllegalArgumentException("Empty defense settings");
        for (String key : REQUIRED) if (json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isNumber()) {
            double number = json.get(key).getAsDouble();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite defense setting: " + key);
        }
        if (value.coreMaxHealth() <= 0 || value.coreDefense() < 0 || value.baseSpawnCount() < 1
            || value.spawnIntervalSeconds() < 1 || value.maxSpawnsPerWave() < 1 || value.maxSpawnsPerTick() < 1
            || value.maxAliveEnemies() < 1 || value.retaliationSeconds() < 0 || value.retaliationRange() <= 0
            || value.playerSpawnCoefficient() < 0 || value.playerHealthCoefficient() < 0
            || value.playerDamageCoefficient() < 0 || value.postMaxLevelHealthCoefficient() < 0
            || value.postMaxLevelDamageCoefficient() < 0 || value.registrationLeadSeconds() < 1
            || value.reconnectGraceSeconds() < 0 || value.rewardEveryLevels() < 1
            || value.minimumActiveSeconds() < 0 || value.reviveProtectionSeconds() < 0
            || !List.of("EMPTY", "FULL").contains(value.reviveShieldMode()))
            throw new IllegalArgumentException("Invalid defense settings");
        for (Point point : value.spawnPoints()) if (point == null || !point.finite())
            throw new IllegalArgumentException("Invalid defense spawn point");
        for (RewardTier tier : value.rewardTiers()) if (tier == null || tier.requiredDefenseLevel() < 1
            || tier.orbQuantity() < 1 || tier.orbItemId() == null || tier.orbItemId().isBlank())
            throw new IllegalArgumentException("Invalid defense reward tier");
        return value;
    }

    /** @param world 所有ワールド @return 防衛対象の座標 */
    public Location coreLocation(World world) { return new Location(world, coreX, coreY, coreZ); }

    /** @param world 所有ワールド @return 入場・自動復活地点 */
    public Location entryLocation(World world) { return new Location(world, entryX, entryY, entryZ); }

    /** @param elapsedSeconds 戦闘の経過秒 @return 全チャンネル共通の防衛レベル */
    public static int level(long elapsedSeconds) {
        return (int) Math.min(Integer.MAX_VALUE, 1L + Math.max(0L, elapsedSeconds) / 30L);
    }

    /** 不変のワールド内座標です。 */
    public record Point(double x, double y, double z) {
        /** @return 有限な三次元座標ならtrue */
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
        /** @param world 所有ワールド @return Bukkit座標 */
        public Location location(World world) { return new Location(world, x, y, z); }
    }

    /** この段階以降で採用するオーブ報酬です。付与の正本はAPIにあります。 */
    public record RewardTier(int requiredDefenseLevel, String orbItemId, int orbQuantity) { }
}
