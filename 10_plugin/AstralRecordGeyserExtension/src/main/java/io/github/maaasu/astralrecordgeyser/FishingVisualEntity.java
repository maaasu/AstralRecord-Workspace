package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** 釣りの仮想 TextDisplay を統合版のモデルと形状更新に変換します。 */
record FishingVisualEntity(GeyserIntEntityProperty style, GeyserFloatEntityProperty dx,
                           GeyserFloatEntityProperty dy, GeyserFloatEntityProperty dz,
                           GeyserFloatEntityProperty sag) {
    // RPG のパケット契約。生成は糸と浮き、着水、水面の軌跡を識別します。
    private static final long SPAWN_TAG = 0x4153544649534831L;
    private static final long UPDATE_TAG = 0x4153464000000000L;
    private static final long UPDATE_TAG_MASK = 0xffffffe000000000L;
    private static final int MAX_EXPONENT = 19;
    private static final int ROPE = 0;
    private static final int MAX_STYLE = 2;
    private static final float UNITS_PER_BLOCK = 256f;
    private static final float MAX_DISPLACEMENT = 67_108_864f;
    private static final float MAX_SAG = 1f;
    private static final Identifier IDENTIFIER = Identifier.of("astralrecord", "fishing_visual");
    static final CustomEntityDefinition DEFINITION = CustomEntityDefinition.of(IDENTIFIER);

    static FishingVisualEntity registerProperties(GeyserDefineEntityPropertiesEvent event) {
        return new FishingVisualEntity(
            event.registerIntegerProperty(IDENTIFIER, property("style"), ROPE, MAX_STYLE, ROPE),
            displacement(event, "dx"), displacement(event, "dy"), displacement(event, "dz"),
            event.registerFloatProperty(IDENTIFIER, property("sag"), 0f, MAX_SAG, 0f)
        );
    }

    static boolean onSpawn(ServerSpawnEntityEvent event, DamagePackDistribution packs, FishingVisualEntity properties) {
        UUID uuid = event.uuid();
        long tag = uuid.getMostSignificantBits();
        if (tag == SPAWN_TAG) {
            int modelStyle = styleOf(uuid, event.entityId());
            if (modelStyle < 0 || properties == null || !packs.fishingVisualsReady(event.connection())) {
                event.setCancelled(true);
            } else {
                event.definition(DEFINITION);
                event.preSpawnConsumer(entity -> properties.initialize(entity, modelStyle));
            }
            return true;
        }
        if ((tag & UPDATE_TAG_MASK) == UPDATE_TAG) {
            event.setCancelled(true);
            int targetId = (int) tag;
            int exponent = (int) (tag >>> 32) & 0x1f;
            if (properties != null && targetId > 0 && exponent <= MAX_EXPONENT) {
                GeyserEntity target = event.connection().entities().byJavaId(targetId);
                if (target != null && DEFINITION.equals(target.definition()) && styleOf(target.uuid(), targetId) == ROPE) {
                    Pose pose = decodePose(uuid.getLeastSignificantBits(), exponent);
                    if (pose != null) {
                        properties.apply(target, pose);
                    }
                }
            }
            return true;
        }
        return false;
    }

    static int styleOf(UUID uuid, int entityId) {
        if (uuid == null || uuid.getMostSignificantBits() != SPAWN_TAG) {
            return -1;
        }
        long payload = uuid.getLeastSignificantBits();
        long modelStyle = payload >>> 32;
        return (int) payload == entityId && modelStyle <= MAX_STYLE ? (int) modelStyle : -1;
    }

    static Pose decodePose(long packed, int exponent) {
        float sagBlocks = (packed >>> 48) / UNITS_PER_BLOCK;
        if (sagBlocks > MAX_SAG) {
            return null;
        }
        float scale = Math.scalb(1f, exponent) / UNITS_PER_BLOCK;
        return new Pose((short) packed * scale, (short) (packed >>> 16) * scale,
            (short) (packed >>> 32) * scale, sagBlocks);
    }

    private void initialize(GeyserEntity entity, int modelStyle) {
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        entity.override(GeyserEntityDataTypes.WIDTH, 0f);
        entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
        entity.override(GeyserEntityDataTypes.VERTICAL_OFFSET, 0f);
        entity.updatePropertiesBatched(batch -> batch.update(style, modelStyle), false);
    }

    private void apply(GeyserEntity entity, Pose pose) {
        entity.updatePropertiesBatched(batch -> {
            batch.update(dx, pose.dx());
            batch.update(dy, pose.dy());
            batch.update(dz, pose.dz());
            batch.update(sag, pose.sag());
        }, true);
    }

    private static GeyserFloatEntityProperty displacement(GeyserDefineEntityPropertiesEvent event, String name) {
        return event.registerFloatProperty(IDENTIFIER, property(name),
            -MAX_DISPLACEMENT, MAX_DISPLACEMENT, 0f);
    }

    private static Identifier property(String name) {
        return Identifier.of("astralrecord", name);
    }

    record Pose(float dx, float dy, float dz, float sag) {}
}
