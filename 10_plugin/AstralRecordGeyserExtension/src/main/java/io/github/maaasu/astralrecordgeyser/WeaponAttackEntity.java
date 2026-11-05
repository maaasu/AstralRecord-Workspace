package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** Paper が通常攻撃の閲覧者へ送る TextDisplay を Bedrock の演出に変換します。 */
record WeaponAttackEntity(GeyserIntEntityProperty style) {
    static final int MAX_STYLE = 5;
    private static final long MARKER = 0x4153545241544b31L; // ASTRATK1
    private static final Identifier IDENTIFIER = Identifier.of("astralrecord", "weapon_attack");

    static boolean isMarker(UUID uuid) {
        return uuid.getMostSignificantBits() == MARKER;
    }

    static int styleOf(UUID uuid) {
        return (int) (uuid.getLeastSignificantBits() & 0xffL);
    }

    static CustomEntityDefinition definition() {
        return CustomEntityDefinition.of(IDENTIFIER);
    }

    static WeaponAttackEntity registerProperties(GeyserDefineEntityPropertiesEvent event) {
        return new WeaponAttackEntity(event.registerIntegerProperty(
            IDENTIFIER, Identifier.of("astralrecord", "style"), 0, MAX_STYLE, 0));
    }

    void initialize(GeyserEntity entity, int attackStyle) {
        // 通常の TextDisplay 変換は防具立てを隠すため SCALE=0 を使います。
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        entity.override(GeyserEntityDataTypes.WIDTH, 0f);
        entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
        entity.updatePropertiesBatched(batch -> batch.update(style, attackStyle), false);
    }
}
