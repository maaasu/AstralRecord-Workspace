package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorldSpawnLocationSnapshotTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## スポーン地点解決・転送
     * 検証契約: 非同期用スポーン地点は独立したコピーとして取得でき、Bukkitのワールド状態に触れない。
     */
    @Test
    void readingSnapshotDoesNotAccessWorldAndCannotMutatePublishedLocation() {
        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            Fixture fixture = new Fixture();
            fixture.publishAndResolve(data("join_base", 2.5));
            clearInvocations(fixture.world);

            Location first = fixture.service.getLoadedSpawnLocationSnapshot("join_base");
            first.setX(999);
            first.setYaw(180);
            Location second = fixture.service.getLoadedSpawnLocationSnapshot("join_base");

            assertSame(fixture.world, second.getWorld());
            assertEquals(2.5, second.getX());
            assertEquals(0, second.getYaw());
            verifyNoInteractions(fixture.world);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## スポーン地点解決・転送
     * 検証契約: 同じ実ワールドの再公開ではactivation前も新座標を利用でき、パス変更では失効する。
     */
    @Test
    void reloadKeepsSameWorldAvailableWithNewCoordinatesButInvalidatesChangedPath() {
        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            Fixture fixture = new Fixture();
            fixture.publishAndResolve(data("join_base", 2.5));
            fixture.publish(data("join_base", 12.5));
            assertEquals(12.5, fixture.service.getLoadedSpawnLocationSnapshot("join_base").getX());

            fixture.publish(data("different_folder", 12.5));
            assertNull(fixture.service.getLoadedSpawnLocationSnapshot("join_base"));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## スポーン地点解決・転送
     * 検証契約: アンロード・定義削除で位置を失効させ、ワールドの再解決で最新位置を再公開する。
     */
    @Test
    void unloadAndDefinitionRemovalInvalidateSpawnSnapshot() {
        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            Fixture fixture = new Fixture();
            fixture.publishAndResolve(data("join_base", 2.5));
            fixture.service.invalidateSpawnLocationSnapshots(fixture.world);
            assertNull(fixture.service.getLoadedSpawnLocationSnapshot("join_base"));

            fixture.publishAndResolve(data("join_base", 2.5));
            assertSame(fixture.world, fixture.service.getLoadedSpawnLocationSnapshot("join_base").getWorld());
            fixture.service.replaceDefinitionSnapshot(new WorldService.DefinitionSnapshot(List.of(), Map.of()));
            assertNull(fixture.service.getLoadedSpawnLocationSnapshot("join_base"));
        }
    }

    private static WorldMasterData data(String path, double x) {
        return new WorldMasterData(1, "join_base", "Base", WorldType.BASE, path, "", false, false,
                0, false, false, false, false, new WorldSpawnLocation(x, 72, -4.5, 0, 0), "",
                null, null, null, null);
    }

    private static final class Fixture {
        private final World world = mock(World.class);
        private final WorldService service;

        private Fixture() {
            File container = new File("snapshot-container").getAbsoluteFile();
            when(world.getUID()).thenReturn(new UUID(1, 2));
            when(world.getWorldFolder()).thenReturn(new File(container, "join_base"));
            service = new WorldService(mock(WorldRepository.class), () -> container, () -> List.of(world), () -> null);
        }

        private void publish(WorldMasterData data) {
            service.replaceDefinitionSnapshot(new WorldService.DefinitionSnapshot(List.of(data), Map.of(data.id(), data)));
        }

        private void publishAndResolve(WorldMasterData data) {
            publish(data);
            service.resolveLoadedWorld(data);
        }
    }
}
