package io.github.maaasu.astralrecordgeyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class HeadCatalogTest {
    @Test
    void deduplicatesCompleteCatalog() {
        JsonObject root = new JsonObject();
        JsonArray textures = new JsonArray();
        textures.add(BuiltinHeadTextures.OAK_WOOD_ARROW_UP_TEXTURE);
        textures.add(BuiltinHeadTextures.OAK_WOOD_ARROW_UP_TEXTURE);
        root.add("textures", textures);
        JsonArray uuids = new JsonArray();
        uuids.add("01234567-89ab-cdef-0123-456789abcdef");
        uuids.add("01234567-89ab-cdef-0123-456789abcdef");
        root.add("playerUuids", uuids);
        HeadCatalog catalog = HeadCatalog.parse(root.toString());
        assertEquals(1, catalog.textures().size());
        assertEquals(1, catalog.playerUuids().size());
    }

    @Test
    void validatesAllBuiltinProfiles() {
        for (String texture : BuiltinHeadTextures.ALL) assertDoesNotThrow(() -> HeadCatalog.validateTexture(texture));
    }

    @Test
    void rejectsMissingArraysMalformedProfilesAndShortUuids() {
        assertThrows(RuntimeException.class, () -> HeadCatalog.parse("{}"));
        assertThrows(RuntimeException.class, () -> HeadCatalog.parse("{\"textures\":[\"invalid\"],\"playerUuids\":[]}"));
        assertThrows(RuntimeException.class, () -> HeadCatalog.parse("{\"textures\":[],\"playerUuids\":[\"1-1-1-1-1\"]}"));
    }

    @Test
    void rejectsUntrustedTextureHosts() {
        for (String url : new String[]{"http://localhost/texture/abc", "https://textures.minecraft.net@localhost/texture/abc",
            "https://textures.minecraft.net/texture/abc?redirect=1"}) {
            String profile = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}")
                .getBytes(StandardCharsets.UTF_8));
            assertThrows(RuntimeException.class, () -> HeadCatalog.validateTexture(profile));
        }
    }
}
