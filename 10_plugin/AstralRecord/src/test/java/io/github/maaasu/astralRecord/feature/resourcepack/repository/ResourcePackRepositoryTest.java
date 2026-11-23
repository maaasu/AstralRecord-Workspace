package io.github.maaasu.astralRecord.feature.resourcepack.repository;

import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourcePackRepositoryTest {
    @Test
    void recognizesQuestOnlyAndChapterCapableManifestMarkers() {
        assertEquals(2, ResourcePackRepository.questHudVersion(new JsonPrimitive("2")));
        assertEquals(3, ResourcePackRepository.questHudVersion(new JsonPrimitive("3")));
        assertEquals(0, ResourcePackRepository.questHudVersion(new JsonPrimitive("4")));
        assertEquals(0, ResourcePackRepository.questHudVersion(new JsonPrimitive(3)));
        assertEquals(0, ResourcePackRepository.questHudVersion(JsonNull.INSTANCE));
        assertEquals(0, ResourcePackRepository.questHudVersion(null));
    }
}
