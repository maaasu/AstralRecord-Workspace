package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/**
 * Paper が技の TextDisplay に載せた寸法を Bedrock の描画プロパティへ渡します。
 * {@code param} はボスごとに任意で、UUID 下位64bitの44～55bitを残りtickや隙間の配置としてそのまま渡します。
 */
record BossSkillEntity(GeyserIntEntityProperty style, GeyserFloatEntityProperty width,
                          GeyserFloatEntityProperty length, GeyserFloatEntityProperty height,
                          GeyserIntEntityProperty param) {
    private static final long SIZE_MASK = 0xfffL;
    private static final float SIZE_UNIT = 1f / 32;
    private static final int PARAM_MAX = 0xfff;

    static Frame decode(UUID uuid, long marker, int maxStyle) {
        if (uuid.getMostSignificantBits() != marker) return null;
        long bits = uuid.getLeastSignificantBits();
        int style = (int) (bits & 0xffL);
        float width = size(bits, 8);
        float length = size(bits, 20);
        float height = size(bits, 32);
        if (style > maxStyle || width == 0 || length == 0 || height == 0) return null;
        return new Frame(style, width, length, height);
    }

    private static float size(long bits, int shift) {
        return ((bits >>> shift) & SIZE_MASK) * SIZE_UNIT;
    }

    /** @return UUID 下位64bitの44～55bit。param を使わないボスでは上位の乱数部分にあたります */
    static int param(UUID uuid) {
        return (int) ((uuid.getLeastSignificantBits() >>> 44) & PARAM_MAX);
    }

    static BossSkillEntity registerProperties(GeyserDefineEntityPropertiesEvent event, Identifier identifier, int maxStyle,
                                              boolean withParam) {
        return new BossSkillEntity(
            event.registerIntegerProperty(identifier, Identifier.of("astralrecord", "style"), 0, maxStyle, 0),
            registerSize(event, identifier, "width"),
            registerSize(event, identifier, "length"),
            registerSize(event, identifier, "height"),
            withParam ? event.registerIntegerProperty(identifier, Identifier.of("astralrecord", "param"), 0, PARAM_MAX, 0) : null
        );
    }

    private static GeyserFloatEntityProperty registerSize(GeyserDefineEntityPropertiesEvent event,
                                                          Identifier identifier, String name) {
        return event.registerFloatProperty(
            identifier, Identifier.of("astralrecord", name), SIZE_UNIT, SIZE_MASK * SIZE_UNIT, 1f
        );
    }

    void initialize(GeyserEntity entity, Frame frame, int value) {
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        entity.override(GeyserEntityDataTypes.WIDTH, 0f);
        entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
        entity.override(GeyserEntityDataTypes.VERTICAL_OFFSET, 0f);
        entity.updatePropertiesBatched(batch -> {
            batch.update(style, frame.style());
            batch.update(width, frame.width());
            batch.update(length, frame.length());
            batch.update(height, frame.height());
            if (param != null) batch.update(param, value);
        }, false);
    }

    record Frame(int style, float width, float length, float height) {}
}
