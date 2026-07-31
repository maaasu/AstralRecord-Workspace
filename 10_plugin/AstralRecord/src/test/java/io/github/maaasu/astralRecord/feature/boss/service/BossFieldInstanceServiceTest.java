package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BossFieldInstanceServiceTest {

    @TempDir
    Path tempDirectory;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 17. フィールド破棄
     * 検証契約: 入れ子のregion/dataを含むinstance world directoryを再帰的に完全削除する。
     */
    @Test
    void deleteDirectoryRemovesNestedWorldFilesWithoutCollectingAllPaths() throws Exception {
        Path worldDirectory = tempDirectory.resolve("boss_instance");
        Files.createDirectories(worldDirectory.resolve("region/nested"));
        Files.writeString(worldDirectory.resolve("level.dat"), "level");
        Files.writeString(worldDirectory.resolve("region/r.0.0.mca"), "region");
        Files.writeString(worldDirectory.resolve("region/nested/data.bin"), "data");

        BossFieldInstanceService.deleteDirectory(worldDirectory);

        assertFalse(Files.exists(worldDirectory));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 14. フィールド作成
     * 検証契約: 同じchunkの複数spawn位置を一件へ重複排除し、非緊急async load後のticketを明示releaseまで保持する。
     */
    @Test
    void prepareRequiredChunksDeduplicatesLocationsAndHoldsTicketsUntilRelease() {
        AstralRecord plugin = mock(AstralRecord.class);
        BossFieldInstanceService service = new BossFieldInstanceService(plugin, mock(WorldService.class));
        World world = mock(World.class);
        Chunk playerChunk = mock(Chunk.class);
        Chunk bossChunk = mock(Chunk.class);
        UUID worldId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.isChunkLoaded(-1, 1)).thenReturn(false);
        when(world.isChunkLoaded(-1, 0)).thenReturn(false);
        when(world.getChunkAtAsync(-1, 1, true, false)).thenReturn(CompletableFuture.completedFuture(playerChunk));
        when(world.getChunkAtAsync(-1, 0, true, false)).thenReturn(CompletableFuture.completedFuture(bossChunk));
        when(playerChunk.addPluginChunkTicket(plugin)).thenReturn(true);
        when(bossChunk.addPluginChunkTicket(plugin)).thenReturn(true);

        CompletableFuture<Void> result = service.prepareRequiredChunksAsync(
                challengeId,
                world,
                List.of(
                        new Location(world, -10.5D, 118.0D, 17.5D),
                        new Location(world, -10.1D, 118.0D, 17.9D),
                        new Location(world, -10.5D, 118.0D, 1.5D)
                )
        );

        assertTrue(result.isDone());
        result.join();
        verify(world, times(1)).getChunkAtAsync(-1, 1, true, false);
        verify(world, times(1)).getChunkAtAsync(-1, 0, true, false);
        verify(playerChunk).addPluginChunkTicket(plugin);
        verify(bossChunk).addPluginChunkTicket(plugin);

        service.releaseStartupChunkTickets(challengeId);

        verify(playerChunk).removePluginChunkTicket(plugin);
        verify(bossChunk).removePluginChunkTicket(plugin);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 14. フィールド作成
     * 検証契約: 全chunk Future完了まで待ち、一件失敗時は保持済みticketを解放してFutureを例外完了する。
     */
    @Test
    void prepareRequiredChunksWaitsForEveryChunkAndPropagatesFailure() {
        AstralRecord plugin = mock(AstralRecord.class);
        BossFieldInstanceService service = new BossFieldInstanceService(plugin, mock(WorldService.class));
        World world = mock(World.class);
        Chunk firstChunk = mock(Chunk.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        when(world.isChunkLoaded(1, 0)).thenReturn(false);
        CompletableFuture<Chunk> first = new CompletableFuture<>();
        CompletableFuture<Chunk> second = new CompletableFuture<>();
        when(world.getChunkAtAsync(0, 0, true, false)).thenReturn(first);
        when(world.getChunkAtAsync(1, 0, true, false)).thenReturn(second);
        when(firstChunk.addPluginChunkTicket(plugin)).thenReturn(true);

        CompletableFuture<Void> result = service.prepareRequiredChunksAsync(
                UUID.randomUUID(),
                world,
                List.of(new Location(world, 1.0D, 64.0D, 1.0D), new Location(world, 17.0D, 64.0D, 1.0D))
        );

        assertFalse(result.isDone());
        first.complete(firstChunk);
        assertFalse(result.isDone());
        verify(firstChunk).addPluginChunkTicket(plugin);
        second.completeExceptionally(new IllegalStateException("chunk load failed"));

        assertThrows(CompletionException.class, result::join);
        verify(firstChunk).removePluginChunkTicket(plugin);
    }
}
