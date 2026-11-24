package io.github.maaasu.astralrecordgeyser;

import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.ResourcePackManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TooltipPackCompatibilityTest {
    private static final UUID PACK_ID = UUID.fromString("da66fd58-ca36-42b3-a475-8573cc7e86f3");

    @Test
    void rejectsAnOlderPackWithTheSameUuid() {
        ResourcePack selected = new TestPack();
        ResourcePack older = new TestPack();

        assertEquals(selected.uuid(), older.uuid());
        assertFalse(isReady(true, selected, List.of(older), true));
    }

    @Test
    void rejectsAnOptionalPackEvenWhenItWasRegistered() {
        ResourcePack selected = new TestPack();

        assertFalse(isReady(false, selected, List.of(selected), true));
        assertFalse(isReady(false, selected, List.of(), true));
    }

    @Test
    void enablesOnlyGlyphsInTheExactRequiredPack() {
        ResourcePack selected = new TestPack();

        assertTrue(isReady(true, selected, List.of(selected), true));
        assertFalse(isReady(true, selected, List.of(selected), false));
    }

    private static boolean isReady(
        boolean forced, ResourcePack selected, List<ResourcePack> registered, boolean includesGlyphs
    ) {
        return DamagePackDistribution.verifiedTooltipPack(
            forced, DamagePackDistribution.isExactPackRegistered(selected, registered), includesGlyphs
        );
    }

    private static final class TestPack implements ResourcePack {
        @Override
        public UUID uuid() {
            return PACK_ID;
        }

        @Override
        public PackCodec codec() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResourcePackManifest manifest() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String contentKey() {
            return "";
        }
    }
}
