package io.github.maaasu.astralrecordgeyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.util.Identifier;

/** Bedrockパックと一緒に公開された、JavaアイテムとBedrockモデルの対応。 */
record ItemModel(String id, String baseItem, int customModelData) {
    private static final String CATALOG = "astralrecord_item_models.json";
    private static final String ATLAS = "textures/item_texture.json";
    private static final String HELD_ATTACHABLE = "attachables/trident.entity.json";
    private static final String HELD_CONTROLLERS = "render_controllers/held_weapon.render_controllers.json";
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int MAX_HELD_ASSET_BYTES = 4 * 1024 * 1024;

    record Catalog(Set<ItemModel> models, Set<Integer> heldWeaponModels) {}

    static Catalog read(ZipFile zip) throws IOException {
        if (zip.getEntry(CATALOG) == null) return new Catalog(Set.of(), Set.of());

        Set<String> paths = new HashSet<>();
        for (var entries = zip.entries(); entries.hasMoreElements();) {
            String path = entries.nextElement().getName();
            if (!paths.add(path)) throw new IOException("Duplicate Bedrock pack entry");
        }
        Catalog catalog = parse(readJson(zip, CATALOG));
        if (!catalog.models().isEmpty()) validateAssets(zip, catalog.models());
        return catalog;
    }

    static Set<Integer> verifiedHeldWeaponModels(ZipFile zip, Catalog catalog) throws IOException {
        if (catalog.heldWeaponModels().isEmpty() || !hasFile(zip, HELD_ATTACHABLE) || !hasFile(zip, HELD_CONTROLLERS)) {
            return Set.of();
        }
        var attachable = parseObject(readJson(zip, HELD_ATTACHABLE, MAX_HELD_ASSET_BYTES));
        var controllers = parseObject(readJson(zip, HELD_CONTROLLERS, MAX_HELD_ASSET_BYTES));
        if (!(attachable.get("minecraft:attachable") instanceof JsonObject definition)
            || !(definition.get("description") instanceof JsonObject description)
            || !new JsonPrimitive("minecraft:trident").equals(description.get("identifier"))
            || !(description.get("geometry") instanceof JsonObject geometry)
            || !(description.get("textures") instanceof JsonObject textures)
            || !(description.get("render_controllers") instanceof JsonArray attachedControllers)
            || !(controllers.get("render_controllers") instanceof JsonObject availableControllers)) {
            return Set.of();
        }
        Set<String> referenced = new HashSet<>();
        for (var controller : attachedControllers) {
            if (controller instanceof JsonObject conditional) referenced.addAll(conditional.keySet());
        }
        Set<Integer> verified = new HashSet<>();
        for (ItemModel model : catalog.models()) {
            String controller = "controller.render.astralrecord.held_" + model.id();
            String alias = "weapon_" + model.id();
            if (catalog.heldWeaponModels().contains(model.customModelData()) && referenced.contains(controller)
                && availableControllers.get(controller) instanceof JsonObject config
                && new JsonPrimitive("geometry." + alias).equals(config.get("geometry"))
                && geometry.get(alias) instanceof JsonPrimitive geometryId && geometryId.isString()
                && new JsonPrimitive("textures/entity/items/" + model.id()).equals(textures.get(alias))) {
                verified.add(model.customModelData());
            }
        }
        return Set.copyOf(verified);
    }

    private static void validateAssets(ZipFile zip, Set<ItemModel> models) throws IOException {
        var atlas = parseObject(readJson(zip, ATLAS));
        if (!(atlas.get("texture_data") instanceof JsonObject textureData)) {
            throw new IOException("Bedrock item texture atlas has no texture_data object");
        }
        for (ItemModel model : models) {
            String id = model.id();
            String icon = "textures/items/" + id;
            if (!(textureData.get("astralrecord." + id) instanceof JsonObject texture)
                || !new JsonPrimitive(icon).equals(texture.get("textures"))) {
                throw new IOException("Invalid Bedrock item texture atlas mapping for " + id);
            }
            requireFile(zip, icon + ".png");
            requireFile(zip, "textures/entity/items/" + id + ".png");
            requireFile(zip, "attachables/" + id + ".json");
            requireFile(zip, "models/entity/items/" + id + ".geo.json");
            requireFile(zip, "animations/" + id + ".animation.json");
        }
    }

    private static ZipEntry requireFile(ZipFile zip, String path) throws IOException {
        var entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory() || entry.getSize() <= 0) {
            throw new IOException("Missing or empty Bedrock pack entry: " + path);
        }
        return entry;
    }

    private static boolean hasFile(ZipFile zip, String path) {
        var entry = zip.getEntry(path);
        return entry != null && !entry.isDirectory() && entry.getSize() > 0;
    }

    private static String readJson(ZipFile zip, String path) throws IOException {
        return readJson(zip, path, MAX_JSON_BYTES);
    }

    private static String readJson(ZipFile zip, String path, int maxBytes) throws IOException {
        var entry = requireFile(zip, path);
        if (entry.getSize() > maxBytes) throw new IOException("Bedrock pack JSON is too large: " + path);
        try (var input = zip.getInputStream(entry)) {
            byte[] content = input.readNBytes(maxBytes + 1);
            if (content.length > maxBytes) throw new IOException("Bedrock pack JSON is too large: " + path);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
        }
    }

    static Catalog parse(String json) throws IOException {
        Set<ItemModel> models = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Set<Integer> modelData = new HashSet<>();
        Set<Integer> heldWeaponModels = new HashSet<>();
        try {
            var root = parseObject(json);
            if (integer(root, "format_version") != 1) throw new IOException("Unsupported Bedrock item model catalog format");
            for (var element : root.getAsJsonArray("items")) {
                var item = element.getAsJsonObject();
                var idValue = item.getAsJsonPrimitive("id");
                String id = idValue.getAsString();
                String base = item.getAsJsonPrimitive("base_item").getAsString();
                int data = integer(item, "custom_model_data");
                if (!idValue.isString() || !id.matches("[a-z0-9]+(?:_[a-z0-9]+)*")
                    || !base.matches("minecraft:[a-z0-9]+(?:_[a-z0-9]+)*")
                    || data <= 0 || data >= (1 << 24)
                    || !ids.add(id) || !modelData.add(data)) {
                    throw new IOException("Invalid or duplicate Bedrock item model");
                }
                models.add(new ItemModel(id, base, data));
                if (item.has("held_weapon")) {
                    var heldWeapon = item.getAsJsonPrimitive("held_weapon");
                    if (!heldWeapon.isBoolean()) throw new IOException("Invalid held_weapon flag for " + id);
                    if (heldWeapon.getAsBoolean()) heldWeaponModels.add(data);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Bedrock item model catalog", exception);
        }
        return new Catalog(Set.copyOf(models), Set.copyOf(heldWeaponModels));
    }

    private static JsonObject parseObject(String json) throws IOException {
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readValue(reader, 0);
            if (!(value instanceof JsonObject object) || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Expected a single JSON object");
            }
            return object;
        } catch (IllegalStateException | NumberFormatException exception) {
            throw new IOException("Invalid Bedrock pack JSON", exception);
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw new IOException("Bedrock pack JSON nesting is too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IOException("Duplicate Bedrock pack JSON field");
                    object.add(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Invalid Bedrock pack JSON value");
        };
    }

    private static int integer(JsonObject object, String key) throws IOException {
        var value = object.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw new IOException("Invalid Bedrock item model catalog number");
        return value.getAsBigDecimal().intValueExact();
    }

    void register(GeyserDefineCustomItemsEvent event) {
        var javaItem = Identifier.of(baseItem);
        var bedrockItem = Identifier.of("astralrecord", id);
        event.register(javaItem, CustomItemDefinition.builder(bedrockItem, javaItem)
            .predicate(context -> context.customModelDataFloat(0) == customModelData)
            .build());
    }
}
