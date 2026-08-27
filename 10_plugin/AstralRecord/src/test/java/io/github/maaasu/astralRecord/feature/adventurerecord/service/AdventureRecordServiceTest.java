package io.github.maaasu.astralRecord.feature.adventurerecord.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureMobRecord;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType;
import io.github.maaasu.astralRecord.feature.adventurerecord.repository.AdventureRecordRepository;
import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Server;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdventureRecordServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 表示 entry 生成
     * 検証契約: 冒険記録の初期一覧はカテゴリ未指定で一度だけ取得し、main-thread callbackへsuper modeと解決済みentryを渡す。
     */
    @Test
    void asyncBuildFetchesRecordsOnceAndPublishesResolvedEntries() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AdventureRecordRepository repository = mock(AdventureRecordRepository.class);
        MobService mobService = mock(MobService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        LootService lootService = mock(LootService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.getAccount()).thenReturn(account);
        when(player.getUser()).thenReturn(user);
        when(account.getUuid()).thenReturn(accountId);
        when(user.getUuid()).thenReturn(userId);
        when(playerSettingService.getPlayerSetting(
            userId,
            PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE
        )).thenReturn(false);
        when(repository.findMobRecords(accountId, null)).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        AdventureRecordService service = new AdventureRecordService(
            plugin,
            repository,
            mobService,
            playerSettingService,
            lootService
        );
        AtomicReference<AdventureRecordService.EntryResult> result = new AtomicReference<>();
        AtomicBoolean failed = new AtomicBoolean();

        service.buildEntriesAsync(
            player,
            AdventureRecordListType.ALL,
            Set.of(),
            result::set,
            () -> failed.set(true)
        );

        assertFalse(failed.get());
        assertNotNull(result.get());
        assertFalse(result.get().superMode());
        assertEquals(List.of(), result.get().entries());
        verify(repository).findMobRecords(accountId, null);
        verify(repository, never()).findMobRecords(accountId, MobCategory.ENEMY);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_1-モデル定義.md
     * 章・見出し: # 21_1-モデル定義 > ## AdventureRecordListType
     * 検証契約: ALL は ENEMY／BOSS を横断し、カテゴリフィルターは選択したカテゴリだけを返す。
     */
    @Test
    void allAndCategoryListsKeepTheirExpectedMobCategories() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant defeatedAt = Instant.parse("2026-08-22T00:00:00Z");
        AstralRecord plugin = mock(AstralRecord.class);
        AdventureRecordRepository repository = mock(AdventureRecordRepository.class);
        MobService mobService = mock(MobService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        LootService lootService = mock(LootService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        MobTemplate enemy = DungeonTestFixtures.mob("enemy", 1, MobCategory.ENEMY);
        MobTemplate boss = DungeonTestFixtures.mob("boss", 1, MobCategory.BOSS);
        AdventureMobRecord enemyRecord = new AdventureMobRecord(
            UUID.randomUUID(), accountId, "enemy", MobCategory.ENEMY, 2, defeatedAt, defeatedAt
        );
        AdventureMobRecord bossRecord = new AdventureMobRecord(
            UUID.randomUUID(), accountId, "boss", MobCategory.BOSS, 1, defeatedAt, defeatedAt
        );
        List<AdventureMobRecord> records = List.of(enemyRecord, bossRecord);

        when(player.getAccount()).thenReturn(account);
        when(player.getUser()).thenReturn(user);
        when(account.getUuid()).thenReturn(accountId);
        when(user.getUuid()).thenReturn(userId);
        when(playerSettingService.getPlayerSetting(
            userId,
            PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE
        )).thenReturn(false);
        when(repository.findMobRecords(accountId, null)).thenReturn(records);
        when(repository.findMobRecords(accountId, MobCategory.ENEMY)).thenReturn(List.of(enemyRecord));
        when(repository.findMobRecords(accountId, MobCategory.BOSS)).thenReturn(List.of(bossRecord));
        when(mobService.findTemplate("enemy")).thenReturn(enemy);
        when(mobService.findTemplate("boss")).thenReturn(boss);

        AdventureRecordService service = new AdventureRecordService(
            plugin,
            repository,
            mobService,
            playerSettingService,
            lootService
        );

        List<AdventureRecordService.Entry> all = service.buildEntries(
            player,
            AdventureRecordListType.ALL,
            Set.of()
        );
        List<AdventureRecordService.Entry> enemies = service.buildEntries(
            player,
            AdventureRecordListType.ENEMY,
            Set.of()
        );
        List<AdventureRecordService.Entry> bosses = service.buildEntries(
            player,
            AdventureRecordListType.BOSS,
            Set.of()
        );

        assertEquals(List.of(MobCategory.ENEMY, MobCategory.BOSS),
            all.stream().map(entry -> entry.template().category()).toList());
        assertEquals(List.of(MobCategory.ENEMY),
            enemies.stream().map(entry -> entry.template().category()).toList());
        assertEquals(List.of(MobCategory.BOSS),
            bosses.stream().map(entry -> entry.template().category()).toList());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 表示 entry 生成
     * 検証契約: SEARCH はロード済み lootTable の平坦化候補も visible drop として照合する。
     */
    @Test
    void searchMatchesLoadedLootTableItems() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        AdventureRecordRepository repository = mock(AdventureRecordRepository.class);
        MobService mobService = mock(MobService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        LootService lootService = mock(LootService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        MobTemplate template = mobWithDrops(
            "loot_mob",
            new MobDropConfig(0, null, List.of(), "loot_table")
        );
        AdventureMobRecord record = new AdventureMobRecord(
            UUID.randomUUID(), accountId, "loot_mob", MobCategory.ENEMY, 1,
            Instant.parse("2026-08-22T00:00:00Z"), Instant.parse("2026-08-22T00:00:00Z")
        );
        LootModel loot = new LootModel(
            1,
            "loot_table",
            "loot_table",
            1,
            List.of(new LootPoolModel(
                "loot_pool",
                1,
                List.of(new LootContent("table_reward", 2, 3, 100.0D))
            ))
        );
        when(player.getAccount()).thenReturn(account);
        when(player.getUser()).thenReturn(user);
        when(account.getUuid()).thenReturn(accountId);
        when(user.getUuid()).thenReturn(userId);
        when(playerSettingService.getPlayerSetting(
            userId,
            PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE
        )).thenReturn(false);
        when(repository.findMobRecords(accountId, null)).thenReturn(List.of(record));
        when(mobService.findTemplate("loot_mob")).thenReturn(template);
        when(lootService.getLoaded("loot_table")).thenReturn(loot);

        AdventureRecordService service = new AdventureRecordService(
            plugin,
            repository,
            mobService,
            playerSettingService,
            lootService
        );

        List<AdventureRecordService.Entry> result = service.buildEntries(
            player,
            AdventureRecordListType.SEARCH,
            Set.of("table_reward")
        );

        assertEquals(List.of("loot_mob"), result.stream().map(entry -> entry.template().id()).toList());
    }

    private MobTemplate mobWithDrops(String id, MobDropConfig drops) {
        return new MobTemplate(
            1,
            id,
            MobCategory.ENEMY,
            id,
            null,
            1,
            EntityType.ZOMBIE,
            false,
            null,
            List.of(),
            List.of(),
            null,
            MobEquipmentConfig.EMPTY,
            List.of(),
            io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig.EMPTY,
            io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig.defaults(),
            false,
            io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig.EMPTY,
            null,
            null,
            drops
        );
    }
}
