package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** 討伐報酬の TextDisplay に、本文の行数に合わせた統合版の枠を表示します。 */
record CombatRewardPlaqueEntity(GeyserIntEntityProperty lines, GeyserIntEntityProperty rare) {
    private static final long TAG = 0xa57ac2e570000002L;
    private static final int MIN_LINES = 5;
    private static final int MAX_LINES = 10;
    private static final int LINE_MASK = 0xf;
    private static final int RARE_FLAG = 0x10;
    private static final Identifier IDENTIFIER = Identifier.of("astralrecord", "combat_reward_plaque");
    static final CustomEntityDefinition DEFINITION = CustomEntityDefinition.of(IDENTIFIER);

    static CombatRewardPlaqueEntity registerProperties(GeyserDefineEntityPropertiesEvent event) {
        return new CombatRewardPlaqueEntity(
            event.registerIntegerProperty(IDENTIFIER, Identifier.of("astralrecord", "reward_lines"),
                MIN_LINES, MAX_LINES, MIN_LINES),
            event.registerIntegerProperty(IDENTIFIER, Identifier.of("astralrecord", "reward_rare"), 0, 1, 0)
        );
    }

    static Layout decode(UUID uuid, int entityId) {
        if (uuid == null || uuid.getMostSignificantBits() != TAG) return null;
        long payload = uuid.getLeastSignificantBits();
        long flags = payload >>> 32;
        int lines = (int) flags & LINE_MASK;
        if ((int) payload != entityId || flags > (LINE_MASK | RARE_FLAG) || lines < MIN_LINES || lines > MAX_LINES) {
            return null;
        }
        return new Layout(lines, (flags & RARE_FLAG) != 0);
    }

    void initialize(GeyserEntity entity, Layout layout) {
        // 元の TextDisplay の名前欄と、Geyser が計算する行数別の位置を利用します。
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        entity.override(GeyserEntityDataTypes.WIDTH, 0f);
        entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
        entity.updatePropertiesBatched(batch -> {
            batch.update(lines, layout.lines());
            batch.update(rare, layout.rare() ? 1 : 0);
        }, false);
    }

    record Layout(int lines, boolean rare) {}
}
