package io.github.maaasu.astralRecord.feature.quest.repository;

import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QuestDefinitionRepositoryTest {

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
