package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionGatewayEventHandler;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.Server;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoneButtonReachServiceTest {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 拠点初期スポーン付近の石ボタン注視判定
     * 検証契約: 拠点初期スポーンから15メートル以内のプレイヤーモードが石ボタンを見ている場合だけ、ブロックリーチを4.5へ変更する。
     */
    @Test
    void stoneButtonWithinBaseSpawnRadiusEnablesBlockReach() {
        Fixture fixture = fixture(AccountMode.PLAYER, 15.0D, Material.STONE_BUTTON, 0.0D);

        runRefresh(fixture);

        verify(fixture.attribute()).setBaseValue(4.5D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 拠点初期スポーン付近の石ボタン注視判定
     * 検証契約: 石ボタン以外を見ている場合、プレイヤーモードのブロックリーチを0へ戻す。
     */
    @Test
    void nonStoneButtonResetsBlockReach() {
        Fixture fixture = fixture(AccountMode.PLAYER, 0.0D, Material.OAK_PLANKS, 4.5D);

        runRefresh(fixture);

        verify(fixture.attribute()).setBaseValue(0.0D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 拠点初期スポーン付近の石ボタン注視判定
     * 検証契約: 初期スポーンから15メートルを超えるプレイヤーは、石ボタンを見ていてもブロックリーチを拡張しない。
     */
    @Test
    void playerOutsideBaseSpawnRadiusDoesNotEnableBlockReach() {
        Fixture fixture = fixture(AccountMode.PLAYER, 15.01D, Material.STONE_BUTTON, 4.5D);

        runRefresh(fixture);

        verify(fixture.attribute()).setBaseValue(0.0D);
        verify(fixture.world(), never()).rayTraceBlocks(
                any(Location.class),
                any(Vector.class),
                eq(4.5D),
                eq(FluidCollisionMode.NEVER),
                eq(false)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 石ボタン注視更新の開始・停止
     * 検証契約: PLAYER以外のアカウントには、このサービスからブロックリーチ属性を上書きしない。
     */
    @Test
    void nonPlayerAccountIsNotChanged() {
        Fixture fixture = fixture(AccountMode.ADMIN, 0.0D, Material.STONE_BUTTON, 4.5D);

        runRefresh(fixture);

        verify(fixture.attribute(), never()).setBaseValue(any(Double.class));
        verify(fixture.worldService(), never()).findByBukkitWorld(fixture.world());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 石ボタン注視更新の開始・停止
     * 検証契約: AstralRecordのschedulerを使い、メインスレッドの初回遅延0・20tick周期で更新を開始する。
     */
    @Test
    void startUsesAstralRecordSchedulerWithSynchronousTwentyTickRefresh() {
        ScheduledFixture fixture = scheduledFixture(AccountMode.PLAYER, 0.0D, Material.STONE_BUTTON, 0.0D);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(fixture.fixture().astPlayer()));
            fixture.fixture().service().start();

            ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
            verify(fixture.scheduler()).runTaskTimer(
                    eq(fixture.plugin()),
                    runnable.capture(),
                    eq(0L),
                    eq(20L)
            );

            runnable.getValue().run();
        }

        verify(fixture.fixture().attribute()).setBaseValue(4.5D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 石ボタン注視更新の開始・停止
     * 検証契約: stop()は更新タスクを停止し、キャッシュ済みPLAYERのブロックリーチを0へ戻す。
     */
    @Test
    void stopCancelsTaskAndResetsPlayerBlockReach() {
        ScheduledFixture fixture = scheduledFixture(AccountMode.PLAYER, 0.0D, Material.STONE_BUTTON, 4.5D);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(fixture.fixture().astPlayer()));
            fixture.fixture().service().start();
            fixture.fixture().service().stop();
        }

        verify(fixture.task()).cancel();
        verify(fixture.fixture().attribute()).setBaseValue(0.0D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 拠点初期スポーン付近の石ボタン注視判定
     * 検証契約: WorldType.BASE以外では、石ボタンを見ていてもブロックリーチを拡張せずray traceもしない。
     */
    @Test
    void nonBaseWorldDoesNotEnableBlockReach() {
        Fixture fixture = fixture(AccountMode.PLAYER, 0.0D, Material.STONE_BUTTON, 4.5D);
        when(fixture.worldService().findByBukkitWorld(fixture.world())).thenReturn(worldData(WorldType.OVERWORLD));

        runRefresh(fixture);

        verify(fixture.attribute()).setBaseValue(0.0D);
        verify(fixture.world(), never()).rayTraceBlocks(
                any(Location.class),
                any(Vector.class),
                eq(4.5D),
                eq(FluidCollisionMode.NEVER),
                eq(false)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 拠点初期スポーン付近の石ボタン注視判定
     * 検証契約: 初期スポーンを解決できない場合は、石ボタンを見ていてもブロックリーチを0へ戻しray traceしない。
     */
    @Test
    void unresolvedSpawnDoesNotEnableBlockReach() {
        Fixture fixture = fixture(AccountMode.PLAYER, 0.0D, Material.STONE_BUTTON, 4.5D);
        when(fixture.worldService().resolveSpawnLocation(any(WorldMasterData.class))).thenReturn(null);

        runRefresh(fixture);

        verify(fixture.attribute()).setBaseValue(0.0D);
        verify(fixture.world(), never()).rayTraceBlocks(
                any(Location.class),
                any(Vector.class),
                eq(4.5D),
                eq(FluidCollisionMode.NEVER),
                eq(false)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### 拠点初期スポーン付近の石ボタン注視判定
     * 検証契約: ray traceがmissした場合は、プレイヤーモードのブロックリーチを0へ戻す。
     */
    @Test
    void rayTraceMissResetsBlockReach() {
        Fixture fixture = fixture(AccountMode.PLAYER, 0.0D, null, 4.5D);

        runRefresh(fixture);

        verify(fixture.attribute()).setBaseValue(0.0D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### プレイヤーモードのブロッククリック制限
     * 検証契約: 拠点初期スポーンから15メートル以内でも、石ボタン以外のブロッククリックはキャンセル候補になる。
     */
    @Test
    void playerModeNonStoneBlockClickRequestsCancellation() {
        Fixture fixture = fixture(AccountMode.PLAYER, 0.0D, null, 0.0D);

        List<PlayerInputCandidate> candidates = resolveBlockClick(
                fixture,
                Action.RIGHT_CLICK_BLOCK,
                Material.OAK_PLANKS
        );

        assertEquals(1, candidates.size());
        assertEquals(InteractionTier.INPUT_LOCK, candidates.get(0).tier());
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidates.get(0).claimPolicy());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### プレイヤーモードのブロッククリック制限
     * 検証契約: 初期スポーンから15メートルを超えたプレイヤーは、石ボタンをクリックしてもキャンセル候補になる。
     */
    @Test
    void playerModeStoneButtonOutsideBaseSpawnRadiusRequestsCancellation() {
        Fixture fixture = fixture(AccountMode.PLAYER, 15.01D, null, 0.0D);

        List<PlayerInputCandidate> candidates = resolveBlockClick(
                fixture,
                Action.LEFT_CLICK_BLOCK,
                Material.STONE_BUTTON
        );

        assertEquals(1, candidates.size());
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidates.get(0).claimPolicy());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### プレイヤーモードのブロッククリック制限
     * 検証契約: 拠点初期スポーンから15メートル以内の石ボタンクリックは通常処理へ委譲する。
     */
    @Test
    void playerModeStoneButtonWithinBaseSpawnRadiusIsNotGuarded() {
        Fixture fixture = fixture(AccountMode.PLAYER, 15.0D, null, 0.0D);

        List<PlayerInputCandidate> candidates = resolveBlockClick(
                fixture,
                Action.RIGHT_CLICK_BLOCK,
                Material.STONE_BUTTON
        );

        assertTrue(candidates.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 6. 条件付きブロックリーチサービス > ### プレイヤーモードのブロッククリック制限
     * 検証契約: PLAYER以外のアカウントでは、石ボタン条件によるブロッククリックキャンセルを行わない。
     */
    @Test
    void nonPlayerAccountBlockClickIsNotGuarded() {
        Fixture fixture = fixture(AccountMode.ADMIN, 15.01D, null, 0.0D);

        List<PlayerInputCandidate> candidates = resolveBlockClick(
                fixture,
                Action.RIGHT_CLICK_BLOCK,
                Material.OAK_PLANKS
        );

        assertTrue(candidates.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-イベント.md
     * 章・見出し: # 28_3-イベント > ## 1. 右・左クリック受付
     * 検証契約: プレイヤーモードの条件外ブロッククリックは、入力gatewayを通じて元イベントをキャンセルする。
     */
    @Test
    void playerModeInvalidBlockClickIsCancelledByInteractionGateway() {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getCurrentTick()).thenReturn(1);
        Fixture fixture = fixture(plugin, AccountMode.PLAYER, 0.0D, null, 0.0D);
        Block clickedBlock = block(fixture, Material.OAK_PLANKS);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(fixture.player());
        when(event.getHand()).thenReturn(org.bukkit.inventory.EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(clickedBlock);

        AstPlayerCache.put(fixture.astPlayer());
        PlayerInteractionGatewayEventHandler gateway = new PlayerInteractionGatewayEventHandler(
                plugin,
                List.of(fixture.service()),
                ignored -> false,
                ignored -> false,
                ignored -> {
                }
        );

        gateway.onPlayerInteract(event);

        verify(event).setCancelled(true);
        verify(event).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        verify(event).setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    private void runRefresh(Fixture fixture) {
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(fixture.astPlayer()));
            fixture.service().refreshAll();
        }
    }

    private List<PlayerInputCandidate> resolveBlockClick(
            Fixture fixture,
            Action action,
            Material clickedMaterial
    ) {
        Block clickedBlock = block(fixture, clickedMaterial);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        InputFamily family = action == Action.LEFT_CLICK_BLOCK
                ? InputFamily.LEFT_CLICK
                : InputFamily.RIGHT_CLICK;
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
                fixture.player(),
                event,
                null,
                action,
                null,
                clickedBlock,
                null,
                false
        );
        PlayerInputContext<PlayerInteractionSnapshot> context = new PlayerInputContext<>(
                fixture.player().getUniqueId(),
                1L,
                family,
                InputSource.PLAYER_INTERACT,
                snapshot
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player())).thenReturn(fixture.astPlayer());
            return List.copyOf(fixture.service().resolve(context));
        }
    }

    private Block block(Fixture fixture, Material material) {
        Block clickedBlock = mock(Block.class);
        when(clickedBlock.getWorld()).thenReturn(fixture.world());
        when(clickedBlock.getType()).thenReturn(material);
        when(clickedBlock.getX()).thenReturn(1);
        when(clickedBlock.getY()).thenReturn(64);
        when(clickedBlock.getZ()).thenReturn(1);
        when(clickedBlock.getBoundingBox()).thenReturn(
                new BoundingBox(1.0D, 64.0D, 1.0D, 2.0D, 65.0D, 2.0D)
        );
        return clickedBlock;
    }

    private Fixture fixture(
            AccountMode accountMode,
            double playerX,
            Material targetMaterial,
            double currentReach
    ) {
        return fixture(mock(Plugin.class), accountMode, playerX, targetMaterial, currentReach);
    }

    private Fixture fixture(
            Plugin plugin,
            AccountMode accountMode,
            double playerX,
            Material targetMaterial,
            double currentReach
    ) {
        WorldService worldService = mock(WorldService.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        AttributeInstance attribute = mock(AttributeInstance.class);
        RayTraceResult rayTrace = targetMaterial == null ? null : mock(RayTraceResult.class);
        WorldMasterData worldData = baseWorldData();
        Location spawn = new Location(world, 0.0D, 0.0D, 0.0D);

        when(account.getMode()).thenReturn(accountMode);
        when(astPlayer.getAccount()).thenReturn(account);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000401"));
        when(player.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000402"));
        when(player.getLocation()).thenReturn(new Location(world, playerX, 0.0D, 0.0D));
        when(player.getEyeLocation()).thenReturn(new Location(world, playerX, 1.62D, 0.0D));
        when(player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)).thenReturn(attribute);
        when(attribute.getBaseValue()).thenReturn(currentReach);
        when(worldService.findByBukkitWorld(world)).thenReturn(worldData);
        when(worldService.resolveSpawnLocation(worldData)).thenReturn(spawn);
        when(world.rayTraceBlocks(
                any(Location.class),
                any(Vector.class),
                eq(4.5D),
                eq(FluidCollisionMode.NEVER),
                eq(false)
        )).thenReturn(rayTrace);

        if (rayTrace != null) {
            Block target = mock(Block.class);
            when(rayTrace.getHitBlock()).thenReturn(target);
            when(target.getType()).thenReturn(targetMaterial);
        }

        return new Fixture(
                new StoneButtonReachService(plugin, worldService),
                astPlayer,
                attribute,
                world,
                worldService,
                player
        );
    }

    private ScheduledFixture scheduledFixture(
            AccountMode accountMode,
            double playerX,
            Material targetMaterial,
            double currentReach
    ) {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(20L))).thenReturn(task);

        return new ScheduledFixture(
                fixture(plugin, accountMode, playerX, targetMaterial, currentReach),
                plugin,
                scheduler,
                task
        );
    }

    private WorldMasterData baseWorldData() {
        return worldData(WorldType.BASE);
    }

    private WorldMasterData worldData(WorldType worldType) {
        return new WorldMasterData(
                1,
                "central-base",
                "中央拠点",
                worldType,
                "base/central-base",
                "instances/central-base",
                false,
                false,
                100,
                false,
                false,
                false,
                true,
                new WorldSpawnLocation(0.0D, 0.0D, 0.0D, 0.0F, 0.0F),
                "",
                null,
                null,
                null
        );
    }

    private record Fixture(
            StoneButtonReachService service,
            AstPlayer astPlayer,
            AttributeInstance attribute,
            World world,
            WorldService worldService,
            Player player
    ) {
    }

    private record ScheduledFixture(
            Fixture fixture,
            AstralRecord plugin,
            BukkitScheduler scheduler,
            BukkitTask task
    ) {
    }
}
