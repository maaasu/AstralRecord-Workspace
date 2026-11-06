package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.property.type.GeyserBooleanEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** Paper の演出用 TextDisplay に載せたサンバードの状態を統合版へ反映します。 */
record SunbirdMotion(GeyserIntEntityProperty motion, GeyserBooleanEntityProperty invulnerable) {
    private static final long MARKER = 0x41535453554e4d31L; // ASTSUNM1
    private static final int MAX_MOTION = 3;
    private static final long INVULNERABLE_BIT = 1L << 40;

    static boolean isMarker(UUID uuid) {
        return uuid.getMostSignificantBits() == MARKER;
    }

    static Frame decode(UUID uuid) {
        if (!isMarker(uuid)) return null;
        long bits = uuid.getLeastSignificantBits();
        int targetId = (int) bits;
        int motion = (int) ((bits >>> 32) & 0xffL);
        if (targetId <= 0 || motion > MAX_MOTION) return null;
        return new Frame(targetId, motion, (bits & INVULNERABLE_BIT) != 0);
    }

    static SunbirdMotion registerProperties(GeyserDefineEntityPropertiesEvent event, Identifier identifier) {
        return new SunbirdMotion(
            event.registerIntegerProperty(identifier, Identifier.of("astralrecord", "motion"), 0, MAX_MOTION, 0),
            event.registerBooleanProperty(identifier, Identifier.of("astralrecord", "invulnerable"), false)
        );
    }

    void apply(GeyserEntity entity, Frame frame) {
        entity.updatePropertiesBatched(batch -> {
            batch.update(motion, frame.motion());
            batch.update(invulnerable, frame.invulnerable());
        });
    }

    record Frame(int targetId, int motion, boolean invulnerable) {}
}
