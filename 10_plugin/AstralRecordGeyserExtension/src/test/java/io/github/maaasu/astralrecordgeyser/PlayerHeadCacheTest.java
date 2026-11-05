package io.github.maaasu.astralrecordgeyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerHeadCacheTest {
    private static final UUID SAVED = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID RETRY = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @TempDir Path data;

    @Test
    void reusesFirstTextureAcrossStartsAndRetriesFailuresOnly() {
        Path cache = data.resolve("player-head-skins.json");
        assertEquals(Map.of(SAVED, "123abc"), PlayerHeadCache.resolve(cache, List.of(SAVED, RETRY), uuid -> {
            if (uuid.equals(RETRY)) throw new IOException("Temporary failure");
            return "123abc";
        }, message -> {}));

        Set<UUID> queried = ConcurrentHashMap.newKeySet();
        assertEquals(Map.of(SAVED, "123abc", RETRY, "456def"),
            PlayerHeadCache.resolve(cache, List.of(SAVED, RETRY), uuid -> {
                queried.add(uuid);
                return "456def";
            }, message -> {}));
        assertEquals(Set.of(RETRY), queried);

        assertEquals(Map.of(RETRY, "456def"), PlayerHeadCache.resolve(cache, List.of(RETRY), uuid -> {
            throw new AssertionError("Saved UUID should not be requested");
        }, message -> {}));

        assertEquals(Map.of(SAVED, "123abc"), PlayerHeadCache.resolve(cache, List.of(SAVED), uuid -> {
            throw new AssertionError("Temporarily absent UUID should still be saved");
        }, message -> {}));
    }

    @Test
    void preservesUnreadableCacheAndSkipsLookupsUntilItIsRecovered() throws IOException {
        Path cache = data.resolve("player-head-skins.json");
        Files.writeString(cache, "{");
        Set<String> warnings = ConcurrentHashMap.newKeySet();
        assertEquals(Map.of(), PlayerHeadCache.resolve(cache, List.of(SAVED), uuid -> {
            throw new AssertionError("An unreadable cache must not trigger new lookups");
        }, warnings::add));
        assertEquals("{", Files.readString(cache));
        assertEquals(1, warnings.size());
    }

    @Test
    void onlyAcceptsTheRequestedProfilesMinecraftTexture() {
        assertEquals("123abc", MojangSkinClient.parse(SAVED,
            profile(SAVED, "http://textures.minecraft.net/texture/123abc")));
        assertThrows(IllegalArgumentException.class, () -> MojangSkinClient.parse(RETRY,
            profile(SAVED, "http://textures.minecraft.net/texture/123abc")));
        assertThrows(IllegalArgumentException.class, () -> MojangSkinClient.parse(SAVED,
            profile(SAVED, "https://textures.minecraft.net.example.org/texture/123abc")));
    }

    private static String profile(UUID uuid, String textureUrl) {
        String id = uuid.toString().replace("-", "");
        String textures = "{\"profileId\":\"%s\",\"textures\":{\"SKIN\":{\"url\":\"%s\"}}}".formatted(id, textureUrl);
        String value = Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"%s\",\"properties\":[{\"name\":\"textures\",\"value\":\"%s\"}]}".formatted(id, value);
    }
}
