package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SkillCooldownBossBarServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 5. cooldown・cast lifecycle
     * 検証契約: 統一cooldown boss barは灰色相当のWHITEと10分割styleで生成する。
     */
    @Test
    void createsUnifiedCooldownBarWithWhiteSegmentedStyle() {
        SkillCooldownBossBarService service = new SkillCooldownBossBarService(mock(SkillService.class));
        Player player = server().addPlayer();

        BossBar bossBar = service.createBossBar(player);

        assertEquals(BarColor.WHITE, bossBar.getColor());
        assertEquals(BarStyle.SEGMENTED_10, bossBar.getStyle());
        bossBar.removeAll();
    }
}
