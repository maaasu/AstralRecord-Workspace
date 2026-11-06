package io.github.maaasu.astralrecordproxy;

import java.util.UUID;
import org.geysermc.api.Geyser;
import org.geysermc.geyser.api.GeyserApi;

/** 接続時に選んだ Bedrock パックの機能対応を、Geyser 拡張へ問い合わせます。 */
final class GeyserPackCapabilities {
    private GeyserPackCapabilities() {
    }

    /**
     * 古い拡張またはパックを使う接続では、RPG側で標準音を再生します。
     *
     * @param playerId 接続中プレイヤーのJava側UUID
     * @return 拡張が専用効果音に対応する接続と確認した場合はtrue
     */
    static boolean hasGameplaySounds(UUID playerId) {
        return supports(playerId, "hasGameplaySounds");
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return 拡張がツールチップに対応する接続と確認した場合は true
     */
    static boolean hasItemTooltips(UUID playerId) {
        return supports(playerId, "hasItemTooltips");
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return 拡張が通常攻撃の飛翔演出に対応する接続と確認した場合は true
     */
    static boolean hasWeaponAttackProjectiles(UUID playerId) {
        return supports(playerId, "hasWeaponAttackProjectiles");
    }

    private static boolean supports(UUID playerId, String method) {
        if (!Geyser.isRegistered()) return false;
        var geyser = GeyserApi.api();
        if (geyser.connectionByUuid(playerId) == null) return false;
        var extension = geyser.extensionManager().extension("astralrecordgeyser");
        if (extension == null || !extension.isEnabled()) return false;
        try {
            // 拡張のクラスローダーやバージョンにProxyを直接依存させない。
            return Boolean.TRUE.equals(extension.getClass().getMethod(method, UUID.class)
                .invoke(extension, playerId));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }
}
