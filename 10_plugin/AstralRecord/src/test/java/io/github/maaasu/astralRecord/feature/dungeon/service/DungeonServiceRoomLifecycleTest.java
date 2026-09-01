package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.adventurerecord.repository.AdventureRecordRepository;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureDungeonRecord;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRewardEntry;
import io.github.maaasu.astralRecord.feature.dungeon.repository.DungeonDefinitionRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DungeonServiceRoomLifecycleTest extends MockBukkitTestBase {
    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 4. 開始・生成・転送
     * 検証契約: 待機中の現在パーティーメンバーが一人もHubにいなくなった場合は挑戦を終了する。
     */
    @Test
    void endsWaitingPartySessionWhenNoCurrentMemberRemainsInHub() throws Exception {
        WorldService worldService = mock(WorldService.class);
        PartyService partyService = mock(PartyService.class);
        DungeonService service = service(
                mock(MobService.class),
                mock(DisplayTextService.class),
                worldService,
                mock(MobDropService.class),
                partyService
        );
        World hubWorld = server().addSimpleWorld("dungeon-waiting-hub-empty");
        World outsideWorld = server().addSimpleWorld("dungeon-waiting-hub-outside");
        PlayerMock player = server().addPlayer();
        player.teleport(new Location(outsideWorld, 0.5D, 65.0D, 0.5D));
        UUID partyId = UUID.randomUUID();
        Party party = new Party(partyId, player.getUniqueId());
        when(partyService.findPartyById(partyId)).thenReturn(party);
        WorldMasterData hubData = worldData("hub", WorldType.BASE);
        when(worldService.getById("hub")).thenReturn(hubData);
        when(worldService.resolveLoadedWorld(hubData)).thenReturn(hubWorld);
        Object session = session(player.getUniqueId(), List.of(), "party:" + partyId);

        try (MockedStatic<Logger> ignored = Mockito.mockStatic(Logger.class)) {
            invoke(service, "synchronizeWaitingParty", session);
        }

        assertTrue(field(session, "ending", Boolean.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 4. 開始・生成・転送
     * 検証契約: 待機中の現在パーティーメンバーが一人でもHubに残る場合は挑戦を継続する。
     */
    @Test
    void keepsWaitingPartySessionWhileCurrentMemberRemainsInHub() throws Exception {
        WorldService worldService = mock(WorldService.class);
        PartyService partyService = mock(PartyService.class);
        DungeonService service = service(
                mock(MobService.class),
                mock(DisplayTextService.class),
                worldService,
                mock(MobDropService.class),
                partyService
        );
        World hubWorld = server().addSimpleWorld("dungeon-waiting-hub-occupied");
        World outsideWorld = server().addSimpleWorld("dungeon-waiting-hub-partially-outside");
        PlayerMock remainingPlayer = server().addPlayer();
        remainingPlayer.teleport(new Location(hubWorld, 0.5D, 65.0D, 0.5D));
        PlayerMock outsidePlayer = server().addPlayer();
        outsidePlayer.teleport(new Location(outsideWorld, 0.5D, 65.0D, 0.5D));
        UUID partyId = UUID.randomUUID();
        Party party = new Party(partyId, remainingPlayer.getUniqueId());
        party.addMember(outsidePlayer.getUniqueId());
        when(partyService.findPartyById(partyId)).thenReturn(party);
        WorldMasterData hubData = worldData("hub", WorldType.BASE);
        when(worldService.getById("hub")).thenReturn(hubData);
        when(worldService.resolveLoadedWorld(hubData)).thenReturn(hubWorld);
        Object session = session(
                List.of(remainingPlayer.getUniqueId(), outsidePlayer.getUniqueId()),
                List.of(),
                "party:" + partyId
        );

        invoke(service, "synchronizeWaitingParty", session);

        assertFalse(field(session, "ending", Boolean.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: ACTIVE入口閉鎖処理は子部屋境界をガラス封鎖し、CLEARED時の解除処理はAIRへ戻す。
     */
    @Test
    void closesActiveRoomEntranceAndReopensItWhenCleared() throws Exception {
        DungeonService service = service(mock(MobService.class), mock(DisplayTextService.class));
        World world = server().addSimpleWorld("dungeon-active-room-gate");
        PlayerMock player = server().addPlayer();
        player.teleport(new Location(world, 13.5D, 65.0D, 4.5D));
        Object session = session(player.getUniqueId());
        configureRunningSession(service, session, player, world);
        Map<Integer, DungeonMapRoomState> states = mapField(session, "roomStates");
        states.put(0, DungeonMapRoomState.CLEARED);
        states.put(1, DungeonMapRoomState.ACTIVE);
        states.put(2, DungeonMapRoomState.LOCKED);

        invoke(service, "closeActiveRoomEntrance", session, 1);
        for (DungeonBlockPlan.Position position : entrance(11).gateBlocks()) {
            assertEquals(Material.GLASS, world.getBlockAt(position.x(), position.y(), position.z()).getType());
        }

        states.put(1, DungeonMapRoomState.CLEARED);
        invoke(service, "openActiveRoomEntrance", session, 1);
        for (DungeonBlockPlan.Position position : entrance(11).gateBlocks()) {
            assertEquals(Material.AIR, world.getBlockAt(position.x(), position.y(), position.z()).getType());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: Dungeon Mob と参加者のダメージは、同一ACTIVE部屋内にいるときだけ許可する。
     */
    @Test
    void allowsDungeonCombatOnlyForParticipantInsideTheActiveMobRoom() throws Exception {
        DungeonService service = service(mock(MobService.class), mock(DisplayTextService.class));
        World world = server().addSimpleWorld("dungeon-combat-room-boundary");
        PlayerMock player = server().addPlayer();
        player.teleport(new Location(world, 16.5D, 65.0D, 4.5D));
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        Object session = session(player.getUniqueId());
        configureRunningSession(service, session, player, world);
        mapField(session, "roomStates").put(1, DungeonMapRoomState.ACTIVE);
        UUID sessionId = field(session, "id", UUID.class);
        MobInstance mob = new MobInstance(
                UUID.randomUUID(),
                DungeonTestFixtures.mob("room_guard", 1, MobCategory.ENEMY),
                new Location(world, 16.5D, 65.0D, 4.5D)
        );
        mapField(service, "mobBindings").put(mob.instanceId(), mobBinding(sessionId, 1));

        assertTrue(service.canApplyCombatDamage(AstEntity.player(astPlayer), AstEntity.mob(mob)));
        assertTrue(service.canApplyCombatDamage(AstEntity.mob(mob), AstEntity.player(astPlayer)));

        player.teleport(new Location(world, 10.5D, 65.0D, 4.5D));
        assertFalse(service.canApplyCombatDamage(AstEntity.player(astPlayer), AstEntity.mob(mob)));
        assertFalse(service.canApplyCombatDamage(AstEntity.mob(mob), AstEntity.player(astPlayer)));

        player.teleport(new Location(world, 16.5D, 65.0D, 4.5D));
        mapField(session, "roomStates").put(1, DungeonMapRoomState.CLEARED);
        assertFalse(service.canApplyCombatDamage(AstEntity.player(astPlayer), AstEntity.mob(mob)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 操作本人がACTIVE部屋内にいる場合、CLEARED部屋へのカルトグラフ転送を開始しない。
     */
    @Test
    void rejectsCartographTeleportFromAnActiveRoom() throws Exception {
        WorldService worldService = mock(WorldService.class);
        DungeonService service = service(mock(MobService.class), mock(DisplayTextService.class), worldService);
        World world = server().addSimpleWorld("dungeon-active-cartograph-lock");
        PlayerMock player = server().addPlayer();
        player.teleport(new Location(world, 16.5D, 65.0D, 4.5D));
        Object session = session(player.getUniqueId());
        configureRunningSession(service, session, player, world);
        Map<Integer, DungeonMapRoomState> states = mapField(session, "roomStates");
        states.put(0, DungeonMapRoomState.CLEARED);
        states.put(1, DungeonMapRoomState.ACTIVE);
        states.put(2, DungeonMapRoomState.LOCKED);
        UUID sessionId = field(session, "id", UUID.class);
        CartographSessionRegistry bindings = field(
                service, "cartographBindings", CartographSessionRegistry.class);
        bindings.bind("cartograph-instance", player.getUniqueId(), sessionId);

        assertFalse(service.teleportToClearedRoom(player, sessionId, 0));

        verifyNoInteractions(worldService);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: チャンク準備中に操作本人がACTIVE部屋へ入った場合、実転送直前条件は失効し、保留転送は多重開始しない。
     */
    @Test
    void revalidatesActiveRoomImmediatelyBeforeCartographTeleport() throws Exception {
        WorldService worldService = mock(WorldService.class);
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        when(worldService.teleportPlayerAsync(
                any(Player.class), any(Location.class), isNull(), any(BooleanSupplier.class)))
                .thenReturn(pending);
        DungeonService service = service(mock(MobService.class), mock(DisplayTextService.class), worldService);
        World world = mockSafeWorld("dungeon-cartograph-active-race");
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        AtomicReference<Location> location = new AtomicReference<>(
                new Location(world, 4.5D, 65.0D, 4.5D));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(ignored -> location.get().clone());
        when(player.isOnline()).thenReturn(true);
        Object session = session(player.getUniqueId());
        configureRunningSession(service, session, player, world);
        Map<Integer, DungeonMapRoomState> states = mapField(session, "roomStates");
        states.put(0, DungeonMapRoomState.CLEARED);
        states.put(1, DungeonMapRoomState.AVAILABLE);
        UUID sessionId = field(session, "id", UUID.class);
        field(service, "cartographBindings", CartographSessionRegistry.class)
                .bind("cartograph-race", player.getUniqueId(), sessionId);

        assertTrue(service.teleportToClearedRoom(player, sessionId, 0));
        assertFalse(service.teleportToClearedRoom(player, sessionId, 0));
        ArgumentCaptor<BooleanSupplier> guard = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(worldService).teleportPlayerAsync(
                eq(player), any(Location.class), isNull(), guard.capture());
        assertTrue(guard.getValue().getAsBoolean());

        states.put(1, DungeonMapRoomState.ACTIVE);
        location.set(new Location(world, 16.5D, 65.0D, 4.5D));

        assertFalse(guard.getValue().getAsBoolean());
        assertTrue(mapField(session, "cartographTransfers").containsKey(player.getUniqueId()));
        pending.complete(false);
        server().getScheduler().performTicks(1L);
        assertTrue(mapField(session, "cartographTransfers").isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 7. 終了と回収
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: セッション終了は保留中のカルトグラフ転送を終了待機へ含め、実転送直前条件を失効させる。
     */
    @Test
    void endingSessionInvalidatesAndWaitsForPendingCartographTeleport() throws Exception {
        WorldService worldService = mock(WorldService.class);
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        when(worldService.teleportPlayerAsync(
                any(Player.class), any(Location.class), isNull(), any(BooleanSupplier.class)))
                .thenReturn(pending);
        DungeonService service = service(mock(MobService.class), mock(DisplayTextService.class), worldService);
        World world = mockSafeWorld("dungeon-cartograph-ending-race");
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 4.5D, 65.0D, 4.5D));
        when(player.isOnline()).thenReturn(true);
        Object session = session(player.getUniqueId());
        configureRunningSession(service, session, player, world);
        mapField(session, "roomStates").put(0, DungeonMapRoomState.CLEARED);
        UUID sessionId = field(session, "id", UUID.class);
        field(service, "cartographBindings", CartographSessionRegistry.class)
                .bind("cartograph-ending", player.getUniqueId(), sessionId);
        assertTrue(service.teleportToClearedRoom(player, sessionId, 0));
        ArgumentCaptor<BooleanSupplier> guard = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(worldService).teleportPlayerAsync(
                eq(player), any(Location.class), isNull(), guard.capture());

        try (MockedStatic<Logger> ignored = Mockito.mockStatic(Logger.class)) {
            invokeCompleteSession(service, session, "CANCELLED");
        }

        assertFalse(guard.getValue().getAsBoolean());
        assertTrue(mapField(service, "sessionsById").containsKey(sessionId),
                "session index must remain until the pending cartograph transfer settles");
        assertFalse(pending.isDone());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: ACTIVE入口の通路側では向き保持共通転送を一度だけ開始し、失敗完了時にpendingを解除し、成功完了時にもACTIVE状態を再検証する。
     */
    @Test
    void transfersCorridorParticipantOnceAndClearsPendingOnFailure() throws Exception {
        DungeonService service = service(mock(MobService.class), mock(DisplayTextService.class));
        World world = server().addSimpleWorld("dungeon-active-room-entry");
        PlayerMock player = server().addPlayer();
        Location approach = new Location(world, 10.5D, 65.0D, 4.5D, 123.0F, -20.0F);
        player.teleport(approach);
        Object session = session(player.getUniqueId());
        configureRunningSession(service, session, player, world);
        mapField(session, "roomStates").put(1, DungeonMapRoomState.ACTIVE);
        @SuppressWarnings("unchecked")
        Set<Integer> closedEntrances = field(session, "closedActiveRoomEntrances", Set.class);
        closedEntrances.add(1);
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        CompletableFuture<Boolean> staleSuccess = new CompletableFuture<>();

        try (MockedStatic<PlayerTeleportService> teleports = Mockito.mockStatic(PlayerTeleportService.class)) {
            teleports.when(() -> PlayerTeleportService.teleportAsync(any(PlayerMock.class), any(Location.class)))
                    .thenReturn(pending, staleSuccess);

            service.handleMove(player, approach);
            service.handleMove(player, approach);

            teleports.verify(() -> PlayerTeleportService.teleportAsync(
                    any(PlayerMock.class),
                    argThat(target -> target.getWorld() == world
                            && target.getBlockX() == 12
                            && target.getBlockY() == 65
                            && target.getBlockZ() == 4)
            ), times(1));
            pending.complete(false);
            server().getScheduler().performTicks(1L);

            service.handleMove(player, approach);
            mapField(session, "roomStates").put(1, DungeonMapRoomState.CLEARED);
            staleSuccess.complete(true);
            server().getScheduler().performTicks(1L);
            teleports.verify(() -> PlayerTeleportService.teleportAsync(
                    any(PlayerMock.class), any(Location.class)), times(2));
        }

        assertTrue(mapField(session, "pendingRoomEntryByParticipant").isEmpty());
        assertTrue(mapField(session, "entryTransfers").isEmpty());
        assertTrue(mapField(session, "currentRoomByParticipant").isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 4. 開始・生成・転送
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 7. 終了と回収
     * 検証契約: DungeonServiceは全室表示を生成し、実カウント完了時にSTARTをMobなしで直接完了して次室表示を更新し、Plugin停止経路で全表示を一度だけ破棄する。
     */
    @Test
    void serviceCompletesSafeStartUpdatesDisplaysAndDestroysThem() throws Exception {
        MobService mobService = mock(MobService.class);
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        DisplayTextService.ManagedTextDisplay startDisplay = mock(DisplayTextService.ManagedTextDisplay.class);
        DisplayTextService.ManagedTextDisplay normalDisplay = mock(DisplayTextService.ManagedTextDisplay.class);
        DisplayTextService.ManagedTextDisplay bossDisplay = mock(DisplayTextService.ManagedTextDisplay.class);
        when(displayTextService.create(any(DisplayAnchor.class), any(DisplayTextOptions.class)))
                .thenReturn(startDisplay, normalDisplay, bossDisplay);
        DungeonService service = service(mobService, displayTextService);

        World world = server().addSimpleWorld("dungeon-room-lifecycle");
        PlayerMock player = server().addPlayer();
        player.teleport(new Location(world, 4.5D, 65.0D, 4.5D));
        DungeonLayout layout = layout();
        Object session = session(player.getUniqueId());
        setField(session, "layout", layout);
        setField(session, "blockPlan", blockPlan());
        setField(session, "instanceWorld", new DungeonInstanceWorldService.InstanceWorld(
                world,
                Path.of("target", "dungeon-room-lifecycle"),
                Set.of()
        ));
        Map<Integer, DungeonMapRoomState> states = mapField(session, "roomStates");
        states.put(0, DungeonMapRoomState.AVAILABLE);
        states.put(1, DungeonMapRoomState.LOCKED);
        states.put(2, DungeonMapRoomState.LOCKED);
        UUID sessionId = field(session, "id", UUID.class);
        Map<UUID, Object> sessions = mapField(service, "sessionsById");
        sessions.put(sessionId, session);

        invoke(service, "createRoomStatusDisplays", session);

        ArgumentCaptor<DisplayTextOptions> initialOptions = ArgumentCaptor.forClass(DisplayTextOptions.class);
        verify(displayTextService, times(3)).create(any(DisplayAnchor.class), initialOptions.capture());
        assertTrue(initialOptions.getAllValues().get(0).text().contains("安全地帯"));
        assertTrue(initialOptions.getAllValues().get(1).text().contains("未開放"));
        assertTrue(initialOptions.getAllValues().get(2).text().contains("未開放"));

        try (MockedStatic<Logger> ignored = Mockito.mockStatic(Logger.class)) {
            invoke(service, "beginStartCountdown", session);
            server().getScheduler().performTicks(220L);
        }

        assertEquals(DungeonMapRoomState.CLEARED, states.get(0));
        assertEquals(DungeonMapRoomState.AVAILABLE, states.get(1));
        assertEquals(DungeonMapRoomState.LOCKED, states.get(2));
        assertTrue(field(session, "combatStarted", Boolean.class));
        verifyNoInteractions(mobService);
        verify(startDisplay).setText(argThat(text -> text.contains("安全地帯")));
        verify(normalDisplay).setText(argThat(text -> text.contains("進入可能")));
        verify(bossDisplay).setText(argThat(text -> text.contains("未開放")));

        mapField(session, "pendingRoomEntryByParticipant").put(player.getUniqueId(), 1);
        @SuppressWarnings("unchecked")
        Set<Integer> closedEntrances = field(session, "closedActiveRoomEntrances", Set.class);
        closedEntrances.add(1);
        service.stop();
        service.stop();

        verify(startDisplay, times(1)).destroy();
        verify(normalDisplay, times(1)).destroy();
        verify(bossDisplay, times(1)).destroy();
        assertTrue(mapField(session, "roomStatusDisplays").isEmpty());
        assertTrue(mapField(session, "pendingRoomEntryByParticipant").isEmpty());
        assertTrue(closedEntrances.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: ボス部屋クリアは確定時にDungeon world内にいる現在参加者だけをdungeon ID付きでガイド進捗へ通知する。
     */
    @Test
    void bossClearNotifiesOnlyEligibleParticipantsInDungeonWorld() throws Exception {
        MobDropService mobDropService = mock(MobDropService.class);
        when(mobDropService.roll(any(MobDropConfig.class), any(AstPlayer.class)))
                .thenReturn(new MobDropResult(List.of(), 0, 0));
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        when(displayTextService.create(any(DisplayAnchor.class), any(DisplayTextOptions.class)))
                .thenReturn(mock(DisplayTextService.ManagedTextDisplay.class));
        AdventureRecordRepository adventureRecordRepository = mock(AdventureRecordRepository.class);
        when(adventureRecordRepository.recordDungeonClear(
                any(UUID.class), eq("test_dungeon"), any(UUID.class)))
                .thenAnswer(invocation -> new AdventureDungeonRecord(
                        UUID.randomUUID(), invocation.getArgument(0), "test_dungeon", 1L,
                        Instant.now(), Instant.now()));
        DungeonService service = service(
                mock(MobService.class), displayTextService, mobDropService,
                adventureRecordRepository);
        World dungeonWorld = server().addSimpleWorld("dungeon-clear-guide");
        World outsideWorld = server().addSimpleWorld("dungeon-clear-guide-outside");
        PlayerMock eligible = server().addPlayer();
        eligible.teleport(new Location(dungeonWorld, 28.5D, 65.0D, 4.5D));
        PlayerMock outside = server().addPlayer();
        outside.teleport(new Location(outsideWorld, 0.5D, 65.0D, 0.5D));
        AstPlayer eligibleAstPlayer = DesignTestFixtures.astPlayer(eligible, AccountMode.PLAYER);
        AstPlayer outsideAstPlayer = DesignTestFixtures.astPlayer(outside, AccountMode.PLAYER);
        AstPlayerCache.put(eligibleAstPlayer);
        AstPlayerCache.put(outsideAstPlayer);
        Object session = session(
                List.of(eligible.getUniqueId(), outside.getUniqueId()), List.of(), "party");
        configureRunningSession(service, session, eligible, dungeonWorld);
        mapField(session, "roomStates").put(2, DungeonMapRoomState.ACTIVE);
        List<String> notifications = new ArrayList<>();
        service.setClearListener((player, dungeonId) ->
                notifications.add(player.getBukkit().getUniqueId() + ":" + dungeonId));

        try (MockedStatic<Logger> ignored = Mockito.mockStatic(Logger.class)) {
            invoke(service, "clearRoom", session, 2);
        }

        assertEquals(List.of(eligible.getUniqueId() + ":test_dungeon"), notifications);
        assertEquals(1L, eligible.getHeardSounds().stream()
                .filter(sound -> sound.getSound().equals("ui.toast.challenge_complete"))
                .count());
        assertEquals(0L, outside.getHeardSounds().stream()
                .filter(sound -> sound.getSound().equals("ui.toast.challenge_complete"))
                .count());
        field(session, "clearReturnTask", BukkitTask.class).cancel();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: ダンジョンクリア時は開始時と同じ完了音と、ダンジョン名付きのクリア title を表示する。
     */
    @Test
    void showsDungeonClearTitleAndCompletionSound() throws Exception {
        DungeonService service = service(mock(MobService.class), mock(DisplayTextService.class));
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getLocation()).thenReturn(location);

        Method method = DungeonService.class.getDeclaredMethod("showDungeonClear", List.class, String.class);
        method.setAccessible(true);
        method.invoke(service, List.of(player), "古代遺跡");

        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(title.capture());
        assertEquals("古代遺跡 クリア", PlainTextComponentSerializer.plainText().serialize(title.getValue().title()));
        assertEquals("ダンジョン", PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle()));
        verify(player).playSound(
                location,
                Sound.UI_TOAST_CHALLENGE_COMPLETE,
                SoundCategory.PLAYERS,
                0.9F,
                1.0F
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 7. 終了と回収
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### 視認距離外 enemy 破棄
     * 検証契約: ダンジョン Mob は視認距離外自動破棄から除外し、セッション停止時には明示的に破棄する。
     */
    @Test
    void dungeonMobsStayWhenUnobservedAndAreDestroyedWhenSessionStops() throws Exception {
        MobService mobService = mock(MobService.class);
        WorldService worldService = mock(WorldService.class);
        DungeonService service = service(mobService, mock(DisplayTextService.class), worldService);
        World world = server().addSimpleWorld("dungeon-mob-lifecycle");
        List<MobInstance> spawned = new ArrayList<>();
        when(mobService.spawn(any(MobTemplate.class), any(Location.class))).thenAnswer(invocation -> {
            MobTemplate template = invocation.getArgument(0, MobTemplate.class);
            Location location = invocation.getArgument(1, Location.class);
            MobInstance instance = new MobInstance(UUID.randomUUID(), template, location);
            spawned.add(instance);
            return instance;
        });

        Object session = session(
                UUID.randomUUID(),
                List.of(new DungeonService.LoadedMob(DungeonTestFixtures.mob("normal", 1, MobCategory.ENEMY), 1))
        );
        setField(session, "layout", layout());
        setField(session, "blockPlan", blockPlan());
        setField(session, "instanceWorld", new DungeonInstanceWorldService.InstanceWorld(
                world,
                Path.of("target", "dungeon-mob-lifecycle"),
                Set.of()
        ));
        mapField(session, "roomStates").put(1, DungeonMapRoomState.ACTIVE);
        mapField(session, "liveMobsByRoom").put(1, new LinkedHashSet<>());

        invoke(service, "activateRoomContent", session, 1);

        assertTrue(!spawned.isEmpty());
        assertTrue(spawned.stream().allMatch(MobInstance::keepWhenUnobserved));
        UUID sessionId = field(session, "id", UUID.class);
        mapField(service, "sessionsById").put(sessionId, session);
        service.stop();

        for (MobInstance instance : spawned) {
            verify(mobService).destroy(instance.instanceId());
        }
        verify(worldService).unregisterRuntimeWorld(world);
        Map<Integer, Set<UUID>> liveMobsByRoom = mapField(session, "liveMobsByRoom");
        assertTrue(liveMobsByRoom.get(1).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 10. GUI サウンド意味付け
     * 検証契約: ダンジョン報酬を1個以上インベントリへ付与できた場合だけ、控えめなアイテム受取音を再生する。
     */
    @Test
    void playsRewardSoundWhenDungeonRewardIsGranted() throws Exception {
        MobService mobService = mock(MobService.class);
        DungeonService service = service(mobService, mock(DisplayTextService.class));
        InventoryService inventoryService = field(service, "inventoryService", InventoryService.class);
        ItemService itemService = field(service, "itemService", ItemService.class);
        ItemModel model = mock(ItemModel.class);
        when(itemService.findLoadedById("reward_item")).thenReturn(model);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class);
        Object session = session(player.getUniqueId());
        UUID sessionId = field(session, "id", UUID.class);
        UUID claimId = UUID.randomUUID();
        setField(session, "cleared", true);
        mapField(session, "rewardsByPlayer").put(
                player.getUniqueId(), new ArrayList<>(List.of(
                        new DungeonRewardEntry(claimId, "reward_item", 1, 1.0D))));
        mapField(service, "sessionsById").put(sessionId, session);
        when(inventoryService.addItemToNormalInventory(astPlayer, model, 1, "dungeon_clear"))
                .thenReturn(1);

        try (MockedStatic<AstPlayerCache> cache = Mockito.mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            service.handleRewardClick(player, sessionId, 0, 0, claimId);
        }

        assertEquals(1L, player.getHeardSounds().stream()
                .filter(sound -> sound.getSound().equals("entity.item.pickup"))
                .count());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 10. AFK判定状態
     * 検証契約: AFK中のプレイヤーにはダンジョンクリア報酬を抽選しない。
     */
    @Test
    void doesNotRollClearRewardsForAfkPlayer() {
        MobDropService mobDropService = mock(MobDropService.class);
        DungeonService service = service(mock(MobService.class), mock(DisplayTextService.class), mobDropService);
        AfkService afkService = mock(AfkService.class);
        AstPlayer recipient = mock(AstPlayer.class);
        service.setAfkService(afkService);
        when(afkService.isAfk(recipient)).thenReturn(true);

        assertTrue(service.rollClearRewards(recipient, DungeonTestFixtures.definition()).isEmpty());

        verifyNoInteractions(mobDropService);
    }

    /** テスト対象サービスを最小依存で構成します。 */
    private DungeonService service(MobService mobService, DisplayTextService displayTextService) {
        return service(mobService, displayTextService, mock(WorldService.class), mock(MobDropService.class));
    }

    /** テスト対象サービスをWorldServiceの検証可能な依存で構成します。 */
    private DungeonService service(
            MobService mobService,
            DisplayTextService displayTextService,
            WorldService worldService
    ) {
        return service(mobService, displayTextService, worldService, mock(MobDropService.class));
    }

    /** テスト対象サービスをMobドロップ抽選サービスの検証可能な依存で構成します。 */
    private DungeonService service(
            MobService mobService,
            DisplayTextService displayTextService,
            MobDropService mobDropService
    ) {
        return service(mobService, displayTextService, mock(WorldService.class), mobDropService);
    }

    /** 踏破記録リポジトリまで検証可能な依存でサービスを構成します。 */
    private DungeonService service(
            MobService mobService,
            DisplayTextService displayTextService,
            MobDropService mobDropService,
            AdventureRecordRepository adventureRecordRepository
    ) {
        return service(
                mobService, displayTextService, mock(WorldService.class), mobDropService,
                mock(PartyService.class), adventureRecordRepository);
    }

    /** テスト対象サービスをWorldとMobドロップ抽選サービスの検証可能な依存で構成します。 */
    private DungeonService service(
            MobService mobService,
            DisplayTextService displayTextService,
            WorldService worldService,
            MobDropService mobDropService
    ) {
        return service(mobService, displayTextService, worldService, mobDropService, mock(PartyService.class));
    }

    /** テスト対象サービスをPartyServiceまで検証可能な依存で構成します。 */
    private DungeonService service(
            MobService mobService,
            DisplayTextService displayTextService,
            WorldService worldService,
            MobDropService mobDropService,
            PartyService partyService
    ) {
        return service(
                mobService, displayTextService, worldService, mobDropService, partyService,
                mock(AdventureRecordRepository.class));
    }

    /** すべての可変依存を指定してテスト対象サービスを構成します。 */
    private DungeonService service(
            MobService mobService,
            DisplayTextService displayTextService,
            WorldService worldService,
            MobDropService mobDropService,
            PartyService partyService,
            AdventureRecordRepository adventureRecordRepository
    ) {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("DungeonServiceRoomLifecycleTest");
        return new DungeonService(
                plugin,
                mock(DungeonDefinitionRepository.class),
                worldService,
                partyService,
                mobService,
                mock(PlayerMessageService.class),
                mock(ParticleDisplayService.class),
                displayTextService,
                mock(PlayerDeathService.class),
                mobDropService,
                mock(InventoryService.class),
                mock(ItemService.class),
                mock(ItemStackFactory.class),
                mock(LootService.class),
                adventureRecordRepository,
                "hub"
        );
    }

    /** START、NORMAL、BOSSが直列接続された最小配置を返します。 */
    private DungeonLayout layout() {
        return new DungeonLayout(
                1L,
                64,
                64,
                64,
                8,
                List.of(
                        room(0, DungeonLayout.RoomRole.START, 0),
                        room(1, DungeonLayout.RoomRole.NORMAL, 1),
                        room(2, DungeonLayout.RoomRole.BOSS, 2)
                ),
                List.of(
                        new DungeonLayout.Connection(10, 0, 1, List.of()),
                        new DungeonLayout.Connection(11, 1, 2, List.of())
                ),
                0,
                2
        );
    }

    /** 指定役割と距離を持つテスト部屋を返します。 */
    private DungeonLayout.Room room(int id, DungeonLayout.RoomRole role, int distanceFromStart) {
        return new DungeonLayout.Room(
                id,
                new DungeonLayout.Rect(id * 12, 0, id * 12 + 8, 8),
                DungeonRoomShape.RECTANGLE,
                role,
                distanceFromStart
        );
    }

    /** 表示座標と空のゲート座標を持つ最小ブロック計画を返します。 */
    private DungeonBlockPlan blockPlan() {
        Map<Integer, List<DungeonBlockPlan.Position>> emptyGates = Map.of(
                10, List.of(),
                11, List.of()
        );
        Map<Integer, List<DungeonBlockPlan.Position>> spawnPoints = Map.of(
                0, List.of(new DungeonBlockPlan.Position(4, 65, 4)),
                1, List.of(new DungeonBlockPlan.Position(16, 65, 4)),
                2, List.of(new DungeonBlockPlan.Position(28, 65, 4))
        );
        return new DungeonBlockPlan(
                List.of(),
                emptyGates,
                emptyGates,
                Map.of(
                        1, entrance(11),
                        2, entrance(23)
                ),
                spawnPoints,
                spawnPoints.get(0).getFirst()
        );
    }

    /** X正方向から入るテスト用子部屋入口を返します。 */
    private DungeonBlockPlan.RoomEntrance entrance(int gateX) {
        return new DungeonBlockPlan.RoomEntrance(
                List.of(
                        new DungeonBlockPlan.Position(gateX, 65, 4),
                        new DungeonBlockPlan.Position(gateX, 66, 4),
                        new DungeonBlockPlan.Position(gateX, 67, 4)
                ),
                List.of(new DungeonBlockPlan.Position(gateX - 1, 65, 4)),
                new DungeonBlockPlan.Position(gateX + 1, 65, 4)
        );
    }

    /** 稼働中セッションに必要な配置・World・reverse indexを設定します。 */
    private void configureRunningSession(
            DungeonService service,
            Object session,
            Player player,
            World world
    ) throws ReflectiveOperationException {
        setField(session, "layout", layout());
        setField(session, "blockPlan", blockPlan());
        setField(session, "instanceWorld", new DungeonInstanceWorldService.InstanceWorld(
                world,
                Path.of("target", world.getName()),
                Set.of()
        ));
        setField(session, "combatStarted", true);
        UUID sessionId = field(session, "id", UUID.class);
        mapField(service, "sessionsById").put(sessionId, session);
        mapField(service, "sessionIdByParticipant").put(player.getUniqueId(), sessionId);
    }

    /** 安全な転送候補ブロックを返す最小World mockを構築します。 */
    private World mockSafeWorld(String name) {
        World world = mock(World.class);
        Block feet = mock(Block.class);
        Block head = mock(Block.class);
        Block floor = mock(Block.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn(name);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
                .thenReturn(feet);
        when(feet.isPassable()).thenReturn(true);
        when(feet.getRelative(BlockFace.UP)).thenReturn(head);
        when(feet.getRelative(BlockFace.DOWN)).thenReturn(floor);
        when(head.isPassable()).thenReturn(true);
        when(floor.isPassable()).thenReturn(false);
        return world;
    }

    /** DungeonServiceのprivate Sessionをテスト用に最小構成します。 */
    private Object session(UUID participantId) throws ReflectiveOperationException {
        return session(participantId, List.of());
    }

    /** 通常 Mob のスナップショットを指定して DungeonService の private Session を構築します。 */
    private Object session(UUID participantId, List<DungeonService.LoadedMob> normalMobs)
            throws ReflectiveOperationException {
        return session(participantId, normalMobs, "party");
    }

    /** パーティーキーと通常 Mob のスナップショットを指定して private Session を構築します。 */
    private Object session(
            UUID participantId,
            List<DungeonService.LoadedMob> normalMobs,
            String partyKey
    ) throws ReflectiveOperationException {
        return session(List.of(participantId), normalMobs, partyKey);
    }

    /** パーティーキー、参加者、通常 Mob のスナップショットを指定して private Session を構築します。 */
    private Object session(
            List<UUID> participantIds,
            List<DungeonService.LoadedMob> normalMobs,
            String partyKey
    ) throws ReflectiveOperationException {
        Class<?> sessionType = sessionType();
        Constructor<?> constructor = sessionType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        DungeonService.LoadedDefinition loaded = new DungeonService.LoadedDefinition(
                DungeonTestFixtures.definition(),
                worldData("entry", WorldType.BASE),
                worldData("instance", WorldType.DUNGEON),
                normalMobs,
                DungeonTestFixtures.mob("boss", 1, MobCategory.BOSS)
        );
        return constructor.newInstance(
                UUID.randomUUID(),
                1L,
                loaded,
                partyKey,
                UUID.randomUUID(),
                new LinkedHashSet<>(participantIds),
                new HashMap<UUID, Location>()
        );
    }

    /** LoadedDefinitionへ渡す最小Worldマスタを構築します。 */
    private WorldMasterData worldData(String id, WorldType type) {
        return new WorldMasterData(
                1,
                id,
                id,
                type,
                "",
                "target/dungeon-room-lifecycle",
                false,
                type == WorldType.DUNGEON,
                4,
                false,
                false,
                false,
                false,
                WorldSpawnLocation.defaultLocation(),
                "",
                null,
                null,
                null
        );
    }

    /** privateフィールドを指定型で取得します。 */
    private <T> T field(Object target, String name, Class<T> type) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    /** Sessionのprivateフィールドへテスト値を設定します。 */
    private void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** SessionのMapフィールドを型付きで取得します。 */
    @SuppressWarnings("unchecked")
    private <K, V> Map<K, V> mapField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Map<K, V>) field.get(target);
    }

    /** Sessionを引数に取るDungeonServiceのprivateメソッドを呼び出します。 */
    private void invoke(DungeonService service, String name, Object session) throws ReflectiveOperationException {
        Method method = DungeonService.class.getDeclaredMethod(name, sessionType());
        method.setAccessible(true);
        method.invoke(service, session);
    }

    /** Session と部屋 ID を引数に取る DungeonService の private メソッドを呼び出します。 */
    private void invoke(DungeonService service, String name, Object session, int roomId)
            throws ReflectiveOperationException {
        Method method = DungeonService.class.getDeclaredMethod(name, sessionType(), int.class);
        method.setAccessible(true);
        method.invoke(service, session, roomId);
    }

    /** private completeSession を終了理由名で呼び出します。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeCompleteSession(DungeonService service, Object session, String reason)
            throws ReflectiveOperationException {
        Class<?> reasonType = List.of(DungeonService.class.getDeclaredClasses()).stream()
                .filter(type -> type.getSimpleName().equals("EndReason"))
                .findFirst()
                .orElseThrow();
        Method method = DungeonService.class.getDeclaredMethod(
                "completeSession", sessionType(), reasonType, boolean.class);
        method.setAccessible(true);
        method.invoke(service, session, Enum.valueOf((Class<Enum>) reasonType, reason), false);
    }

    /** DungeonService内のprivate Session型を解決します。 */
    private Class<?> sessionType() {
        return List.of(DungeonService.class.getDeclaredClasses()).stream()
                .filter(type -> type.getSimpleName().equals("Session"))
                .findFirst()
                .orElseThrow();
    }

    /** DungeonService内のprivate MobBindingをテスト用に構築します。 */
    private Object mobBinding(UUID sessionId, int roomId) throws ReflectiveOperationException {
        Class<?> bindingType = List.of(DungeonService.class.getDeclaredClasses()).stream()
                .filter(type -> type.getSimpleName().equals("MobBinding"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = bindingType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(sessionId, roomId);
    }
}
