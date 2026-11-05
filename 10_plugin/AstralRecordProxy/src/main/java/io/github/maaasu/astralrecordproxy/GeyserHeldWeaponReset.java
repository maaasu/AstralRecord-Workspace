package io.github.maaasu.astralrecordproxy;

import java.util.UUID;
import org.geysermc.api.Geyser;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.util.Identifier;

/** Velocity 内の切替では同じ Geyser 接続が使われるため、前のチャンネルの武器表示を解除します。 */
final class GeyserHeldWeaponReset {
    private static final Identifier HELD_WEAPON = Identifier.of("astralrecord", "held_weapon_model");

    private GeyserHeldWeaponReset() {}

    static void reset(UUID playerId) {
        if (!Geyser.isRegistered()) return;
        var geyser = GeyserApi.api();
        // 古い Geyser を使う Proxy では、対応する拡張自体が読み込まれません。
        if (geyser.extensionManager().extension("astralrecordgeyser") == null) return;
        var connection = geyser.connectionByUuid(playerId);
        if (connection == null) return;

        var player = connection.playerEntity();
        for (var property : player.definition().properties()) {
            if (property instanceof GeyserFloatEntityProperty model && HELD_WEAPON.equals(model.identifier())) {
                player.updatePropertiesBatched(batch -> batch.update(model, 0.0F), true);
                return;
            }
        }
    }
}
