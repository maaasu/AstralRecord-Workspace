package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.property.type.GeyserBooleanEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** Paper の演出用 TextDisplay に載せたボスの状態を統合版へ反映します。 */
record BossMotion(GeyserIntEntityProperty motion, GeyserBooleanEntityProperty invulnerable,
                  GeyserIntEntityProperty bodyYawTenths) {
    private static final long INVULNERABLE_BIT = 1L << 40;

    static Frame decode(UUID uuid, long marker, int maxMotion) {
        if (uuid.getMostSignificantBits() != marker) return null;
        long bits = uuid.getLeastSignificantBits();
        int targetId = (int) bits;
        int motion = (int) ((bits >>> 32) & 0xffL);
        if (targetId <= 0 || motion > maxMotion) return null;
        // bits 41..52: 0 is the legacy payload; 1..3600 stores normalized yaw + 1.
        int encodedYaw = (int) ((bits >>> 41) & 0xfffL);
        if (encodedYaw > 3600) return null;
        return new Frame(targetId, motion, (bits & INVULNERABLE_BIT) != 0, encodedYaw - 1);
    }

    static BossMotion registerProperties(GeyserDefineEntityPropertiesEvent event, Identifier identifier, int maxMotion) {
        return new BossMotion(
            event.registerIntegerProperty(identifier, Identifier.of("astralrecord", "motion"), 0, maxMotion, 0),
            event.registerBooleanProperty(identifier, Identifier.of("astralrecord", "invulnerable"), false),
            event.registerIntegerProperty(identifier, Identifier.of("astralrecord", "body_yaw_tenths"), -1, 3599, -1)
        );
    }

    void apply(GeyserEntity entity, Frame frame) {
        entity.updatePropertiesBatched(batch -> {
            batch.update(motion, frame.motion());
            batch.update(invulnerable, frame.invulnerable());
            batch.update(bodyYawTenths, frame.bodyYawTenths());
        });
    }

    record Frame(int targetId, int motion, boolean invulnerable, int bodyYawTenths) {}
}
