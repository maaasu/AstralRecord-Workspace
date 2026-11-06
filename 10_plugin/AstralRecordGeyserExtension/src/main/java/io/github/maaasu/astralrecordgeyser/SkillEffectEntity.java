package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** プレイヤースキルの仮想 TextDisplay を統合版パックの演出へ変換します。 */
record SkillEffectEntity(GeyserIntEntityProperty style, GeyserIntEntityProperty size) {
    private static final long MARKER = 0x41535452534b4c31L; // ASTRSKL1
    private static final int STYLE_BITS = 10;
    private static final int STYLE_MASK = (1 << STYLE_BITS) - 1;
    private static final int MAX_STYLE = 679;
    private static final int MAX_SIZE = 255;

    static boolean isMarker(UUID uuid) {
        return uuid.getMostSignificantBits() == MARKER;
    }

    static Frame decode(UUID uuid) {
        if (!isMarker(uuid)) return null;
        long data = uuid.getLeastSignificantBits();
        int style = (int) (data & STYLE_MASK);
        int size = (int) ((data >>> STYLE_BITS) & MAX_SIZE);
        return style <= MAX_STYLE && size > 0 ? new Frame(style, size) : null;
    }

    static CustomEntityDefinition definition() {
        return CustomEntityDefinition.of(Identifier.of("astralrecord", "skill_effect"));
    }

    static SkillEffectEntity registerProperties(GeyserDefineEntityPropertiesEvent event, Identifier identifier) {
        return new SkillEffectEntity(
            event.registerIntegerProperty(
                identifier, Identifier.of("astralrecord", "style"), 0, MAX_STYLE, 0),
            event.registerIntegerProperty(
                identifier, Identifier.of("astralrecord", "size"), 1, MAX_SIZE, 16)
        );
    }

    void initialize(GeyserEntity entity, Frame frame) {
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        entity.override(GeyserEntityDataTypes.WIDTH, 0f);
        entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
        entity.override(GeyserEntityDataTypes.VERTICAL_OFFSET, 0f);
        entity.updatePropertiesBatched(batch -> {
            batch.update(style, frame.style());
            batch.update(size, frame.size());
        }, false);
    }

    record Frame(int style, int size) {}
}
