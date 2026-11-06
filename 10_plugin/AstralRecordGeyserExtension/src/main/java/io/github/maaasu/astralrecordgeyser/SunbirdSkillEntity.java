package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** Paper が技の TextDisplay に載せた寸法を Bedrock の描画プロパティへ渡します。 */
record SunbirdSkillEntity(GeyserIntEntityProperty style, GeyserFloatEntityProperty width,
                          GeyserFloatEntityProperty length, GeyserFloatEntityProperty height) {
    private static final long MARKER = 0x41535453554e4631L; // ASTSUNF1
    private static final int MAX_STYLE = 5;
    private static final long SIZE_MASK = 0xfffL;
    private static final float SIZE_UNIT = 1f / 32;

    static boolean isMarker(UUID uuid) {
        return uuid.getMostSignificantBits() == MARKER;
    }

    static Frame decode(UUID uuid) {
        if (!isMarker(uuid)) return null;
        long bits = uuid.getLeastSignificantBits();
        int style = (int) (bits & 0xffL);
        float width = size(bits, 8);
        float length = size(bits, 20);
        float height = size(bits, 32);
        if (style > MAX_STYLE || width == 0 || length == 0 || height == 0) return null;
        return new Frame(style, width, length, height);
    }

    private static float size(long bits, int shift) {
        return ((bits >>> shift) & SIZE_MASK) * SIZE_UNIT;
    }

    static CustomEntityDefinition definition() {
        return CustomEntityDefinition.of(Identifier.of("astralrecord", "sunbird_skill"));
    }

    static SunbirdSkillEntity registerProperties(GeyserDefineEntityPropertiesEvent event, Identifier identifier) {
        return new SunbirdSkillEntity(
            event.registerIntegerProperty(identifier, Identifier.of("astralrecord", "style"), 0, MAX_STYLE, 0),
            registerSize(event, identifier, "width"),
            registerSize(event, identifier, "length"),
            registerSize(event, identifier, "height")
        );
    }

    private static GeyserFloatEntityProperty registerSize(GeyserDefineEntityPropertiesEvent event,
                                                          Identifier identifier, String name) {
        return event.registerFloatProperty(
            identifier, Identifier.of("astralrecord", name), SIZE_UNIT, SIZE_MASK * SIZE_UNIT, 1f
        );
    }

    void initialize(GeyserEntity entity, Frame frame) {
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        entity.override(GeyserEntityDataTypes.WIDTH, 0f);
        entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
        entity.override(GeyserEntityDataTypes.VERTICAL_OFFSET, 0f);
        entity.updatePropertiesBatched(batch -> {
            batch.update(style, frame.style());
            batch.update(width, frame.width());
            batch.update(length, frame.length());
            batch.update(height, frame.height());
        }, false);
    }

    record Frame(int style, float width, float length, float height) {}
}
