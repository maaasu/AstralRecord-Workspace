package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WorldServiceSpawnInWorldTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## スポーン地点解決・転送
     * 検証契約: 指定したBukkitワールドへWorldMasterDataのスポーン座標を適用し、転送前のプレイヤーyaw/pitchを維持する。
     */
    @Test
    void teleportToSpawnInWorldUsesExplicitRuntimeWorld() {
        WorldService service = new WorldService(mock(WorldRepository.class), () -> new File("target/test-world-container"));
        PlayerMock player = server().addPlayer();
        World runtimeWorld = server().addSimpleWorld("runtime-instance");
        player.teleport(new Location(player.getWorld(), 1.0D, 64.0D, 1.0D, 135.0F, 23.5F));
        WorldMasterData data = new WorldMasterData(
                1,
                "boss-field",
                "Boss Field",
                WorldType.BOSS_FIELD,
                "boss-field",
                "instances/boss-field",
                false,
                true,
                6,
                false,
                false,
                false,
                false,
                new WorldSpawnLocation(12.5D, 70.0D, -8.25D, 0.0F, 0.0F),
                "",
                null,
                null,
                null
        );

        assertTrue(service.teleportToSpawnInWorld(player, data, runtimeWorld));
        assertEquals(runtimeWorld, player.getWorld());
        assertEquals(12.5D, player.getLocation().getX(), 0.0001D);
        assertEquals(70.0D, player.getLocation().getY(), 0.0001D);
        assertEquals(-8.25D, player.getLocation().getZ(), 0.0001D);
        assertEquals(135.0F, player.getLocation().getYaw(), 0.0001F);
        assertEquals(23.5F, player.getLocation().getPitch(), 0.0001F);
    }
}
