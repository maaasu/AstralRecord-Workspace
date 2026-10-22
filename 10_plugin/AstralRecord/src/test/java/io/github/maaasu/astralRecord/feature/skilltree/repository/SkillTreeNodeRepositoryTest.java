package io.github.maaasu.astralRecord.feature.skilltree.repository;

import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeSkillEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeStatusEffect;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.api.RuntimeFilebaseSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTreeNodeRepositoryTest {
    private final List<RuntimeFilebaseSnapshot.Entry> entries = new ArrayList<>();

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 6. skill tree node 定義読込
     * 検証契約: schema v1 JSONのskill/status typed effectを対応modelへ変換する。
     */
    @Test
    void loadsSchemaV1TypedEffects() {
        writeNode("1000.json", nodeJson(
                "1000",
                """
                        [
                          {"type": "skill", "skillId": "passive-test"},
                          {"type": "status", "status": "ATTACK", "modifierType": "FLAT", "value": 5}
                        ]
                        """
        ));

        var nodes = repository().findAll();

        assertEquals(1, nodes.size());
        assertEquals("1000", nodes.getFirst().nodeId());
        assertEquals(Material.NETHER_STAR, nodes.getFirst().icon());
        SkillTreeSkillEffect skill = assertInstanceOf(
                SkillTreeSkillEffect.class,
                nodes.getFirst().effects().getFirst()
        );
        assertEquals("passive-test", skill.skillId());
        SkillTreeStatusEffect status = assertInstanceOf(
                SkillTreeStatusEffect.class,
                nodes.getFirst().effects().get(1)
        );
        assertEquals(StatusType.ATTACK, status.statusType());
        assertEquals(StatusModifierType.FLAT, status.modifierType());
        assertEquals(5.0D, status.value());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 6. skill tree node 定義読込
     * 検証契約: 削除されたloreを未知のnode propertyとして拒否する。
     */
    @Test
    void rejectsRemovedLore() {
        writeNode("1000.json", nodeJson("1000", "[]").replace(
                "  \"tags\": [\"root\"],",
                "  \"lore\": [],\n  \"tags\": [\"root\"],"
        ));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> repository().findAll());

        assertTrue(error.getMessage().contains("lore"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 6. skill tree node 定義読込
     * 検証契約: 複数file間で重複するnode IDを全体load失敗にする。
     */
    @Test
    void rejectsDuplicateNodeIdsAcrossFiles() {
        writeNode("first.json", nodeJson("1000", "[]"));
        writeNode("second.json", nodeJson("1000", "[]"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> repository().findAll()
        );

        assertTrue(error.getMessage().contains("duplicate nodeId"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 6. skill tree node 定義読込
     * 検証契約: 同じclass条件で同じskill使用許可を複数nodeへ定義できない。
     */
    @Test
    void rejectsDuplicateSkillPermissionsWithSameClassCondition() {
        String skillEffect = "[{\"type\": \"skill\", \"skillId\": \"adventurer_astral_edge\"}]";
        writeNode("1000.json", nodeJson("1000", skillEffect));
        writeNode("1001.json", nodeJson("1001", skillEffect));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> repository().findAll()
        );

        assertTrue(error.getMessage().contains("duplicate skill permission 'adventurer_astral_edge'"));
        assertTrue(error.getMessage().contains("nodes '1000' and '1001'"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 6. skill tree node 定義読込
     * 検証契約: class条件が異なるnodeには同じskill使用許可を定義できる。
     */
    @Test
    void allowsSameSkillPermissionForDifferentClassConditions() {
        String skillEffect = "[{\"type\": \"skill\", \"skillId\": \"adventurer_astral_edge\"}]";
        writeNode("1000.json", nodeJson("1000", skillEffect).replace(
                "\"effects\":",
                "\"unlockCondition\": {\"classId\": \"hunter\"},\n  \"effects\":"
        ));
        writeNode("1001.json", nodeJson("1001", skillEffect).replace(
                "\"effects\":",
                "\"unlockCondition\": {\"classId\": \"mage\"},\n  \"effects\":"
        ));

        var nodes = repository().findAll();

        assertEquals(2, nodes.size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 6. skill tree node 定義読込
     * 検証契約: schema外legacy propertyと先頭0付きnode IDを拒否する。
     */
    @Test
    void rejectsLegacyPropertiesAndLeadingZeroIds() {
        writeNode("legacy.json", nodeJson("01000", "[]").replace(
                "\"name\": \"Root\"",
                "\"positionId\": \"root\",\n  \"name\": \"Root\""
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> repository().findAll()
        );

        assertTrue(error.getMessage().contains("positionId"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 6. skill tree node 定義読込
     * 検証契約: 0単独を除く先頭0付き数字node IDを拒否する。
     */
    @Test
    void rejectsNodeIdWithLeadingZero() {
        writeNode("invalid-id.json", nodeJson("01000", "[]"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> repository().findAll()
        );

        assertTrue(error.getMessage().contains("non-zero-leading"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 6. skill tree node 定義読込
     * 検証契約: 任意current class/player level unlock conditionをmodelへ変換する。
     */
    @Test
    void loadsOptionalCurrentClassAndPlayerLevelCondition() {
        writeNode("1000.json", nodeJson("1000", "[]").replace(
                "\"effects\": []",
                "\"unlockCondition\": {\"classId\": \"hunter\", \"playerLevel\": 25},\n  \"effects\": []"
        ));

        var node = repository().findAll().getFirst();

        assertEquals("hunter", node.unlockCondition().classId());
        assertEquals(25, node.unlockCondition().playerLevel());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_1-モデル定義.md
     * 章・見出し: # 13_1-モデル定義 > ## 10. スキルツリー > ### node definition
     * 検証契約: 現schemaにないclass level conditionを未知propertyとして拒否する。
     */
    @Test
    void rejectsClassLevelAsNodeUnlockCondition() {
        writeNode("1000.json", nodeJson("1000", "[]").replace(
                "\"effects\": []",
                "\"unlockCondition\": {\"classId\": \"hunter\", \"classLevel\": 25},\n  \"effects\": []"
        ));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> repository().findAll());

        assertTrue(error.getMessage().contains("classLevel"));
    }

    private void writeNode(String fileName, String json) {
        entries.add(new RuntimeFilebaseSnapshot.Entry("35.features.skilltree/nodes/" + fileName, json));
    }

    private SkillTreeNodeRepository repository() {
        return new SkillTreeNodeRepository(
                () -> new RuntimeFilebaseSnapshot(entries),
                material -> material != Material.AIR
        );
    }

    private String nodeJson(String nodeId, String effects) {
        return """
                {
                  "$schema": "../schemas/node.v1.schema.json",
                  "schemaVersion": 1,
                  "nodeId": "%s",
                  "name": "Root",
                  "icon": "NETHER_STAR",
                  "tags": ["root"],
                  "pointType": "PP",
                  "pointCost": 0,
                  "effects": %s
                }
                """.formatted(nodeId, effects);
    }
}
