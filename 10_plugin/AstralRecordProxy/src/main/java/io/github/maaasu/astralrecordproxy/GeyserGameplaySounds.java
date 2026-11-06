package io.github.maaasu.astralrecordproxy;

import java.util.UUID;
import org.geysermc.api.Geyser;
import org.geysermc.geyser.api.GeyserApi;

/** 接続時に選んだBedrockパックの効果音対応を、Geyser拡張へ問い合わせます。 */
final class GeyserGameplaySounds {
    private GeyserGameplaySounds() {
    }

    /**
     * 古い拡張またはパックを使う接続では、RPG側で標準音を再生します。
     *
     * @param playerId 接続中プレイヤーのJava側UUID
     * @return 拡張が専用効果音に対応する接続と確認した場合はtrue
     */
    static boolean isReady(UUID playerId) {
        if (!Geyser.isRegistered()) return false;
        var geyser = GeyserApi.api();
        if (geyser.connectionByUuid(playerId) == null) return false;
        var extension = geyser.extensionManager().extension("astralrecordgeyser");
        if (extension == null || !extension.isEnabled()) return false;
        try {
            // 拡張のクラスローダーやバージョンにProxyを直接依存させない。
            return Boolean.TRUE.equals(extension.getClass().getMethod("hasGameplaySounds", UUID.class)
                .invoke(extension, playerId));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }
}
