package io.github.maaasu.astralRecord.feature.inventory.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * Bukkit インベントリのスナップショットを metadataJson として変換します。
 */
final class InventorySnapshotCodec {
    private static final String SNAPSHOT_FORMAT = "bukkit_inventory_nbt_v1";
    private static final String LEGACY_SNAPSHOT_FORMAT = "bukkit_inventory_v1";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_CONTENTS = "contents";
    private static final String KEY_GAME_MODE = "gameMode";

    /**
     * BUILDER モード用スナップショット復元結果。
     *
     * @param contents 復元した ItemStack 配列
     * @param gameMode 保存されていた GameMode。未保存または不明値の場合は null
     */
    record BuilderSnapshot(@NotNull ItemStack[] contents, @Nullable GameMode gameMode) {
    }

    /**
     * ItemStack 配列を API 保存用 metadataJson に変換します。
     *
     * @param contents Bukkit インベントリ内容
     * @return metadataJson
     */
    @NotNull String encode(@NotNull ItemStack[] contents) {
        JsonObject json = new JsonObject();
        json.addProperty(KEY_FORMAT, SNAPSHOT_FORMAT);
        json.addProperty(KEY_CONTENTS, encodeContents(contents));
        return json.toString();
    }

    /**
     * BUILDER モード用に ItemStack 配列と GameMode を metadataJson に変換します。
     *
     * @param contents Bukkit インベントリ内容
     * @param gameMode 保存対象 GameMode。null の場合は GameMode を含めずに保存します
     * @return metadataJson
     */
    @NotNull String encodeBuilder(@NotNull ItemStack[] contents, @Nullable GameMode gameMode) {
        JsonObject json = new JsonObject();
        json.addProperty(KEY_FORMAT, SNAPSHOT_FORMAT);
        json.addProperty(KEY_CONTENTS, encodeContents(contents));
        if (gameMode != null) {
            json.addProperty(KEY_GAME_MODE, gameMode.name());
        }
        return json.toString();
    }

    /**
     * BUILDER モード用 metadataJson から ItemStack 配列と GameMode を復元します。
     *
     * @param metadataJson API に保存された metadataJson
     * @return 復元できた [BuilderSnapshot]。形式不一致や破損時は null
     */
    @Nullable BuilderSnapshot decodeBuilder(@NotNull String metadataJson) {
        ItemStack[] contents = decode(metadataJson);
        if (contents == null) {
            return null;
        }
        GameMode gameMode = null;
        try {
            JsonObject obj = JsonParser.parseString(metadataJson).getAsJsonObject();
            if (obj.has(KEY_GAME_MODE)) {
                String name = obj.get(KEY_GAME_MODE).getAsString();
                try {
                    gameMode = GameMode.valueOf(name);
                } catch (IllegalArgumentException ignored) {
                    gameMode = null;
                }
            }
        } catch (Exception ignored) {
            gameMode = null;
        }
        return new BuilderSnapshot(contents, gameMode);
    }

    /**
     * metadataJson から Bukkit インベントリ内容を復元します。
     *
     * @param metadataJson API に保存された metadataJson
     * @return 復元できた ItemStack 配列。形式不一致や破損時は null
     */
    @Nullable ItemStack[] decode(@NotNull String metadataJson) {
        try {
            JsonObject obj = JsonParser.parseString(metadataJson).getAsJsonObject();
            String format = obj.has(KEY_FORMAT) ? obj.get(KEY_FORMAT).getAsString() : null;
            String encoded = obj.has(KEY_CONTENTS) ? obj.get(KEY_CONTENTS).getAsString() : null;
            if (encoded == null || encoded.isBlank()) {
                return null;
            }

            if (SNAPSHOT_FORMAT.equals(format)) {
                return decodeContents(encoded);
            }

            if (LEGACY_SNAPSHOT_FORMAT.equals(format)) {
                return decodeLegacyContents(encoded);
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ItemStack 配列を Base64 文字列へシリアライズします。
     *
     * @param contents Bukkit インベントリ内容
     * @return Base64 化した内容。失敗時は空文字列
     */
    private @NotNull String encodeContents(@NotNull ItemStack[] contents) {
        try {
            return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(contents));
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5250, e.getMessage());
            return "";
        }
    }

    /**
     * Base64 文字列から ItemStack 配列を復元します。
     *
     * @param encoded Base64 化された Bukkit インベントリ内容
     * @return 復元した ItemStack 配列。失敗時は null
     */
    private @Nullable ItemStack[] decodeContents(@NotNull String encoded) {
        try {
            return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5251, e.getMessage());
            return null;
        }
    }

    /**
     * 旧形式の Base64 文字列から ItemStack 配列を復元します。
     * <p>
     * 新規保存では使用せず、既存 metadataJson の移行互換のためだけに残しています。
     *
     * @param encoded Base64 化された旧形式の Bukkit インベントリ内容
     * @return 復元した ItemStack 配列。失敗時は null
     */
    @SuppressWarnings({"deprecation"})
    private @Nullable ItemStack[] decodeLegacyContents(@NotNull String encoded) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
             BukkitObjectInputStream dataIn = new BukkitObjectInputStream(in)) {
            int length = dataIn.readInt();
            ItemStack[] contents = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                contents[i] = (ItemStack) dataIn.readObject();
            }
            return contents;
        } catch (Exception e) {
            Logger.warn(LogId.W_5251, e.getMessage());
            return null;
        }
    }

}
