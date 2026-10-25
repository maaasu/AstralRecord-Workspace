package io.github.maaasu.astralRecord.infrastructure.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;

/** Dev の Plugin データフォルダ内だけから、宣言済み原稿を検証して保持します。 */
public final class LocalMasterOverlay {
    private static final String MOB_PREFIX = "40.features.mob/enemy/";
    private static final String SPAWNER_PREFIX = "41.features.mob.spawner/";
    private static final String MANIFEST = "manifest.yml";
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    private final Path root;
    private final BooleanSupplier enabled;
    private final BooleanSupplier debugMode;
    private final ThreadLocal<Snapshot> prepared = new ThreadLocal<>();
    private volatile Snapshot published = Snapshot.empty();

    public LocalMasterOverlay(@NotNull Path pluginDataFolder, boolean enabled) {
        this(pluginDataFolder, () -> enabled, () -> true);
    }

    public LocalMasterOverlay(@NotNull Path pluginDataFolder, @NotNull BooleanSupplier enabled,
                              @NotNull BooleanSupplier debugMode) {
        this.root = pluginDataFolder.toAbsolutePath().normalize().resolve("local-master-overlay");
        this.enabled = enabled;
        this.debugMode = debugMode;
    }

    /** 有効時は manifest と全原稿の整合を検証し、不変の原稿 snapshot を返します。 */
    public @NotNull Snapshot loadSnapshot() {
        if (!enabled.getAsBoolean()) return Snapshot.empty();
        if (!debugMode.getAsBoolean()) {
            throw new IllegalStateException("Local master overlay requires plugin.debugMode=true");
        }
        try {
            requirePlainDirectory(root);
            Path manifestPath = root.resolve(MANIFEST);
            byte[] manifestBytes = readPlainFile(manifestPath);
            YamlConfiguration manifest = parseYaml(decode(manifestBytes), MANIFEST);
            if (!(manifest.get("schemaVersion") instanceof Integer version) || version != 1
                    || !manifest.getKeys(false).equals(Set.of("schemaVersion", "files"))) {
                throw new IllegalStateException("Invalid local master manifest schema");
            }
            List<?> declared = manifest.getList("files");
            if (declared == null || declared.isEmpty()) {
                throw new IllegalStateException("Empty local master manifest");
            }
            Map<String, Entry> mobs = new LinkedHashMap<>();
            Map<String, Entry> spawners = new LinkedHashMap<>();
            Set<String> paths = new HashSet<>();
            for (Object item : declared) {
                if (!(item instanceof Map<?, ?> row)) {
                    throw new IllegalStateException("Invalid local master manifest entry");
                }
                if (!row.keySet().equals(Set.of("path", "sha256"))) {
                    throw new IllegalStateException("Invalid local master manifest entry");
                }
                Object rawPath = row.get("path");
                Object rawHash = row.get("sha256");
                if (!(rawPath instanceof String relative) || !(rawHash instanceof String expected)
                        || !expected.matches(SHA256_PATTERN) || !paths.add(relative)) {
                    throw new IllegalStateException("Invalid or duplicate local master manifest entry");
                }
                boolean mob = relative.startsWith(MOB_PREFIX);
                String prefix = mob ? MOB_PREFIX : SPAWNER_PREFIX;
                if ((!mob && !relative.startsWith(SPAWNER_PREFIX))
                        || !relative.endsWith(".yml") || relative.substring(prefix.length()).isBlank()
                        || relative.substring(prefix.length()).contains("/")
                        || relative.contains("\\") || relative.contains(":")
                        || relative.contains("..")) {
                    throw new IllegalStateException("Unsupported local master path: " + relative);
                }
                String[] segments = relative.split("/");
                Path file = root;
                for (int index = 0; index < segments.length; index++) {
                    String segment = segments[index];
                    if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                        throw new IllegalStateException("Invalid local master path: " + relative);
                    }
                    file = file.resolve(segment);
                    if (index < segments.length - 1) requirePlainDirectory(file);
                }
                byte[] bytes = readPlainFile(file);
                if (!HexFormat.of().formatHex(sha256(bytes)).equals(expected)) {
                    throw new IllegalStateException("Local master hash mismatch: " + relative);
                }
                String content = decode(bytes);
                YamlConfiguration yaml = parseYaml(content, relative);
                String id = yaml.getString("id");
                if (id == null || !id.matches("[A-Za-z0-9_]+")
                        || !relative.substring(prefix.length()).equals("v1." + id + ".yml")) {
                    throw new IllegalStateException("Local master ID/file mismatch: " + relative);
                }
                if (!(yaml.get("schemaVersion") instanceof Integer schema) || schema != 1) {
                    throw new IllegalStateException("Invalid local master schema version: " + relative);
                }
                if (mob && (!"MOB".equals(yaml.getString("type"))
                        || !"ENEMY".equals(yaml.getString("category")))) {
                    throw new IllegalStateException("Only ENEMY mob overlays are allowed: " + relative);
                }
                if (mob) validateMobLevels(yaml, relative);
                else validateSpawner(yaml, relative);
                Map<String, Entry> destination = mob ? mobs : spawners;
                if (destination.putIfAbsent(id, new Entry(relative, content)) != null) {
                    throw new IllegalStateException("Duplicate local master ID: " + id);
                }
            }
            if (mobs.isEmpty() || spawners.isEmpty()) {
                throw new IllegalStateException("Local overlay requires enemy and spawner files");
            }
            return new Snapshot(Collections.unmodifiableMap(new LinkedHashMap<>(mobs)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(spawners)));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read local master overlay", failure);
        }
    }

    public @NotNull Snapshot currentSnapshot() {
        Snapshot snapshot = prepared.get();
        return snapshot == null ? published : snapshot;
    }

    /** 準備済み Mob 一覧に対して、ローカルスポナーの全参照を検証します。 */
    public void validateReferences(@NotNull Map<String, MobTemplate> mobs) {
        for (Entry entry : currentSnapshot().spawners().values()) {
            YamlConfiguration yaml = parseYaml(entry.content(), entry.path());
            for (Map<?, ?> row : yaml.getMapList("spawnMobs")) {
                String raw = String.valueOf(row.get("mobId"));
                String id = raw.startsWith("mob:") ? raw.substring(4) : raw;
                MobTemplate template = mobs.get(id);
                if (template == null) {
                    throw new IllegalStateException("Unknown local spawner mob: " + entry.path() + " -> " + id);
                }
                Object levelValue = row.get("level");
                if (levelValue instanceof Number number) {
                    int level = number.intValue();
                    boolean available = template.levelProfiles().isEmpty()
                            ? template.level() == level
                            : template.levelProfiles().stream().anyMatch(profile -> profile.level() == level);
                    if (!available) {
                        throw new IllegalStateException("Unknown local spawner mob level: "
                                + entry.path() + " -> " + id + " Lv." + level);
                    }
                }
            }
        }
    }

    /** ローカル Mob の item・loot table・Mob スキル参照を準備済みマスタへ照合します。 */
    public void validateMobDependencies(@NotNull Predicate<String> itemExists,
                                        @NotNull Predicate<String> lootExists,
                                        @NotNull Predicate<String> mobSkillExists) {
        for (Entry entry : currentSnapshot().mobs().values()) {
            YamlConfiguration yaml = parseYaml(entry.content(), entry.path());
            validateDependencyNode(yaml, entry.path(), itemExists, lootExists, mobSkillExists);
        }
    }

    private static void validateDependencyNode(Object node, String path,
                                               Predicate<String> itemExists,
                                               Predicate<String> lootExists,
                                               Predicate<String> mobSkillExists) {
        if (node instanceof ConfigurationSection section) {
            for (String key : section.getKeys(false)) {
                validateDependencyEntry(key, section.get(key), path, itemExists, lootExists, mobSkillExists);
            }
        } else if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> validateDependencyEntry(String.valueOf(key), value, path,
                    itemExists, lootExists, mobSkillExists));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> validateDependencyNode(value, path, itemExists, lootExists, mobSkillExists));
        }
    }

    private static void validateDependencyEntry(String key, Object value, String path,
                                                Predicate<String> itemExists,
                                                Predicate<String> lootExists,
                                                Predicate<String> mobSkillExists) {
        if (key.equals("ref") && value instanceof String ref) {
            if (ref.startsWith("item:") && !itemExists.test(ref.substring(5))) {
                throw new IllegalStateException("Unknown local mob item reference: " + path + " -> " + ref);
            }
            if (ref.startsWith("loot_table:") && !lootExists.test(ref.substring(11))) {
                throw new IllegalStateException("Unknown local mob loot reference: " + path + " -> " + ref);
            }
        }
        if (key.equals("skills") && value instanceof List<?> skills) {
            for (Object skill : skills) {
                if (!(skill instanceof Map<?, ?> map) || !(map.get("id") instanceof String id)
                        || !mobSkillExists.test(id)) {
                    throw new IllegalStateException("Unknown local mob skill reference: " + path);
                }
            }
        }
        validateDependencyNode(value, path, itemExists, lootExists, mobSkillExists);
    }

    private static void validateMobLevels(YamlConfiguration yaml, String path) {
        int base = yaml.getInt("level", -1);
        if (base < 1 || yaml.getString("entityType") == null) {
            throw new IllegalStateException("Invalid local mob base level/entity: " + path);
        }
        Set<Integer> levels = new HashSet<>();
        for (Map<?, ?> row : yaml.getMapList("levels")) {
            Object value = row.get("level");
            if (!(value instanceof Number number) || number.intValue() < 1
                    || number.doubleValue() != number.intValue() || !levels.add(number.intValue())) {
                throw new IllegalStateException("Invalid local mob level profile: " + path);
            }
        }
    }

    private static void validateSpawner(YamlConfiguration yaml, String path) {
        List<Map<?, ?>> rows = new ArrayList<>(yaml.getMapList("spawnMobs"));
        if (rows.isEmpty()) throw new IllegalStateException("Local spawner has no mobs: " + path);
        for (Map<?, ?> row : rows) {
            Object raw = row.get("mobId");
            if (!(raw instanceof String id) || id.isBlank() || id.equals("mob:")) {
                throw new IllegalStateException("Invalid local spawner mob ID: " + path);
            }
            Object level = row.get("level");
            if (level != null && (!(level instanceof Number number) || number.intValue() < 1
                    || number.doubleValue() != number.intValue())) {
                throw new IllegalStateException("Invalid local spawner level: " + path);
            }
        }
    }

    public void replaceSnapshot(@NotNull Snapshot snapshot) { published = snapshot; }

    public <T> T withSnapshot(@NotNull Snapshot snapshot, @NotNull Supplier<T> action) {
        Snapshot previous = prepared.get();
        prepared.set(snapshot);
        try { return action.get(); }
        finally {
            if (previous == null) prepared.remove();
            else prepared.set(previous);
        }
    }

    /** API 配列を保ちながら同じ ID のローカル原稿で置換します。 */
    public static <T> List<T> mergeById(@NotNull List<T> api, @NotNull Map<String, T> local,
                                        @NotNull Function<T, String> id) {
        Map<String, T> merged = new LinkedHashMap<>();
        for (T value : api) merged.put(id.apply(value), value);
        merged.putAll(local);
        return List.copyOf(merged.values());
    }

    public static @NotNull YamlConfiguration parseYaml(@NotNull String content, @NotNull String path) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(content); }
        catch (InvalidConfigurationException failure) {
            throw new IllegalStateException("Invalid local master YAML: " + path, failure);
        }
        return yaml;
    }

    /** API の YAML→JSON 変換と同じ型のまま既存 Mob parser へ渡します。 */
    public static @NotNull JsonObject asJson(@NotNull YamlConfiguration yaml) {
        return (JsonObject) jsonValue(yaml);
    }

    private static JsonElement jsonValue(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof ConfigurationSection section) {
            JsonObject result = new JsonObject();
            for (String key : section.getKeys(false)) result.add(key, jsonValue(section.get(key)));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject result = new JsonObject();
            map.forEach((key, item) -> result.add(String.valueOf(key), jsonValue(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            JsonArray result = new JsonArray();
            list.forEach(item -> result.add(jsonValue(item)));
            return result;
        }
        if (value instanceof Boolean bool) return new JsonPrimitive(bool);
        if (value instanceof Number number) return new JsonPrimitive(number);
        return new JsonPrimitive(String.valueOf(value));
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static void requirePlainDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Local master directory is not plain: " + path);
        }
    }

    private static byte[] readPlainFile(Path path) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther()) {
            throw new IOException("Local master file is not plain: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || after.isOther()
                || before.size() != bytes.length || after.size() != bytes.length
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new IOException("Local master changed while reading: " + path);
        }
        return bytes;
    }

    public record Entry(String path, String content) { }

    public record Snapshot(Map<String, Entry> mobs, Map<String, Entry> spawners) {
        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of()); }
    }
}
