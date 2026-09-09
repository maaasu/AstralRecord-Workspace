package io.github.maaasu.astralRecord.feature.skilltree.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkillTreeTabCompleterTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 10. スキルツリーノードの強調・絞り込み・簡易表示
     * 検証契約: node強調の第3引数はtrue/falseを、status絞り込みはコロン接続できる未選択status IDを補完する。
     */
    @Test
    void nodeDisplayArgumentsProvideBooleanAndColonSeparatedStatusCompletions() {
        SkillTreeTabCompleter completer = new SkillTreeTabCompleter();
        AstPlayer player = mock(AstPlayer.class);

        List<String> highlight = completer.getPlayerCompletions(
                player,
                new String[]{"node", "highlight", ""}
        );
        List<String> filter = completer.getPlayerCompletions(
                player,
                new String[]{"node", "filter", "attack:"}
        );

        assertTrue(highlight.contains("true"));
        assertTrue(highlight.contains("false"));
        assertTrue(filter.contains("attack:defense"));
        assertTrue(filter.stream().noneMatch("attack:attack"::equals));
    }
}
