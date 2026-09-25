package io.github.maaasu.astralRecord.feature.skill.active.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SkillMagicCircleRegistryTest {

    private static final UUID FIRST_CASTER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_CASTER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキル魔法陣の共有状態
     * 検証契約: 同じ発動者による複数登録は異なる個体IDを返し、登録した個体数だけを数える。
     */
    @Test
    void countsEachRegisteredCircleForOneCaster() {
        SkillMagicCircleRegistry registry = new SkillMagicCircleRegistry();

        UUID first = registry.register(FIRST_CASTER);
        UUID second = registry.register(FIRST_CASTER);

        assertNotEquals(first, second);
        assertEquals(2, registry.count(FIRST_CASTER));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキル魔法陣の共有状態
     * 検証契約: 一人の魔法陣を解除しても、別の発動者の個体数は変わらない。
     */
    @Test
    void isolatesCasterCounts() {
        SkillMagicCircleRegistry registry = new SkillMagicCircleRegistry();
        UUID firstCircle = registry.register(FIRST_CASTER);
        registry.register(SECOND_CASTER);

        registry.remove(firstCircle);

        assertEquals(0, registry.count(FIRST_CASTER));
        assertEquals(1, registry.count(SECOND_CASTER));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキル魔法陣の共有状態
     * 検証契約: 同じ魔法陣UUIDを複数回解除しても、他の魔法陣の個数を減らさない。
     */
    @Test
    void ignoresRepeatedRemoval() {
        SkillMagicCircleRegistry registry = new SkillMagicCircleRegistry();
        UUID first = registry.register(FIRST_CASTER);
        registry.register(FIRST_CASTER);

        registry.remove(first);
        registry.remove(first);

        assertEquals(1, registry.count(FIRST_CASTER));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキル魔法陣の共有状態
     * 検証契約: 全消去後は、それまで登録されていたすべての発動者の個体数が0になる。
     */
    @Test
    void clearsAllCasterCounts() {
        SkillMagicCircleRegistry registry = new SkillMagicCircleRegistry();
        registry.register(FIRST_CASTER);
        registry.register(SECOND_CASTER);

        registry.clear();

        assertEquals(0, registry.count(FIRST_CASTER));
        assertEquals(0, registry.count(SECOND_CASTER));
    }
}
