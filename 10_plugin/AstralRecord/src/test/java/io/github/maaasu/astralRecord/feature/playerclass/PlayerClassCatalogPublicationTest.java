package io.github.maaasu.astralRecord.feature.playerclass;

import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class PlayerClassCatalogPublicationTest {
    /**
     * 未公開とクリア後は空ハッシュを返し、正常公開した空カタログは有効なSHA-256を返す。
     */
    @Test
    void distinguishesUnpublishedCatalogFromPublishedEmptyCatalog() {
        var service = new PlayerClassService();
        assertEquals("", service.getClassCatalogHash());

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            service.replaceSnapshot(Map.of());
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            service.getClassCatalogHash());

        service.clearCache();
        assertEquals("", service.getClassCatalogHash());
    }
}
