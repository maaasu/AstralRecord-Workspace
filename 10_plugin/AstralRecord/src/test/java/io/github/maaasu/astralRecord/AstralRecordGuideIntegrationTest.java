package io.github.maaasu.astralRecord;

import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeSkillEffect;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AstralRecordGuideIntegrationTest {

    private static final Path ASTRAL_RECORD_SOURCE = Path.of(
        "src",
        "main",
        "java",
        "io",
        "github",
        "maaasu",
        "astralRecord",
        "AstralRecord.java"
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: setupFeatureは、テスト済みの武器・転職・スキルツリーのガイド進捗配線を使用する。
     */
    @Test
    void setupFeatureUsesTestedGuideIntegrationWiring() throws IOException {
        String source = Files.readString(ASTRAL_RECORD_SOURCE, StandardCharsets.UTF_8);
        int setupFeatureStart = source.indexOf("private void setupFeature()");

        assertTrue(setupFeatureStart >= 0, "setupFeature must exist");
        assertTrue(
            source.indexOf("configureWeaponSkillGuideIntegration(", setupFeatureStart) >= 0,
            "setupFeature must call configureWeaponSkillGuideIntegration"
        );
        assertTrue(
            source.indexOf("configureClassChangeGuideIntegration(", setupFeatureStart) >= 0,
            "setupFeature must call configureClassChangeGuideIntegration"
        );
        assertTrue(
            source.indexOf("configureSkillTreeGuideIntegration(", setupFeatureStart) >= 0,
            "setupFeature must call configureSkillTreeGuideIntegration"
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: 装備中武器IDと成功スキルのタグを連結した対象を通知し、通常攻撃は通知しない。
     */
    @Test
    void weaponSkillGuideListenerRecordsTagsAndSkipsNormalAttacks() {
        SkillService skillService = mock(SkillService.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        InventoryService inventoryService = mock(InventoryService.class);
        GuideService guideService = mock(GuideService.class);
        AstPlayer player = mock(AstPlayer.class);
        SkillDefinition skillDefinition = mock(SkillDefinition.class);
        ItemModel weapon = mock(ItemModel.class);

        when(skillService.registry()).thenReturn(skillRegistry);
        when(skillRegistry.getDefinition("swordsman_skill")).thenReturn(skillDefinition);
        when(skillDefinition.getTags()).thenReturn(List.of("active", "melee", "melee", " "));
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(weapon.getId()).thenReturn("nox_sword");

        AstralRecord.configureWeaponSkillGuideIntegration(skillService, inventoryService, guideService);

        ArgumentCaptor<BiConsumer<AstPlayer, String>> listener = ArgumentCaptor.captor();
        verify(skillService).addPlayerCastSuccessListener(listener.capture());
        listener.getValue().accept(player, "swordsman_skill");

        ArgumentCaptor<String> targetId = ArgumentCaptor.forClass(String.class);
        verify(guideService, times(2)).recordCondition(
            eq(player),
            eq(GuideConditionType.WEAPON_SKILL_CAST),
            targetId.capture()
        );
        assertEquals(List.of("nox_sword:active", "nox_sword:melee"), targetId.getAllValues());

        clearInvocations(guideService);
        listener.getValue().accept(player, io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE);
        verifyNoInteractions(guideService);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: クラス変更後にパッシブ状態を再評価し、変更後クラスIDをガイドへ通知する。
     */
    @Test
    void classChangeGuideListenerRecordsChangedClass() {
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        GuideService guideService = mock(GuideService.class);
        AstPlayer player = mock(AstPlayer.class);
        when(player.getClassId()).thenReturn("mage");

        AstralRecord.configureClassChangeGuideIntegration(
            playerClassService,
            passiveSkillService,
            guideService
        );

        ArgumentCaptor<Consumer<AstPlayer>> listener = ArgumentCaptor.captor();
        verify(playerClassService).setClassChangeListener(listener.capture());
        listener.getValue().accept(player);

        verify(passiveSkillService).reconcileNow(player);
        verify(guideService).recordCondition(player, GuideConditionType.CLASS_CHANGED, "mage");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: ノード解放を共通・PP・CP・スキルノードの各条件へ正しく分類する。
     */
    @Test
    void skillTreeGuideListenerClassifiesPointCostAndSkillNodes() {
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        GuideService guideService = mock(GuideService.class);
        AstPlayer player = mock(AstPlayer.class);
        SkillTreeNodeDefinition freeNode = node("free", SkillTreePointType.PASSIVE_POINT, 0);
        SkillTreeNodeDefinition ppNode = node("pp", SkillTreePointType.PASSIVE_POINT, 1);
        SkillTreeNodeDefinition cpSkillNode = node(
            "cp_skill",
            SkillTreePointType.CLASS_POINT,
            1,
            new SkillTreeSkillEffect("mage_skill")
        );

        when(skillTreeService.getNode("free")).thenReturn(freeNode);
        when(skillTreeService.getNode("pp")).thenReturn(ppNode);
        when(skillTreeService.getNode("cp_skill")).thenReturn(cpSkillNode);

        AstralRecord.configureSkillTreeGuideIntegration(skillTreeService, guideService);

        ArgumentCaptor<BiConsumer<AstPlayer, String>> listener = ArgumentCaptor.captor();
        verify(skillTreeService).setNodeUnlockListener(listener.capture());

        listener.getValue().accept(player, "free");
        verify(guideService).recordCondition(player, GuideConditionType.SKILLTREE_NODE_UNLOCKED, "free");
        verify(guideService, never()).recordCondition(
            player,
            GuideConditionType.SKILLTREE_PP_NODE_UNLOCKED,
            "free"
        );
        verify(guideService, never()).recordCondition(
            player,
            GuideConditionType.SKILLTREE_CP_NODE_UNLOCKED,
            "free"
        );
        verify(guideService, never()).recordCondition(
            player,
            GuideConditionType.SKILLTREE_SKILL_NODE_UNLOCKED,
            "free"
        );

        clearInvocations(guideService);
        listener.getValue().accept(player, "pp");
        verify(guideService).recordCondition(player, GuideConditionType.SKILLTREE_NODE_UNLOCKED, "pp");
        verify(guideService).recordCondition(player, GuideConditionType.SKILLTREE_PP_NODE_UNLOCKED, "pp");
        verify(guideService, never()).recordCondition(
            player,
            GuideConditionType.SKILLTREE_CP_NODE_UNLOCKED,
            "pp"
        );
        verify(guideService, never()).recordCondition(
            player,
            GuideConditionType.SKILLTREE_SKILL_NODE_UNLOCKED,
            "pp"
        );

        clearInvocations(guideService);
        listener.getValue().accept(player, "cp_skill");
        verify(guideService).recordCondition(
            player,
            GuideConditionType.SKILLTREE_NODE_UNLOCKED,
            "cp_skill"
        );
        verify(guideService).recordCondition(
            player,
            GuideConditionType.SKILLTREE_CP_NODE_UNLOCKED,
            "cp_skill"
        );
        verify(guideService).recordCondition(
            player,
            GuideConditionType.SKILLTREE_SKILL_NODE_UNLOCKED,
            "cp_skill"
        );
    }

    private static SkillTreeNodeDefinition node(
        String id,
        SkillTreePointType pointType,
        int pointCost,
        SkillTreeNodeEffect... effects
    ) {
        return new SkillTreeNodeDefinition(
            id,
            id,
            Material.STONE,
            List.of(),
            List.of(),
            pointType,
            pointCost,
            List.of(effects)
        );
    }
}
