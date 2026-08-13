package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.adventurerecord.repository.AdventureRecordRepository;
import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.repository.DungeonDefinitionRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
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
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DungeonServiceRoomLifecycleTest extends MockBukkitTestBase {
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

        setField(session, "instanceWorld", null);
        service.stop();
        service.stop();

        verify(startDisplay, times(1)).destroy();
        verify(normalDisplay, times(1)).destroy();
        verify(bossDisplay, times(1)).destroy();
        assertTrue(mapField(session, "roomStatusDisplays").isEmpty());
    }

    /** テスト対象サービスを最小依存で構成します。 */
    private DungeonService service(MobService mobService, DisplayTextService displayTextService) {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("DungeonServiceRoomLifecycleTest");
        return new DungeonService(
                plugin,
                mock(DungeonDefinitionRepository.class),
                mock(WorldService.class),
                mock(PartyService.class),
                mobService,
                mock(PlayerMessageService.class),
                mock(ParticleDisplayService.class),
                displayTextService,
                mock(PlayerDeathService.class),
                mock(MobDropService.class),
                mock(InventoryService.class),
                mock(ItemService.class),
                mock(ItemStackFactory.class),
                mock(LootService.class),
                mock(AdventureRecordRepository.class),
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
                spawnPoints,
                spawnPoints.get(0).getFirst()
        );
    }

    /** DungeonServiceのprivate Sessionをテスト用に最小構成します。 */
    private Object session(UUID participantId) throws ReflectiveOperationException {
        Class<?> sessionType = sessionType();
        Constructor<?> constructor = sessionType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        DungeonService.LoadedDefinition loaded = new DungeonService.LoadedDefinition(
                DungeonTestFixtures.definition(),
                worldData("entry", WorldType.BASE),
                worldData("instance", WorldType.DUNGEON),
                List.of(),
                DungeonTestFixtures.mob("boss", 1, MobCategory.BOSS)
        );
        return constructor.newInstance(
                UUID.randomUUID(),
                1L,
                loaded,
                "party",
                UUID.randomUUID(),
                new LinkedHashSet<>(List.of(participantId)),
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

    /** DungeonService内のprivate Session型を解決します。 */
    private Class<?> sessionType() {
        return List.of(DungeonService.class.getDeclaredClasses()).stream()
                .filter(type -> type.getSimpleName().equals("Session"))
                .findFirst()
                .orElseThrow();
    }
}
