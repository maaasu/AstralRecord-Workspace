package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerVanillaDamageBlockEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラプレイヤーダメージ抑止
     * 検証契約: プレイヤー間の Bukkit ダメージは、攻撃者・被弾者の両方が PvP 有効の場合だけ下流へ通す。
     */
    @Test
    void allowsPlayerDamageOnlyWhenBothPvpFlagsAreEnabled() {
        WorldService worldService = mock(WorldService.class);
        Player attacker = mock(Player.class);
        Player victim = mock(Player.class);
        AstPlayer attackerModel = mock(AstPlayer.class);
        AstPlayer victimModel = mock(AstPlayer.class);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        when(attackerModel.isPvpEnabled()).thenReturn(true);
        when(victimModel.isPvpEnabled()).thenReturn(true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(attacker)).thenReturn(attackerModel);
            cache.when(() -> AstPlayerCache.get(victim)).thenReturn(victimModel);

            new PlayerVanillaDamageBlockEventHandler(worldService).onEntityDamage(event);

            verify(event, never()).setDamage(anyDouble());
            verify(event, never()).setCancelled(true);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラプレイヤーダメージ抑止
     * 検証契約: PvP フラグが片方でも無効なプレイヤー間ダメージは既定のバニラダメージ抑止へ戻る。
     */
    @Test
    void cancelsPlayerDamageWhenEitherPvpFlagIsDisabled() {
        WorldService worldService = mock(WorldService.class);
        Player attacker = mock(Player.class);
        Player victim = mock(Player.class);
        AstPlayer attackerModel = mock(AstPlayer.class);
        AstPlayer victimModel = mock(AstPlayer.class);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        when(attackerModel.isPvpEnabled()).thenReturn(true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(attacker)).thenReturn(attackerModel);
            cache.when(() -> AstPlayerCache.get(victim)).thenReturn(victimModel);

            new PlayerVanillaDamageBlockEventHandler(worldService).onEntityDamage(event);

            verify(event).setDamage(0.0D);
            verify(event).setCancelled(true);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラプレイヤーダメージ抑止
     * 検証契約: プレイヤーへの Bukkit EntityDamageEvent はキャッシュ状態や原因にかかわらず damage を0にしてキャンセルする。
     */
    @Test
    void cancelsCampfireDamageWithoutFeedbackForPlayerOutsidePlayerCache() {
        assertVanillaDamageWithoutFeedback(EntityDamageEvent.DamageCause.CAMPFIRE);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラプレイヤーダメージ抑止
     * 検証契約: プレイヤーへの Bukkit EntityDamageEvent は窒息ダメージでも damage を0にしてキャンセルする。
     */
    @Test
    void cancelsSuffocationDamageWithoutFeedbackForPlayerOutsidePlayerCache() {
        assertVanillaDamageWithoutFeedback(EntityDamageEvent.DamageCause.SUFFOCATION);
    }

    private void assertVanillaDamageWithoutFeedback(EntityDamageEvent.DamageCause cause) {
        WorldService worldService = mock(WorldService.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(cause);
        when(event.getDamage()).thenReturn(2.0D);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(mock(Location.class));

        new PlayerVanillaDamageBlockEventHandler(worldService).onEntityDamage(event);

        verify(event).setDamage(0.0D);
        verify(event).setCancelled(true);
        verify(player, never()).playHurtAnimation(0.0F);
        verify(world, never()).playSound(
                any(Location.class),
                eq(Sound.ENTITY_PLAYER_HURT),
                eq(SoundCategory.PLAYERS),
                eq(0.75F),
                eq(1.0F)
        );
        verify(worldService, never()).findByBukkitWorld(org.mockito.ArgumentMatchers.any(World.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラプレイヤーダメージ抑止
     * 検証契約: VOID の EntityDamageEvent はキャンセルしたうえで、現在 Bukkit ワールドに対応する WorldMasterData の spawnLocation 転送を呼び出す。
     */
    @Test
    void returnsVoidDamagedPlayerToCurrentWorldMasterSpawn() {
        WorldService worldService = mock(WorldService.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        WorldMasterData worldData = worldData();
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.VOID);
        when(player.getWorld()).thenReturn(world);
        when(worldService.findByBukkitWorld(world)).thenReturn(worldData);
        when(worldService.teleportToSpawnInWorld(player, worldData, world)).thenReturn(true);

        new PlayerVanillaDamageBlockEventHandler(worldService).onEntityDamage(event);

        verify(event).setDamage(0.0D);
        verify(event).setCancelled(true);
        verify(worldService).findByBukkitWorld(world);
        verify(worldService).teleportToSpawnInWorld(player, worldData, world);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### バニラプレイヤーダメージ抑止
     * 検証契約: VOID の現在ワールドを WorldMasterData へ解決できない場合もダメージはキャンセルし、Bukkit の既定スポーン転送は実行しない。
     */
    @Test
    void doesNotFallbackToBukkitSpawnWhenVoidWorldIsUnmanaged() {
        WorldService worldService = mock(WorldService.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.VOID);
        when(player.getWorld()).thenReturn(world);
        when(worldService.findByBukkitWorld(world)).thenReturn(null);

        new PlayerVanillaDamageBlockEventHandler(worldService).onEntityDamage(event);

        verify(event).setDamage(0.0D);
        verify(event).setCancelled(true);
        verify(worldService).findByBukkitWorld(world);
        verify(worldService, never()).teleportToSpawnInWorld(player, null, world);
    }

    private WorldMasterData worldData() {
        return new WorldMasterData(
                1,
                "current-world",
                "Current World",
                WorldType.OVERWORLD,
                "base/current-world",
                "instances/current-world",
                false,
                false,
                100,
                false,
                false,
                true,
                true,
                new WorldSpawnLocation(10.5D, 64.0D, 20.5D, 0.0F, 0.0F),
                "",
                null,
                null,
                null
        );
    }
}
