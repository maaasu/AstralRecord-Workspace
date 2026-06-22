package io.github.maaasu.astralRecord.feature.textdisplay.repository;

import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextDisplayPlacementRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAllAndLoadAllPreservesTextAndLocation() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        TextDisplayPlacementRepository repository = new TextDisplayPlacementRepository(plugin);

        TextDisplayPlacement placement = new TextDisplayPlacement(
                "welcome",
                "&aWelcome\\n&bAstralRecord",
                "world",
                1.25D,
                64.5D,
                -2.75D,
                90.0F,
                15.0F
        );

        repository.saveAll(List.of(placement));

        assertEquals(List.of(placement), repository.loadAll());
    }
}
