package io.github.maaasu.astralRecord.feature.item.castdisk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** スキルキャストディスク個体へ保存する設定値を扱います。 */
public record CastDiskSettings(int actionSlotIndex, int weaponHotbarSlot) {
    private static final String ROOT_KEY = "castDisk";
    private static final String ACTION_SLOT_KEY = "actionSlot";
    private static final String WEAPON_SLOT_KEY = "weaponHotbarSlot";

    public boolean isComplete() {
        return actionSlotIndex >= 0 && actionSlotIndex < 6 && weaponHotbarSlot >= 0 && weaponHotbarSlot < 9;
    }

    public static @NotNull CastDiskSettings read(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return new CastDiskSettings(-1, -1);
        try {
            JsonElement rootElement = JsonParser.parseString(metadataJson);
            if (!rootElement.isJsonObject()) return new CastDiskSettings(-1, -1);
            JsonObject root = rootElement.getAsJsonObject();
            JsonElement diskElement = root.get(ROOT_KEY);
            if (diskElement == null || !diskElement.isJsonObject()) return new CastDiskSettings(-1, -1);
            JsonObject disk = diskElement.getAsJsonObject();
            return new CastDiskSettings(readInt(disk, ACTION_SLOT_KEY), readInt(disk, WEAPON_SLOT_KEY));
        } catch (RuntimeException ignored) {
            return new CastDiskSettings(-1, -1);
        }
    }

    public static @Nullable String write(
        @Nullable String metadataJson,
        @NotNull CastDiskSettings settings
    ) {
        JsonObject root;
        try {
            JsonElement existing = metadataJson == null || metadataJson.isBlank()
                ? null : JsonParser.parseString(metadataJson);
            root = existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            root = new JsonObject();
        }
        JsonObject disk = new JsonObject();
        if (settings.actionSlotIndex >= 0 && settings.actionSlotIndex < 6) {
            disk.addProperty(ACTION_SLOT_KEY, settings.actionSlotIndex);
        }
        if (settings.weaponHotbarSlot >= 0 && settings.weaponHotbarSlot < 9) {
            disk.addProperty(WEAPON_SLOT_KEY, settings.weaponHotbarSlot);
        }
        if (disk.isEmpty()) {
            root.remove(ROOT_KEY);
        } else {
            root.add(ROOT_KEY, disk);
        }
        return root.isEmpty() ? null : root.toString();
    }

    private static int readInt(@NotNull JsonObject object, @NotNull String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
            ? value.getAsInt() : -1;
    }
}
