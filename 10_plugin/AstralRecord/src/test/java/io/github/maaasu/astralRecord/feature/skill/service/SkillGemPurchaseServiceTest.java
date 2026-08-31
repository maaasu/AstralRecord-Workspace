package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSkillGem;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.model.ShopSpecialPurchaseState;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillGemPurchaseServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルジェム購入反映
     * 検証契約: 未習得は習得、習得済みは次レベル、最大レベルは購入不可へ分類する。
     */
    @Test
    void previewClassifiesLearnLevelUpAndMaxLevel() {
        Harness harness = harness();
        UUID accountId = harness.player.getAccount().getUuid();
        when(harness.learnedSkillService.hasLoadedSkills(accountId)).thenReturn(true);
        when(harness.learnedSkillService.getLearnedSkills(accountId)).thenReturn(List.of());

        ShopSpecialPurchaseState learn = harness.service.preview(harness.player, harness.gem);
        assertEquals(ShopSpecialPurchaseState.Action.SKILL_LEARN, learn.action());
        assertTrue(learn.canPurchase());

        LearnedSkillInstance levelOne = learned(accountId, 1);
        when(harness.learnedSkillService.getLearnedSkills(accountId)).thenReturn(List.of(levelOne));
        ShopSpecialPurchaseState levelUp = harness.service.preview(harness.player, harness.gem);
        assertEquals(ShopSpecialPurchaseState.Action.SKILL_LEVEL_UP, levelUp.action());
        assertEquals(1, levelUp.currentLevel());
        assertEquals(2, levelUp.nextLevel());

        when(harness.learnedSkillService.getLearnedSkills(accountId)).thenReturn(List.of(learned(accountId, 5)));
        ShopSpecialPurchaseState max = harness.service.preview(harness.player, harness.gem);
        assertEquals(ShopSpecialPurchaseState.Action.SKILL_MAX_LEVEL, max.action());
        assertFalse(max.canPurchase());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## スキルジェム購入効果
     * 検証契約: 初回購入は購入entry UUIDを指定して購入専用learn mutationを呼び、完了後に購入予約を解除する。
     */
    @Test
    void firstPurchaseLearnsWithPurchasedEntry() {
        Harness harness = harness();
        UUID accountId = harness.player.getAccount().getUuid();
        UUID purchasedEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1);
        AtomicBoolean stateChanged = new AtomicBoolean();
        AtomicReference<String> learnedGuideSkill = new AtomicReference<>();
        harness.service.setSkillLearnedListener((ignored, skillId) -> learnedGuideSkill.set(skillId));
        when(harness.learnedSkillService.hasLoadedSkills(accountId)).thenReturn(true);
        when(harness.learnedSkillService.getLearnedSkills(accountId)).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<Consumer<LearnedSkillInstance>>getArgument(5).accept(learned);
            return true;
        }).when(harness.learnedSkillService).learnFromPurchaseAsync(
            eq(accountId), eq(SKILL_ID), eq(purchasedEntryId), eq(accountId), any(), any(), any()
        );

        assertTrue(harness.service.reserve(harness.player, harness.gem));
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(harness.player.getBukkit())).thenReturn(null);
            harness.service.completePurchase(
                harness.player,
                harness.gem,
                purchasedEntryId,
                () -> true,
                () -> { },
                () -> stateChanged.set(true)
            );
        }

        verify(harness.learnedSkillService).learnFromPurchaseAsync(
            eq(accountId), eq(SKILL_ID), eq(purchasedEntryId), eq(accountId), any(), any(), any()
        );
        assertTrue(stateChanged.get());
        assertEquals(SKILL_ID, learnedGuideSkill.get());
        assertEquals(
            ShopSpecialPurchaseState.Action.SKILL_LEARN,
            harness.service.preview(harness.player, harness.gem).action()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## スキルジェム購入効果
     * 検証契約: 再購入は既存個体UUID・次レベル・購入entry UUIDを指定して購入専用level-up mutationを呼ぶ。
     */
    @Test
    void repeatedPurchaseLevelsUpCanonicalInstanceWithPurchasedEntry() {
        Harness harness = harness();
        UUID accountId = harness.player.getAccount().getUuid();
        UUID purchasedEntryId = UUID.randomUUID();
        LearnedSkillInstance current = learned(accountId, 2);
        LearnedSkillInstance leveled = new LearnedSkillInstance(
            current.getLearnedSkillId(), accountId, SKILL_ID, 3, List.of(), 1, null, null
        );
        AtomicBoolean stateChanged = new AtomicBoolean();
        AtomicReference<String> enhancedGuideSkill = new AtomicReference<>();
        harness.service.setSkillEnhancedListener((ignored, skillId) -> enhancedGuideSkill.set(skillId));
        when(harness.learnedSkillService.hasLoadedSkills(accountId)).thenReturn(true);
        when(harness.learnedSkillService.getLearnedSkills(accountId)).thenReturn(List.of(current));
        doAnswer(invocation -> {
            invocation.<Consumer<LearnedSkillInstance>>getArgument(6).accept(leveled);
            return true;
        }).when(harness.learnedSkillService).levelUpFromPurchaseAsync(
            eq(accountId),
            eq(current.getLearnedSkillId()),
            eq(3),
            eq(purchasedEntryId),
            eq(accountId),
            any(),
            any(),
            any()
        );

        assertTrue(harness.service.reserve(harness.player, harness.gem));
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(harness.player.getBukkit())).thenReturn(null);
            harness.service.completePurchase(
                harness.player,
                harness.gem,
                purchasedEntryId,
                () -> true,
                () -> { },
                () -> stateChanged.set(true)
            );
        }

        verify(harness.learnedSkillService).levelUpFromPurchaseAsync(
            eq(accountId),
            eq(current.getLearnedSkillId()),
            eq(3),
            eq(purchasedEntryId),
            eq(accountId),
            any(),
            any(),
            any()
        );
        assertTrue(stateChanged.get());
        assertEquals(SKILL_ID, enhancedGuideSkill.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_5-例外・ログ・運用.md
     * 章・見出し: # 20_5-例外・ログ・運用 > ## 購入後スキル mutation
     * 検証契約: 購入mutationが致命的失敗callbackへ遷移してもpending予約を解除し、次回購入を処理中扱いにしない。
     */
    @Test
    void fatalPurchaseFailureReleasesPendingReservation() {
        Harness harness = harness();
        UUID accountId = harness.player.getAccount().getUuid();
        UUID purchasedEntryId = UUID.randomUUID();
        when(harness.learnedSkillService.hasLoadedSkills(accountId)).thenReturn(true);
        when(harness.learnedSkillService.getLearnedSkills(accountId)).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<Consumer<Throwable>>getArgument(6)
                .accept(new IllegalStateException("terminal unknown"));
            return true;
        }).when(harness.learnedSkillService).learnFromPurchaseAsync(
            eq(accountId), eq(SKILL_ID), eq(purchasedEntryId), eq(accountId), any(), any(), any()
        );

        assertTrue(harness.service.reserve(harness.player, harness.gem));
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(harness.player.getBukkit())).thenReturn(null);
            harness.service.completePurchase(
                harness.player,
                harness.gem,
                purchasedEntryId,
                () -> false,
                () -> { },
                () -> { }
            );
        }

        assertEquals(
            ShopSpecialPurchaseState.Action.SKILL_LEARN,
            harness.service.preview(harness.player, harness.gem).action()
        );
    }

    private Harness harness() {
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        SkillService skillService = mock(SkillService.class);
        SkillRegistry registry = mock(SkillRegistry.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillDefinition definition = mock(SkillDefinition.class);
        ItemModel gem = mock(ItemModel.class);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        when(skillService.registry()).thenReturn(registry);
        when(registry.getDefinition(SKILL_ID)).thenReturn(definition);
        when(definition.getMaxLevel()).thenReturn(5);
        when(gem.getId()).thenReturn("00_skill_gem_" + SKILL_ID);
        when(gem.getSkillGem()).thenReturn(new ItemSkillGem(SKILL_ID));
        return new Harness(
            new SkillGemPurchaseService(
                learnedSkillService, skillService, inventoryService, passiveSkillService
            ),
            learnedSkillService,
            player,
            gem
        );
    }

    private LearnedSkillInstance learned(UUID accountId, int level) {
        return new LearnedSkillInstance(
            UUID.randomUUID(), accountId, SKILL_ID, level, List.of(), 0, null, null
        );
    }

    private static final String SKILL_ID = "adventurer_smash";

    private record Harness(
        SkillGemPurchaseService service,
        LearnedSkillService learnedSkillService,
        AstPlayer player,
        ItemModel gem
    ) {
    }
}
