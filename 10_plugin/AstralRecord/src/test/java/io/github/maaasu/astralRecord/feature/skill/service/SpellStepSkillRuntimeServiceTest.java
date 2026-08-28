package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class SpellStepSkillRuntimeServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.3 スペルステップ
     * 検証契約: 有効なスペルステップはrangedタグ付きスキル成功後20tick以内の次回ドッジだけを無料化し、権利を一度だけ消費する。
     */
    @Test
    void rangedSkillArmsOneFreeDodgeWithinTwentyTicks() {
        SpellStepSkillRuntimeService runtime = new SpellStepSkillRuntimeService();
        PlayerMock bukkitPlayer = spy(server().addPlayer());
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PassiveSkillContext context = new PassiveSkillContext(
                player,
                definition(Map.of(
                        "windowTicks", 20,
                        "triggerSound", "block.beacon.power_select"
                ), List.of("passive", "windwait")),
                Instant.EPOCH,
                0L
        );
        runtime.activate(context);

        runtime.onSkillCast(player, definition(Map.of(), List.of("active", "ranged")));

        assertTrue(runtime.consumeFreeDodge(player));
        assertFalse(runtime.consumeFreeDodge(player));
        verify(bukkitPlayer).playSound(
                any(Location.class),
                eq("block.beacon.power_select"),
                eq(SoundCategory.PLAYERS),
                eq(0.8F),
                eq(1.3F)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.3 スペルステップ
     * 検証契約: 非遠隔スキルは待機権を作らず、遠隔スキルの再成功は権利を重ねず期限だけを更新し、期限切れ・ライフサイクル破棄後は無料化しない。
     */
    @Test
    void nonRangedAndExpiredStatesAreNotFreeAndLifecycleClearsState() {
        SpellStepSkillRuntimeService runtime = new SpellStepSkillRuntimeService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PassiveSkillContext context = new PassiveSkillContext(
                player,
                definition(Map.of(
                        "windowTicks", 20,
                        "triggerSound", "block.beacon.power_select"
                ), List.of("passive", "windwait")),
                Instant.EPOCH,
                0L
        );
        runtime.activate(context);

        runtime.onSkillCast(player, definition(Map.of(), List.of("active", "melee")));
        assertFalse(runtime.consumeFreeDodge(player));

        runtime.onSkillCast(player, definition(Map.of(), List.of("active", "ranged")));
        server().getScheduler().performTicks(15);
        runtime.onSkillCast(player, definition(Map.of(), List.of("active", "ranged")));
        server().getScheduler().performTicks(19);
        assertTrue(runtime.consumeFreeDodge(player));

        runtime.onSkillCast(player, definition(Map.of(), List.of("active", "ranged")));
        server().getScheduler().performTicks(20);
        assertFalse(runtime.consumeFreeDodge(player));

        runtime.onSkillCast(player, definition(Map.of(), List.of("active", "ranged")));
        runtime.clearArmedState(player.getBukkit().getUniqueId());
        assertFalse(runtime.consumeFreeDodge(player));

        runtime.onSkillCast(player, definition(Map.of(), List.of("active", "ranged")));
        runtime.deactivate(context);
        assertFalse(runtime.consumeFreeDodge(player));
    }

    private static SkillDefinition definition(Map<String, Object> params, List<String> tags) {
        return new SkillDefinition(
                "test_skill",
                "test_skill",
                "テストスキル",
                "テスト",
                "FEATHER",
                List.of(),
                0L,
                0.0D,
                0L,
                1,
                null,
                params,
                tags,
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                0.0D
        );
    }
}
