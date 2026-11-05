package io.github.maaasu.astralrecordgeyser;

import java.util.ArrayList;
import java.util.List;

import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** GeyserとBedrockパックで共有するエンティティのプロパティ。 */
record DamageNumberEntity(GeyserIntEntityProperty length, GeyserIntEntityProperty style,
                          GeyserFloatEntityProperty size, List<GeyserIntEntityProperty> glyphs) {
    private static final String NAMESPACE = "astralrecord";
    static final String IDENTIFIER = NAMESPACE + ":damage_number";

    static DamageNumberEntity registerProperties(GeyserDefineEntityPropertiesEvent event) {
        Identifier entity = Identifier.of(IDENTIFIER);
        var length = event.registerIntegerProperty(entity, property("length"), 0, DamageNumber.MAX_GLYPHS, 0);
        var style = event.registerIntegerProperty(entity, property("style"), 0, 2, 0);
        var size = event.registerFloatProperty(entity, property("size"), 0.5f, 2f, 1f);
        var glyphs = new ArrayList<GeyserIntEntityProperty>(DamageNumber.MAX_GLYPHS);
        for (int i = 0; i < DamageNumber.MAX_GLYPHS; i++) {
            glyphs.add(event.registerIntegerProperty(entity, property("glyph_" + i), 0, DamageNumber.BLANK, DamageNumber.BLANK));
        }
        return new DamageNumberEntity(length, style, size, List.copyOf(glyphs));
    }

    static CustomEntityDefinition definition() {
        return CustomEntityDefinition.of(IDENTIFIER);
    }

    void initialize(GeyserEntity entity, DamageNumber number) {
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        int[] values = number.glyphs();
        entity.updatePropertiesBatched(batch -> {
            batch.update(length, values.length);
            batch.update(style, number.style());
            batch.update(size, number.scale());
            for (int i = 0; i < glyphs.size(); i++) {
                batch.update(glyphs.get(i), i < values.length ? values[i] : DamageNumber.BLANK);
            }
        }, false);
    }

    private static Identifier property(String path) {
        return Identifier.of(NAMESPACE, path);
    }
}
