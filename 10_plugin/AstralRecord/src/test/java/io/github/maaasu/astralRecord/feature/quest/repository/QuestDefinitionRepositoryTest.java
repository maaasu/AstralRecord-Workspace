package io.github.maaasu.astralRecord.feature.quest.repository;

import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QuestDefinitionRepositoryTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 1. クエスト定義読込
     * 検証契約: nested ref形式のturnInNpcIdとobjective targetIdを読み、mob: prefixを除去する。
     */
    @Test
    void parseResolvesNestedTurnInNpcReference() throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            id: windwait_outer_patrol
            completion:
              mode: NPC
              turnInNpcId:
                ref: mob:village_elder
            objectives:
              - id: defeat_guard
                type: KILL_MOB
                targetId:
                  ref: mob:skyway_guard
                amount: 1
            """);

        QuestDefinition definition = new QuestDefinitionRepository().parse(yaml);

        assertNotNull(definition);
        assertEquals("village_elder", definition.turnInNpcId());
        assertEquals("skyway_guard", definition.objectives().getFirst().targetId());
    }
}
