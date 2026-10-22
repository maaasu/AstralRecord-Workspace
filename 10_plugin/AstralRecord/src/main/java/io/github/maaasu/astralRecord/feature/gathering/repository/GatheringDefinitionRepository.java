package io.github.maaasu.astralRecord.feature.gathering.repository;

import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition.GatheringSound;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition.GatheringSoundConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobMoneyDrop;
import io.github.maaasu.astralRecord.infrastructure.api.RuntimeFilebaseRepository;
import io.github.maaasu.astralRecord.infrastructure.api.RuntimeFilebaseSnapshot;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GatheringDefinitionRepository {
    private static final String RELATIVE_PATH = "42.features.gathering";

    public @NotNull List<GatheringDefinition> findAll() {
        List<GatheringDefinition> result = new ArrayList<>();
        for (RuntimeFilebaseSnapshot.Entry entry : RuntimeFilebaseRepository.getInstance()
                .currentSnapshot().files(RELATIVE_PATH)) {
            if (!entry.name().endsWith(".yml") || entry.name().contains("YAML")) {
                continue;
            }
            GatheringDefinition definition = parse(entry.yaml());
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    /**
     * YAMLから採集定義を生成します。level未指定時は1として読み込みます。
     *
     * @param yaml 採集定義YAML
     * @return 有効なIDを持つ採集定義。ID不在時はnull
     */
    private @Nullable GatheringDefinition parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            return null;
        }

        Material displayBlock = resolveMaterial(yaml.getString("displayBlock"), Material.STONE);
        return new GatheringDefinition(
                yaml.getInt("schemaVersion", 1),
                id,
                yaml.getString("category", "MINING"),
                yaml.getString("name", id),
                yaml.getInt("level", 1),
                yaml.getInt("maxHealth", 1),
                displayBlock.isBlock() ? displayBlock : Material.STONE,
                new Vector3f(
                        (float) yaml.getDouble("displayScale.x", 1.0D),
                        (float) yaml.getDouble("displayScale.y", 1.0D),
                        (float) yaml.getDouble("displayScale.z", 1.0D)
                ),
                yaml.getStringList("requiredToolTags").stream()
                        .map(value -> value.trim().toUpperCase(Locale.ROOT))
                        .filter(value -> !value.isBlank())
                        .toList(),
                parseDrops(yaml),
                parseSounds(yaml)
        );
    }

    private @NotNull MobDropConfig parseDrops(@NotNull YamlConfiguration yaml) {
        int exp = yaml.getInt("drops.exp", 0);
        MobMoneyDrop money = null;
        if (yaml.contains("drops.money")) {
            money = new MobMoneyDrop(
                    yaml.getInt("drops.money.min", 0),
                    yaml.getInt("drops.money.max", 0)
            );
        }

        List<MobDropItem> items = new ArrayList<>();
        for (var map : yaml.getMapList("drops.items")) {
            Object rawItemId = map.get("itemId");
            if (rawItemId == null) {
                continue;
            }
            String itemId = stripPrefix(rawItemId.toString());
            if (itemId.isBlank()) {
                continue;
            }
            items.add(new MobDropItem(
                    itemId,
                    doubleValue(map.get("rate"), 0.0D),
                    stringValue(map.get("amount"), "1"),
                    booleanValue(map.get("luckAffected"), true),
                    booleanValue(map.get("hidden"), false)
            ));
        }
        return new MobDropConfig(exp, money, items, stripPrefix(yaml.getString("drops.lootTable")));
    }

    private @NotNull GatheringSoundConfig parseSounds(@NotNull YamlConfiguration yaml) {
        return new GatheringSoundConfig(
                parseSound(yaml, "sounds.hit"),
                parseSound(yaml, "sounds.break")
        );
    }

    private @Nullable GatheringSound parseSound(@NotNull YamlConfiguration yaml, @NotNull String path) {
        String soundKey = trimToNull(yaml.getString(path + ".sound"));
        if (soundKey == null) {
            return null;
        }
        return new GatheringSound(
                soundKey,
                (float) yaml.getDouble(path + ".volume", 1.0D),
                (float) yaml.getDouble(path + ".pitch", 1.0D)
        );
    }

    private @NotNull Material resolveMaterial(@Nullable String raw, @NotNull Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private @NotNull String stripPrefix(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        int index = raw.indexOf(':');
        return (index < 0 ? raw : raw.substring(index + 1)).trim();
    }

    private @Nullable String trimToNull(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private double doubleValue(@Nullable Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean booleanValue(@Nullable Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private @NotNull String stringValue(@Nullable Object value, @NotNull String fallback) {
        return value == null ? fallback : value.toString();
    }
}
