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
     * @return 拡張が固定UVのスキル演出に対応する接続と確認した場合は true
     */
    static boolean hasSkillEffectsV2(UUID playerId) {
        return supports(playerId, "hasSkillEffectsV2");
    }

    static boolean hasWeaponAttackProjectiles(UUID playerId) {
        return supports(playerId, "hasWeaponAttackProjectiles");
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return 拡張がセレネアの描画に対応する接続と確認した場合は true
     */
    static boolean hasSelenea(UUID playerId) {
        return supports(playerId, "hasSelenea");
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return 拡張が戦闘報酬プレートに対応する接続と確認した場合は true
     */
    static boolean hasCombatRewardPlaque(UUID playerId) {
        return supports(playerId, "hasCombatRewardPlaque");
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return 接続のアクションリング HUD 互換性識別子。非対応なら null
     */
    static String actionRingHudRevision(UUID playerId) {
        Object value = invoke(playerId, "actionRingHudRevision");
        return value instanceof String revision && revision.matches("[0-9a-f]{64}") ? revision : null;
    }

    static boolean hasQuestHud(UUID playerId) {
        return supports(playerId, "hasQuestHud");
    }

    static boolean sendQuestHud(UUID playerId, String text) {
        if (!Geyser.isRegistered()) return false;
        var geyser = GeyserApi.api();
        if (geyser.connectionByUuid(playerId) == null) return false;
        var extension = geyser.extensionManager().extension("astralrecordgeyser");
        if (extension == null || !extension.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(extension.getClass()
                .getMethod("sendQuestHud", UUID.class, String.class).invoke(extension, playerId, text));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean supports(UUID playerId, String method) {
        return Boolean.TRUE.equals(invoke(playerId, method));
    }

    private static Object invoke(UUID playerId, String method) {
        if (!Geyser.isRegistered()) return null;
        var geyser = GeyserApi.api();
        if (geyser.connectionByUuid(playerId) == null) return null;
        var extension = geyser.extensionManager().extension("astralrecordgeyser");
        if (extension == null || !extension.isEnabled()) return null;
        try {
            // 拡張のクラスローダーやバージョンにProxyを直接依存させない。
            return extension.getClass().getMethod(method, UUID.class).invoke(extension, playerId);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
