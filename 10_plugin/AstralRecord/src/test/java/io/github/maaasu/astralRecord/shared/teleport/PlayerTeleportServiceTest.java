package io.github.maaasu.astralRecord.shared.teleport;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PlayerTeleportServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## スポーン地点解決・転送
     * 検証契約: targetをcloneし座標を維持したまま転送直前のplayer yaw/pitchへ置換し、入力Locationを変更しない。
     */
    @Test
    void withCurrentLookDirectionCopiesPlayerYawAndPitchWithoutMutatingTarget() {
        PlayerMock player = server().addPlayer();
        World world = player.getWorld();
        player.teleport(new Location(world, 1.0D, 64.0D, 1.0D, 135.0F, 23.5F));
        Location target = new Location(world, 10.0D, 70.0D, -4.0D, 0.0F, 0.0F);

        Location oriented = PlayerTeleportService.withCurrentLookDirection(player, target);

        assertNotSame(target, oriented);
        assertEquals(10.0D, oriented.getX());
        assertEquals(70.0D, oriented.getY());
        assertEquals(-4.0D, oriented.getZ());
        assertEquals(135.0F, oriented.getYaw());
        assertEquals(23.5F, oriented.getPitch());
        assertEquals(0.0F, target.getYaw());
        assertEquals(0.0F, target.getPitch());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## スポーン地点解決・転送
     * 検証契約: 転送先world・座標へ移動しつつplayerの転送直前yaw/pitchを維持する。
     */
    @Test
    void teleportKeepsPlayerYawAndPitchAtDestination() {
        PlayerMock player = server().addPlayer();
        World world = player.getWorld();
        player.teleport(new Location(world, 1.0D, 64.0D, 1.0D, -90.0F, 12.0F));
        Location target = new Location(world, 20.0D, 75.0D, 30.0D, 0.0F, 0.0F);

        PlayerTeleportService.teleport(player, target);

        assertEquals(20.0D, player.getLocation().getX());
        assertEquals(75.0D, player.getLocation().getY());
        assertEquals(30.0D, player.getLocation().getZ());
        assertEquals(-90.0F, player.getLocation().getYaw());
        assertEquals(12.0F, player.getLocation().getPitch());
    }
}
