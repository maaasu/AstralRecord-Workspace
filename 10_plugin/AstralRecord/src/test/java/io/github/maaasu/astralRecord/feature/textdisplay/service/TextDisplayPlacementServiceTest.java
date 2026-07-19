package io.github.maaasu.astralRecord.feature.textdisplay.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.textdisplay.command.TextDisplayCommand;
import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import io.github.maaasu.astralRecord.feature.textdisplay.repository.TextDisplayPlacementRepository;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextDisplayPlacementServiceTest extends MockBukkitTestBase {

    @AfterEach
    void clearAstPlayerCache() {
        AstPlayerCache.clear();
    }

    @Test
    void placeKeepsRawTextButCreatesDisplayWithLegacyColorCodes() {
        Plugin plugin = mock(Plugin.class);
        TextDisplayPlacementRepository repository = mock(TextDisplayPlacementRepository.class);
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        TextDisplayPlacementService service = new TextDisplayPlacementService(plugin, repository);
        service.setDisplayTextService(displayTextService);
        World world = server().addSimpleWorld("text_world");
        Location location = new Location(world, 12.5D, 70.0D, -4.25D, 90.0F, 0.0F);

        TextDisplayPlacement placement = service.place("notice", "&aHello &fWorld\\n&cRed", location);

        assertEquals("&aHello &fWorld\\n&cRed", placement.text());
        ArgumentCaptor<DisplayTextOptions> options = ArgumentCaptor.forClass(DisplayTextOptions.class);
        verify(displayTextService).create(any(DisplayAnchor.class), options.capture());
        assertEquals("\u00a7aHello \u00a7fWorld\\n\u00a7cRed", options.getValue().text());
    }

    @Test
    void commandPlacePassesJoinedTextToPlacementService() {
        TextDisplayPlacementService placementService = mock(TextDisplayPlacementService.class);
        TextDisplayCommand command = new TextDisplayCommand(placementService);
        World world = server().addSimpleWorld("command_world");
        Player player = server().addPlayer("admin");
        Location location = new Location(world, 5.0D, 65.0D, 9.0D);
        player.teleport(location);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.hasPermissionLevel(anyInt())).thenReturn(true);
        AstPlayerCache.put(astPlayer);
        TextDisplayPlacement placement = TextDisplayPlacement.from("notice", "&aHello World", location);
        when(placementService.place("notice", "&aHello World", location)).thenReturn(placement);

        command.onCommand(player, null, "textdisplay", new String[] {"place", "notice", "&aHello", "World"});

        verify(placementService).place("notice", "&aHello World", location);
    }

    @Test
    void failedSaveKeepsDirtyStateForRetry() {
        Plugin plugin = mock(Plugin.class);
        TextDisplayPlacementRepository repository = mock(TextDisplayPlacementRepository.class);
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        when(repository.saveAll(any())).thenReturn(false, true);
        TextDisplayPlacementService service = new TextDisplayPlacementService(plugin, repository);
        service.setDisplayTextService(displayTextService);
        World world = server().addSimpleWorld("retry_world");

        service.place("retry", "保存再試行", new Location(world, 0.0D, 64.0D, 0.0D));
        service.saveIfDirty();

        verify(repository, times(2)).saveAll(any());
    }
}
