package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.skill.executor.SwordsmanShieldActivateSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PassiveSkillServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## パッシブスロット
     * 検証契約: 基本5枠へPASSIVE_SKILL_SLOTSを1値1枠で加え、負数を無視し最大9枠に制限する。
     */
    @Test
    void activePassiveSlotCountUsesBaseFiveAndCapsStatusBonusAtNine() {
        PassiveSkillService service = new PassiveSkillService(
            mock(AstralRecord.class),
            mock(SkillService.class),
            mock(SkillBindPresetService.class),
            mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class),
            mock(LearnedSkillResolver.class)
        );
        AstPlayer player = mock(AstPlayer.class);
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        when(player.getStatusSnapshot()).thenReturn(snapshot);
        when(snapshot.getMaxValue(StatusType.PASSIVE_SKILL_SLOTS)).thenReturn(-1.0D, 2.9D, 99.0D);

        assertEquals(5, service.activePassiveSlotCount(player));
        assertEquals(7, service.activePassiveSlotCount(player));
        assertEquals(9, service.activePassiveSlotCount(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6. passive skill 同期
     * 検証契約: bind-required パッシブは、習得個体・使用許可・有効パッシブ枠のバインドがすべて揃った場合だけ有効になり、いずれかが外れると無効になる。
     */
    @Test
    void passiveActivationRequiresPermissionAndBoundInstance() {
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getStatusSnapshot()).thenReturn(snapshot);
        when(snapshot.getMaxValue(StatusType.PASSIVE_SKILL_SLOTS)).thenReturn(5.0D);

        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId,
            accountId,
            SwordsmanShieldActivateSkillExecutor.ID,
            1,
            List.of(),
            0,
            null,
            null
        );
        SkillDefinition definition = new SkillDefinition(
            SwordsmanShieldActivateSkillExecutor.ID,
            SwordsmanShieldActivateSkillExecutor.ID,
            "タンクシールドアクティベート",
            null,
            "SHIELD",
            List.of(),
            0L,
            0.0D,
            0L,
            1,
            null,
            Map.of(),
            List.of("passive", "defense"),
            SkillKind.PASSIVE,
            true,
            SkillResourceType.MANA,
            0.0D
        );
        SkillRegistry registry = new SkillRegistry();
        registry.registerExecutor(new SwordsmanShieldActivateSkillExecutor());
        registry.replaceDefinitions(Map.of(definition.getId(), definition));

        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.learnedSkills(player)).thenReturn(List.of(learned));
        when(presetService.selectedPresetIndex(accountId)).thenReturn(0);
        when(permissionService.isPermitted(player, definition.getId())).thenReturn(true);
        when(presetService.getPresets(accountId)).thenReturn(List.of(
            new SkillBindPreset(
                null,
                accountId,
                0,
                List.of(),
                null,
                List.of(learnedSkillId.toString()),
                true,
                true,
                1
            )
        ));

        PassiveSkillService service = new PassiveSkillService(
            mock(AstralRecord.class),
            skillService,
            presetService,
            ownershipService,
            permissionService,
            new LearnedSkillResolver(mock(ItemService.class))
        );

        assertTrue(service.isPassiveSkillActive(player, SwordsmanShieldActivateSkillExecutor.ID));

        when(presetService.getPresets(accountId)).thenReturn(List.of(
            new SkillBindPreset(null, accountId, 0, List.of(), null, List.of(), true, true, 1)
        ));
        service.markDirty(player);
        assertFalse(service.isPassiveSkillActive(player, SwordsmanShieldActivateSkillExecutor.ID));

        when(presetService.getPresets(accountId)).thenReturn(List.of(
            new SkillBindPreset(
                null,
                accountId,
                0,
                List.of(),
                null,
                List.of(learnedSkillId.toString()),
                true,
                true,
                1
            )
        ));
        when(permissionService.isPermitted(player, definition.getId())).thenReturn(false);
        service.markDirty(player);
        assertFalse(service.isPassiveSkillActive(player, SwordsmanShieldActivateSkillExecutor.ID));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## タンクシールドアクティベートの実装契約
     * 検証契約: bindRequired=false のタンクシールドアクティベートは、使用許可と習得済み個体があればパッシブスロット未設定でも有効になる。
     */
    @Test
    void nonBindRequiredPassiveActivatesWithoutBoundInstance() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(),
            accountId,
            SwordsmanShieldActivateSkillExecutor.ID,
            1,
            List.of(),
            0,
            null,
            null
        );
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);

        SkillDefinition definition = new SkillDefinition(
            SwordsmanShieldActivateSkillExecutor.ID,
            SwordsmanShieldActivateSkillExecutor.ID,
            "タンクシールドアクティベート",
            null,
            "SHIELD",
            List.of(),
            0L,
            0.0D,
            0L,
            1,
            null,
            Map.of(),
            List.of("passive", "defense"),
            SkillKind.PASSIVE,
            false,
            SkillResourceType.MANA,
            0.0D
        );
        SkillRegistry registry = new SkillRegistry();
        registry.registerExecutor(new SwordsmanShieldActivateSkillExecutor());
        registry.replaceDefinitions(Map.of(definition.getId(), definition));

        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.learnedSkills(player)).thenReturn(List.of(learned));
        when(permissionService.isPermitted(player, definition.getId())).thenReturn(true);
        when(presetService.selectedPresetIndex(accountId)).thenReturn(0);
        when(presetService.getPresets(accountId)).thenReturn(List.of());

        PassiveSkillService service = new PassiveSkillService(
            mock(AstralRecord.class),
            skillService,
            presetService,
            ownershipService,
            permissionService,
            new LearnedSkillResolver(mock(ItemService.class))
        );

        assertTrue(service.isPassiveSkillActive(player, SwordsmanShieldActivateSkillExecutor.ID));
    }
}
