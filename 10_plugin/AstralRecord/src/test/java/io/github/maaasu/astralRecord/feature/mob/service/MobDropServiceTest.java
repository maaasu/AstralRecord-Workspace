package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.EntityType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobDropServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 4. MobDropService メソッド仕様 > ### ドロップ確定
     * 検証契約: 直接drop/loot結果へ設定rateを保持し表示・rare判定へ渡す。
     */
    @Test
    void rollPreservesConfiguredRateForResultPresentation() {
        MobDropConfig drops = new MobDropConfig(
            10,
            null,
            List.of(new MobDropItem("rare_item", 100.0D, "2", false, false)),
            null
        );

        MobDropResult result = new MobDropService().roll(drops, null);

        assertEquals(1, result.items().size());
        assertEquals("rare_item", result.items().getFirst().itemId());
        assertEquals(2, result.items().getFirst().amount());
        assertEquals(100.0D, result.items().getFirst().dropRate());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 4. MobDropService メソッド仕様 > ### ドロップ確定
     * 検証契約: amountのmin~max記法を閉区間として抽選する。
     */
    @Test
    void rollAcceptsDocumentedTildeAmountRange() {
        MobDropConfig drops = new MobDropConfig(
            0,
            null,
            List.of(new MobDropItem("boss_material", 100.0D, "2~4", false, false)),
            null
        );

        MobDropResult result = new MobDropService().roll(drops, null);

        assertEquals(1, result.items().size());
        assertTrue(result.items().getFirst().amount() >= 2);
        assertTrue(result.items().getFirst().amount() <= 4);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 4. MobDropService メソッド仕様 > ### ドロップ確定
     * 検証契約: load済みloot table当選結果を直接drop結果へ結合する。
     */
    @Test
    void rollAddsLoadedLootTableRewards() {
        LootService lootService = mock(LootService.class);
        when(lootService.getLoaded("field_table")).thenReturn(new LootModel(
            1,
            "field_table",
            "field_table",
            1,
            List.of(new LootPoolModel(
                "field_pool",
                1,
                List.of(new LootContent("table_reward", 2, 2, 100.0D))
            ))
        ));
        MobDropConfig drops = new MobDropConfig(0, null, List.of(), "field_table");

        MobDropResult result = new MobDropService(lootService).roll(drops, null);

        assertEquals(1, result.items().size());
        assertEquals("table_reward", result.items().getFirst().itemId());
        assertEquals(2, result.items().getFirst().amount());
        assertEquals(100.0D, result.items().getFirst().dropRate());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 4. MobDropService メソッド仕様 > ### ドロップ確定
     * 検証契約: LUCK補正をluckAffected=trueの直接dropだけへ適用する。
     */
    @Test
    void rollAppliesLuckOnlyToAffectedDirectDrops() {
        AstPlayer killer = mock(AstPlayer.class);
        when(killer.getStatusSnapshot()).thenReturn(new StatusSnapshot(
            Map.of(StatusType.LUCK, new StatusValue(0.0D, 2000.0D)),
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            0L,
            LocalDateTime.now()
        ));
        MobDropConfig drops = new MobDropConfig(
            0,
            null,
            List.of(
                new MobDropItem("affected", 0.0D, "1", true, false),
                new MobDropItem("unaffected", 0.0D, "1", false, false)
            ),
            null
        );

        MobDropResult result = new MobDropService().roll(drops, killer);

        assertEquals(1, result.items().size());
        assertEquals("affected", result.items().getFirst().itemId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 4. MobDropService メソッド仕様 > ### ドロップ確定
     * 検証契約: EXPをplayer/Mob絶対level差1ごと5%減、最低10%へ補正する。
     */
    @Test
    void rollTemplateReducesExperienceByPlayerAndMobLevelDifference() {
        LocalDateTime now = LocalDateTime.now();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID systemId = UUID.randomUUID();
        AccountModel account = new AccountModel(
            accountId,
            userId,
            "test-account",
            0,
            true,
            AccountMode.PLAYER,
            "{}",
            now,
            now,
            systemId,
            systemId,
            false,
            20
        );
        AstPlayer killer = mock(AstPlayer.class);
        when(killer.getAccount()).thenReturn(account);

        MobDropConfig drops = new MobDropConfig(100, null, List.of(), null);
        MobTemplate template = new MobTemplate(
            1,
            "test_mob",
            MobCategory.ENEMY,
            "Test Mob",
            null,
            10,
            EntityType.ZOMBIE,
            false,
            "ZOMBIE_HEAD",
            List.of(),
            List.of(),
            null,
            MobEquipmentConfig.EMPTY,
            List.of(),
            MobShieldConfig.EMPTY,
            MobIdleConfig.defaults(),
            false,
            MobInteractionsConfig.EMPTY,
            null,
            null,
            drops
        );

        MobDropResult result = new MobDropService().roll(template, killer);

        assertEquals(50, result.exp());
    }
}
