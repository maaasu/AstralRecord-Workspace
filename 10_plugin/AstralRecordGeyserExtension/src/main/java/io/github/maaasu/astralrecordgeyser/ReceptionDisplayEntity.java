package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** 受付モデルの種類と向きを統合版へ送ります。 */
record ReceptionDisplayEntity(GeyserIntEntityProperty style, GeyserIntEntityProperty yawTenths) {
    // Paper と共有する ASTRECPT マーカー。下位64ビットはエンティティID32・種類8・角度16・予約8。
    private static final long MARKER = 0x4153545245435054L;
    private static final int DUNGEON = 1;
    private static final int MAX_YAW_TENTHS = 3599;

    static CustomEntityDefinition definition() {
        return CustomEntityDefinition.of(Identifier.of("astralrecord", "reception_display"));
    }

    static ReceptionDisplayEntity registerProperties(GeyserDefineEntityPropertiesEvent event, Identifier identifier) {
        return new ReceptionDisplayEntity(
            event.registerIntegerProperty(identifier, Identifier.of("astralrecord", "reception_style"),
                0, DUNGEON, 0),
            event.registerIntegerProperty(identifier, Identifier.of("astralrecord", "reception_yaw_tenths"),
                0, MAX_YAW_TENTHS, 0));
    }

    static boolean isMarker(UUID uuid) {
        return uuid != null && uuid.getMostSignificantBits() == MARKER;
    }

    static Frame decode(UUID uuid, int entityId) {
        if (!isMarker(uuid)) {
            return null;
        }
        long payload = uuid.getLeastSignificantBits();
        int modelStyle = (int) (payload >>> 32) & 0xff;
        int modelYawTenths = (int) (payload >>> 40) & 0xffff;
        if ((int) payload != entityId || modelStyle > DUNGEON || modelYawTenths > MAX_YAW_TENTHS
            || (payload >>> 56) != 0) {
            return null;
        }
        return new Frame(modelStyle, modelYawTenths);
    }

    void initialize(GeyserEntity entity, Frame frame) {
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        entity.override(GeyserEntityDataTypes.WIDTH, 0f);
        entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
        entity.override(GeyserEntityDataTypes.VERTICAL_OFFSET, 0f);
        entity.updatePropertiesBatched(batch -> {
            batch.update(style, frame.style());
            batch.update(yawTenths, frame.yawTenths());
        }, false);
    }

    record Frame(int style, int yawTenths) {}
}
