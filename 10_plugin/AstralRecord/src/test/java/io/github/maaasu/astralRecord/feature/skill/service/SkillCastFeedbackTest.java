package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillCastFeedbackTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 4. skill 発動
     * 検証契約: 詠唱ActionBarに残り秒とlime難読化済み/gray未完了の太いprogress barを表示する。
     */
    @Test
    void actionBarShowsRemainingTimeAndColoredObfuscatedProgress() {
        SkillCastFeedback feedback = new SkillCastFeedback();

        var component = feedback.createActionBar(skill(), 40L, 20L);
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        String legacy = LegacyComponentSerializer.legacySection().serialize(component);

        assertTrue(plain.contains("詠唱中: Arc Lance 1.0秒"));
        assertEquals(24, plain.chars().filter(character -> character == '█').count());
        assertTrue(legacy.contains("§a"));
        assertTrue(legacy.contains("§l"));
        assertTrue(legacy.contains("§k"));
        assertTrue(legacy.contains("§7"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 4. skill 発動
     * 検証契約: 詠唱soundを0.5秒間隔で鳴らし進捗に応じてpitchを上げる。
     */
    @Test
    void castSoundRunsAtHalfSecondIntervalsWithProgressPitch() {
        SkillCastFeedback feedback = new SkillCastFeedback();
        Player player = mock(Player.class);
        Location location = new Location(null, 1.0D, 2.0D, 3.0D);
        when(player.getLocation()).thenReturn(location);

        assertTrue(feedback.shouldPlaySound(0L));
        assertFalse(feedback.shouldPlaySound(9L));
        assertTrue(feedback.shouldPlaySound(10L));

        feedback.playSound(player, 20L, 40L);

        verify(player).playSound(
                location,
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS,
                0.35F,
                1.2F
        );
    }

    private SkillDefinition skill() {
        return new SkillDefinition(
                "arc_lance",
                "arc_lance",
                "Arc Lance",
                null,
                null,
                List.of(),
                0L,
                0.0D,
                40L,
                1,
                null,
                Map.of(),
                List.of(),
                SkillKind.ACTIVE,
                true,
                null,
                null
        );
    }
}
