package io.github.maaasu.astralrecordgeyser;

import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 統合版の一体型モデルにスプライトとゲージの差分を渡します。 */
record ActionRingPanelEntity(ActionRingEntity pose, List<GeyserIntEntityProperty> icons,
                             List<GeyserIntEntityProperty> cooldowns, GeyserIntEntityProperty time,
                             GeyserIntEntityProperty confirmed) {
    private static final Identifier IDENTIFIER = Identifier.of("astralrecord", "action_ring_panel");
    private static final long ICONS_TAG = 0xa57a710b00000000L;
    private static final long GAUGES_TAG = 0xa57a710c00000000L;
    private static final long FALLBACK_TAG = 0xa57a710d00000000L;
    private static final int SLOTS = 8;
    private static final int ACTIVE = 1 << SLOTS;

    static CustomEntityDefinition definition() {
        return CustomEntityDefinition.of(IDENTIFIER);
    }

    static ActionRingPanelEntity registerProperties(GeyserDefineEntityPropertiesEvent event) {
        ActionRingEntity pose = ActionRingEntity.registerProperties(event, IDENTIFIER);
        List<GeyserIntEntityProperty> icons = new ArrayList<>(SLOTS + 1);
        List<GeyserIntEntityProperty> cooldowns = new ArrayList<>(SLOTS);
        for (int slot = 0; slot <= SLOTS; slot++) {
            icons.add(integer(event, "icon_" + slot, 254));
            if (slot < SLOTS) {
                cooldowns.add(integer(event, "cd_" + slot, 10));
            }
        }
        return new ActionRingPanelEntity(pose, List.copyOf(icons), List.copyOf(cooldowns),
            integer(event, "time", 24), integer(event, "confirmed", SLOTS));
    }

    static boolean isUpdate(UUID uuid) {
        return targetId(uuid) > 0 && (tag(uuid) == ICONS_TAG || tag(uuid) == GAUGES_TAG);
    }

    static boolean isFallback(UUID uuid) {
        return targetId(uuid) > 0 && tag(uuid) == FALLBACK_TAG
            && uuid.getLeastSignificantBits() >= 0 && uuid.getLeastSignificantBits() <= SLOTS;
    }

    static int targetId(UUID uuid) {
        return uuid == null ? 0 : (int) uuid.getMostSignificantBits();
    }

    static boolean replacesFallback(GeyserEntity ring, int part) {
        // VARIANT holds the accepted sprite mask for this actor. It is never used to select client geometry.
        Integer mask = ring.override(GeyserEntityDataTypes.VARIANT);
        return mask != null && (part >= SLOTS ? (mask & ACTIVE) != 0 : (mask & (1 << part)) != 0);
    }

    void apply(GeyserEntity ring, UUID uuid, Set<Integer> sprites) {
        if (tag(uuid) == ICONS_TAG) {
            applyIcons(ring, uuid.getLeastSignificantBits(), sprites);
        } else {
            applyGauges(ring, uuid.getLeastSignificantBits(), sprites);
        }
    }

    private void applyIcons(GeyserEntity ring, long packed, Set<Integer> sprites) {
        int[] selected = new int[SLOTS];
        int mask = ACTIVE;
        for (int slot = 0; slot < SLOTS; slot++) {
            int sprite = (int) ((packed >>> (slot * 8)) & 0xff);
            if (sprites.contains(sprite)) {
                selected[slot] = sprite;
                mask |= 1 << slot;
            }
        }
        ring.override(GeyserEntityDataTypes.VARIANT, mask);
        ring.updatePropertiesBatched(batch -> {
            for (int slot = 0; slot < SLOTS; slot++) {
                batch.update(icons.get(slot), selected[slot]);
            }
        });
    }

    private void applyGauges(GeyserEntity ring, long packed, Set<Integer> sprites) {
        int timer = (int) ((packed >>> 32) & 0x1f);
        int confirmedSlot = (int) ((packed >>> 37) & 0xf);
        int center = (int) ((packed >>> 41) & 0xff);
        if ((packed >>> 49) != 0 || timer > 24 || confirmedSlot > SLOTS) {
            return;
        }
        ring.updatePropertiesBatched(batch -> {
            for (int slot = 0; slot < SLOTS; slot++) {
                int progress = (int) ((packed >>> (slot * 4)) & 0xf);
                batch.update(cooldowns.get(slot), Math.min(10, progress));
            }
            batch.update(time, timer);
            batch.update(confirmed, confirmedSlot);
            batch.update(icons.get(SLOTS), sprites.contains(center) ? center : 0);
        });
    }

    private static long tag(UUID uuid) {
        return uuid.getMostSignificantBits() & 0xffffffff00000000L;
    }

    private static GeyserIntEntityProperty integer(GeyserDefineEntityPropertiesEvent event, String name, int max) {
        return event.registerIntegerProperty(IDENTIFIER, Identifier.of("astralrecord", "panel_" + name), 0, max, 0);
    }
}
