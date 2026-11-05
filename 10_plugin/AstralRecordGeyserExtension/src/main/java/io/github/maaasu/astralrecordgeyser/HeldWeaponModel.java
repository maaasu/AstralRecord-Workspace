package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** Paper が本人に送る、長押し入力用トライデントの外観指定です。 */
record HeldWeaponModel(int id) {
    private static final long TAG = 0x4152574D4F44454CL;
    private static final int MAX_MODEL_ID = 0x00ff_ffff;

    static HeldWeaponModel decode(UUID uuid) {
        if (uuid == null || uuid.getMostSignificantBits() != TAG) return null;
        long payload = uuid.getLeastSignificantBits();
        if ((payload & ~((long) MAX_MODEL_ID)) != Long.MIN_VALUE) return null;
        return new HeldWeaponModel((int) (payload & MAX_MODEL_ID));
    }

    static GeyserFloatEntityProperty registerProperty(GeyserDefineEntityPropertiesEvent event) {
        // モデル番号の全範囲は float で正確に表せます。
        return event.registerFloatProperty(
            Identifier.of("minecraft", "player"), Identifier.of("astralrecord", "held_weapon_model"),
            0f, MAX_MODEL_ID, 0f
        );
    }
}
