package io.github.maaasu.astralrecordgeyser;

import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** 1体の輪郭モデルの向き、倍率、8枠の配色を切り替えるプロパティです。 */
record ActionRingEntity(GeyserFloatEntityProperty yaw, GeyserFloatEntityProperty pitch,
                        GeyserFloatEntityProperty roll, GeyserFloatEntityProperty size,
                        GeyserIntEntityProperty selected, GeyserIntEntityProperty cooldowns) {
    private static final Identifier IDENTIFIER = Identifier.of("astralrecord", "action_ring");

    static CustomEntityDefinition definition() {
        return CustomEntityDefinition.of(IDENTIFIER);
    }

    static ActionRingEntity registerProperties(GeyserDefineEntityPropertiesEvent event) {
        return new ActionRingEntity(
            event.registerFloatProperty(IDENTIFIER, property("yaw"), -180f, 180f, 0f),
            event.registerFloatProperty(IDENTIFIER, property("pitch"), -90f, 90f, 0f),
            event.registerFloatProperty(IDENTIFIER, property("roll"), -180f, 180f, 0f),
            event.registerFloatProperty(IDENTIFIER, property("size"), 0f, 4f, 1f),
            event.registerIntegerProperty(IDENTIFIER, property("selected"), 0, 8, 0),
            event.registerIntegerProperty(IDENTIFIER, property("cooldowns"), 0, 255, 0)
        );
    }

    void initialize(GeyserEntity entity, ActionRingFrame frame) {
        // TextDisplay の通常の変換では SCALE=0 で防具立てが隠れます。
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        apply(entity, frame, false);
    }

    void apply(GeyserEntity entity, ActionRingFrame frame, boolean send) {
        entity.updatePropertiesBatched(batch -> {
            batch.update(yaw, frame.yaw());
            batch.update(pitch, frame.pitch());
            batch.update(roll, frame.roll());
            batch.update(size, frame.size());
            batch.update(selected, frame.selected());
            batch.update(cooldowns, frame.cooldowns());
        }, send);
    }

    private static Identifier property(String name) {
        return Identifier.of("astralrecord", "ring_" + name);
    }
}
