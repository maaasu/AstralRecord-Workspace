package io.github.maaasu.astralRecord.shared.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/** PLAYER_HEAD へ master 指定の textures 値を適用する共通処理。 */
public final class HeadTextureItemStackSupport {
    private static final Pattern TEXTURE_URL = Pattern.compile(
            "^https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]{1,64}$"
    );
    private HeadTextureItemStackSupport() {
    }

    /**
     * PLAYER_HEAD の ItemStack へ Base64 textures 値を適用します。
     *
     * @param itemStack 適用対象
     * @param iconTexture Base64 textures 値。空白時は何もしません
     * @return textures を適用した場合は {@code true}
     */
    public static boolean apply(@NotNull ItemStack itemStack, @Nullable String iconTexture) {
        if (itemStack.getType() != Material.PLAYER_HEAD || !isValid(iconTexture)) {
            return false;
        }
        if (!(itemStack.getItemMeta() instanceof SkullMeta skullMeta)) {
            return false;
        }
        String texture = iconTexture.trim();
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)));
        profile.setProperty(new ProfileProperty("textures", texture));
        skullMeta.setPlayerProfile(profile);
        itemStack.setItemMeta(skullMeta);
        return true;
    }

    /**
     * API の head 契約と同じ iconTexture 妥当性を判定します。
     *
     * @param iconTexture Base64 の textures 値
     * @return Base64 JSON の {@code textures.SKIN.url} が公式 texture URL の場合は {@code true}
     */
    public static boolean isValid(@Nullable String iconTexture) {
        if (iconTexture == null || iconTexture.isBlank()) {
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(iconTexture.trim()), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(decoded);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonObject textures = root.getAsJsonObject().getAsJsonObject("textures");
            JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
            if (skin == null || !skin.has("url") || !skin.get("url").isJsonPrimitive()) {
                return false;
            }
            return TEXTURE_URL.matcher(skin.get("url").getAsString()).matches();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return false;
        }
    }
}
