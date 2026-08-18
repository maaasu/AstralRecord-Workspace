package io.github.maaasu.astralRecord.feature.gathering.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringInstance;
import io.github.maaasu.astralRecord.feature.gathering.repository.GatheringDefinitionRepository;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentDurabilityService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassExperienceResult;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatheringServiceMiningSessionTest {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7. GatheringService メソッド仕様 > ### 採集開始・継続
     * 検証契約: 同一instance/toolのactive session中は連打damageを追加せず8tick後だけ再damageする。
     */
    @Test
    void activeSessionIgnoresRepeatedClicksAndDamagesAgainAfterCooldown() {
        Fixture fixture = createFixture();

        assertTrue(fixture.service().startMining(fixture.player()));
        assertEquals(9, fixture.instance().currentHealth());

        assertTrue(fixture.service().startMining(fixture.player()));
        assertEquals(9, fixture.instance().currentHealth());

        Runnable continuation = captureContinuation(fixture);
        continuation.run();

        assertEquals(8, fixture.instance().currentHealth());
        verify(fixture.player(), times(2)).swingMainHand();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7. GatheringService メソッド仕様 > ### 採集開始・継続
     * 検証契約: 視線が外れたら採集sessionを停止し対象HPを初期化する。
     */
    @Test
    void lookingAwayCancelsSessionAndRestoresObjectHealth() {
        Fixture fixture = createFixture();
        assertTrue(fixture.service().startMining(fixture.player()));
        assertEquals(9, fixture.instance().currentHealth());

        Runnable continuation = captureContinuation(fixture);
        when(fixture.player().getEyeLocation()).thenReturn(
                new Location(fixture.world(), 0.5D, 0.55D, -1.0D, 180.0F, 0.0F)
        );
        continuation.run();

        assertEquals(10, fixture.instance().currentHealth());
        assertFalse(fixture.service().isMining(fixture.player(), fixture.instance().instanceId()));
        verify(fixture.task()).cancel();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_2-ユースケース.md
     * 章・見出し: # 12_2-ユースケース > ## 10. 採集 object を採集する
     * 検証契約: 採集 object の破壊完了時に、drops.expをアカウント・クラス経験値へ反映し、メインハンドのツール耐久値消費を1回実行する。
     */
    @Test
    void destroyingObjectConsumesToolDurabilityAndGrantsExperience() {
        Fixture fixture = createFixture(1, 7);
        UUID userId = UUID.randomUUID();
        UserModel user = mock(UserModel.class);
        when(user.getUuid()).thenReturn(userId);
        when(fixture.astPlayer().getUser()).thenReturn(user);

        AccountModel currentAccount = fixture.astPlayer().getAccount();
        AccountModel updatedAccount = mock(AccountModel.class);
        when(fixture.dropService().roll(any(MobDropConfig.class), eq(fixture.astPlayer())))
            .thenReturn(new MobDropResult(List.of(), 7, 0));
        when(fixture.accountService().grantExperienceCached(currentAccount, 7, userId))
            .thenReturn(new AccountExperienceResult(currentAccount, updatedAccount, 7, 0));
        when(fixture.playerClassService().grantClassExperience(fixture.astPlayer(), 7))
            .thenReturn(new ClassExperienceResult(1, 1, 7, 0));

        assertTrue(fixture.service().startMining(fixture.player()));

        verify(fixture.equipmentDurabilityService()).consumeOnGathering(fixture.astPlayer());
        verify(fixture.accountService()).grantExperienceCached(currentAccount, 7, userId);
        verify(fixture.playerClassService()).grantClassExperience(fixture.astPlayer(), 7);
        assertNull(fixture.service().getInstance(fixture.instance().instanceId()));

    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7. GatheringService メソッド仕様 > ### 採集 instance の生成・破棄
     * 検証契約: 同一ワールドの同一ブロック座標に既存の採集 object がある場合、新しい instance を生成しない。
     */
    @Test
    void rejectsGatheringSpawnOnAnOccupiedBlock() {
        Fixture fixture = createFixture();

        assertNull(fixture.service().spawn(
                "test_ore",
                new Location(fixture.world(), 0.9D, 0.25D, 0.2D)
        ));
        assertEquals(1, fixture.service().getInstances().size());
    }

    private Runnable captureContinuation(Fixture fixture) {
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler()).runTaskTimer(
                eq(fixture.plugin()),
                runnable.capture(),
                eq(8L),
                eq(8L)
        );
        return runnable.getValue();
    }

    private Fixture createFixture() {
        return createFixture(10, 0);
    }

    private Fixture createFixture(int maxHealth, int experience) {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(8L), eq(8L))).thenReturn(task);

        UUID playerId = UUID.randomUUID();
        World world = mock(World.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.5D, 0.55D, -1.0D, 0.0F, 0.0F));
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayer(playerId)).thenReturn(player);

        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getAccount()).thenReturn(account);
        when(astPlayer.getStatusSnapshot()).thenReturn(StatusSnapshot.empty());
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);

        GatheringDefinition definition = new GatheringDefinition(
                1,
                "test_ore",
                "MINING",
                "Test Ore",
                maxHealth,
                Material.STONE,
                new Vector3f(1.0F),
                List.of(),
                new MobDropConfig(experience, null, List.of(), null),
                GatheringDefinition.GatheringSoundConfig.empty()
        );
        GatheringDefinitionRepository repository = mock(GatheringDefinitionRepository.class);
        when(repository.findAll()).thenReturn(List.of(definition));

        MobDropService dropService = mock(MobDropService.class);
        MobDropPresentationService dropPresentationService = mock(MobDropPresentationService.class);
        EquipmentDurabilityService equipmentDurabilityService = mock(EquipmentDurabilityService.class);
        when(equipmentDurabilityService.canUseMainHandTool(astPlayer)).thenReturn(true);
        AccountService accountService = mock(AccountService.class);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);

        GatheringService service = new GatheringService(
                plugin,
                repository,
                dropService,
                mock(ItemService.class),
                dropPresentationService
        );
        service.setEquipmentDurabilityService(equipmentDurabilityService);
        service.setProgressionServices(
            accountService,
            playerClassService,
            skillTreeService,
            mock(ParticleDisplayService.class)
        );
        service.loadAll();
        GatheringInstance instance = service.spawn("test_ore", new Location(world, 0.0D, 0.0D, 0.0D));
        if (instance == null) {
            throw new IllegalStateException("テスト用採集オブジェクトを生成できませんでした");
        }
        return new Fixture(
            plugin,
            scheduler,
            task,
            service,
            player,
            world,
            instance,
            astPlayer,
            dropService,
            equipmentDurabilityService,
            accountService,
            playerClassService
        );
    }

    private record Fixture(
            Plugin plugin,
            BukkitScheduler scheduler,
            BukkitTask task,
            GatheringService service,
            Player player,
            World world,
            GatheringInstance instance,
            AstPlayer astPlayer,
            MobDropService dropService,
            EquipmentDurabilityService equipmentDurabilityService,
            AccountService accountService,
            PlayerClassService playerClassService
    ) {
    }
}
