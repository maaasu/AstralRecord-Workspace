package io.github.maaasu.astralRecord.feature.player.model;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstPlayerClassProgressTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 2. プレイヤーセッション > ### 2.2 主要状態
     * 検証契約: クラスごとのlevel/experienceを独立保持し、転職後も各進行値を復元する。
     */
    @Test
    void keepsLevelAndExperienceIndependentlyForEveryClass() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setClassLevel(10);
        player.setClassExperience(4_000L);

        player.selectClass("mage");
        assertEquals(1, player.getClassLevel());
        assertEquals(0L, player.getClassExperience());
        player.setClassLevel(4);
        player.setClassExperience(900L);

        player.selectClass("adventurer");
        assertEquals(10, player.getClassLevel());
        assertEquals(4_000L, player.getClassExperience());
        assertEquals(4, player.getClassProgress("mage").getLevel());
        assertEquals(900L, player.getClassProgress("mage").getExperience());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 2. プレイヤーセッション > ### 2.3 Bedrock 判定
     * 検証契約: AstPlayer生成時にuser.mcidへドットを含むセッションだけisBedrockをtrueで保持する。
     */
    @Test
    void storesBedrockFlagFromUserMcid() {
        AstPlayer bedrockPlayer = DesignTestFixtures.astPlayer(
            server().addPlayer(".BedrockUser"),
            AccountMode.PLAYER
        );
        AstPlayer javaPlayer = DesignTestFixtures.astPlayer(
            server().addPlayer("JavaUser"),
            AccountMode.PLAYER
        );

        assertTrue(bedrockPlayer.isBedrock());
        assertFalse(javaPlayer.isBedrock());
    }
}
