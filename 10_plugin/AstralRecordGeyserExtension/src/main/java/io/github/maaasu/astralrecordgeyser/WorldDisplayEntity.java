package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** ウェイストーンとブロック型NPCのモデルを統合版へ送ります。 */
record WorldDisplayEntity(GeyserIntEntityProperty style) {
    // Paper の WorldDisplayModel と同じパケット契約。
    private static final long MODEL_TAG = 0x415354574d4f444cL;
    private static final long FALLBACK_TAG = 0x4153545746414c4cL;
    private static final int STORAGE = 2;
    private static final int BLAST_FURNACE = 3;
    private static final Identifier TEXT_DISPLAY = Identifier.of("minecraft", "text_display");
    private static final Identifier ITEM_DISPLAY = Identifier.of("minecraft", "item_display");
    private static final Identifier BLOCK_DISPLAY = Identifier.of("minecraft", "block_display");
    private static final Identifier IDENTIFIER = Identifier.of("astralrecord", "world_display");
    static final CustomEntityDefinition DEFINITION = CustomEntityDefinition.of(IDENTIFIER);

    static WorldDisplayEntity registerProperties(GeyserDefineEntityPropertiesEvent event) {
        return new WorldDisplayEntity(event.registerIntegerProperty(
            IDENTIFIER, Identifier.of("astralrecord", "style"), 0, BLAST_FURNACE, 0));
    }

    void onSpawn(ServerSpawnEntityEvent event, DamagePackDistribution packs) {
        int model = modelCode(event.uuid(), event.entityId(), MODEL_TAG);
        if (model >= 0 && event.entityType().is(TEXT_DISPLAY)) {
            if (!packs.worldDisplayReady(event.connection())) {
                event.setCancelled(true);
                return;
            }
            event.definition(DEFINITION);
            event.preSpawnConsumer(entity -> {
                entity.override(GeyserEntityDataTypes.SCALE, 1f);
                entity.override(GeyserEntityDataTypes.WIDTH, 0f);
                entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
                entity.updatePropertiesBatched(batch -> batch.update(style, model), false);
            });
            return;
        }

        int fallback = modelCode(event.uuid(), event.entityId(), FALLBACK_TAG);
        boolean npcFallback = (fallback == STORAGE && event.entityType().is(ITEM_DISPLAY))
            || (fallback == BLAST_FURNACE && event.entityType().is(BLOCK_DISPLAY));
        if (npcFallback && packs.worldDisplayReady(event.connection())) {
            event.setCancelled(true);
        }
    }

    private static int modelCode(UUID uuid, int entityId, long tag) {
        long payload = uuid.getLeastSignificantBits();
        long code = payload >>> 32;
        return uuid.getMostSignificantBits() == tag && (int) payload == entityId && code <= BLAST_FURNACE
            ? (int) code : -1;
    }
}
