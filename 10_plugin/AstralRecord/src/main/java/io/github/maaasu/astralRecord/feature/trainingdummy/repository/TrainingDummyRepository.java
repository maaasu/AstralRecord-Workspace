package io.github.maaasu.astralRecord.feature.trainingdummy.repository;

import io.github.maaasu.astralRecord.feature.trainingdummy.model.TrainingDummyDefinition;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plugin data folder の training-dummies.yml を読み書きします。 */
public final class TrainingDummyRepository {
    private static final String FILE_NAME = "training-dummies.yml";
    private final Plugin plugin;

    public TrainingDummyRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /** 全カカシ定義を読み込みます。 */
    public @NotNull List<TrainingDummyDefinition> loadAll() {
        File file = file();
        if (!file.exists()) return List.of();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<TrainingDummyDefinition> definitions = new ArrayList<>();
        for (Map<?, ?> row : yaml.getMapList("dummies")) {
            String id = string(row.get("id"));
            String world = string(row.get("world"));
            if (id == null || id.isBlank() || world == null || world.isBlank()) continue;
            definitions.add(new TrainingDummyDefinition(
                    id, world, number(row.get("x")), number(row.get("y")), number(row.get("z")),
                    (float) number(row.get("yaw")), number(row.get("maxHealth"), 100.0D),
                    number(row.get("defense")), number(row.get("magicDefense")),
                    bool(row.get("shieldEnabled")), number(row.get("shieldMax"), 10.0D),
                    (long) number(row.get("recoveryIntervalTicks"), 40.0D)
            ));
        }
        return List.copyOf(definitions);
    }

    /** 全カカシ定義を保存します。 */
    public void saveAll(@NotNull Iterable<TrainingDummyDefinition> definitions) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("AstralRecord training dummy placements and shared test settings."));
        yaml.set("version", 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TrainingDummyDefinition definition : definitions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", definition.id());
            row.put("world", definition.worldName());
            row.put("x", definition.x()); row.put("y", definition.y()); row.put("z", definition.z()); row.put("yaw", definition.yaw());
            row.put("maxHealth", definition.maxHealth()); row.put("defense", definition.defense()); row.put("magicDefense", definition.magicDefense());
            row.put("shieldEnabled", definition.shieldEnabled()); row.put("shieldMax", definition.shieldMax());
            row.put("recoveryIntervalTicks", definition.recoveryIntervalTicks());
            rows.add(row);
        }
        yaml.set("dummies", rows);
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Plugin data folder を作成できません");
        try {
            yaml.save(file());
        } catch (IOException ex) {
            throw new IllegalStateException("training-dummies.yml を保存できません", ex);
        }
    }

    private @NotNull File file() { return new File(plugin.getDataFolder(), FILE_NAME); }
    private String string(Object value) { return value == null ? null : value.toString(); }
    private double number(Object value) { return number(value, 0.0D); }
    private double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }
    private boolean bool(Object value) { return value instanceof Boolean bool && bool; }
}
