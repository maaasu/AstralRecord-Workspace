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
    static boolean hasChallengeCountdown(UUID playerId) {
        return supports(playerId, "hasChallengeCountdown");
    }

    /** @return 専用ダンジョンクリア音がある接続の場合 true */
    static boolean hasDungeonClearSound(UUID playerId) {
        return supports(playerId, "hasDungeonClearSound");
    }

    /** @return 拡張が専用効果音の対応を確認した場合true */
    static boolean hasGameplaySounds(UUID playerId) {
        return supports(playerId, "hasGameplaySounds");
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return 拡張がツールチップに対応する接続と確認した場合は true
     */
    static boolean hasSkillTooltips(UUID playerId) {
        return supports(playerId, "hasSkillTooltips");
    }

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

    static boolean hasArchmagePhoenix(UUID playerId) {
        return supports(playerId, "hasArchmagePhoenix");
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return 拡張が天球心臓ニクセロンの描画に対応する接続と確認した場合は true
     */
    static boolean hasNyxeron(UUID playerId) {
        return supports(playerId, "hasNyxeron");
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
     * @return 拡張が報酬プレートの可変表示時間に対応すると確認した場合はtrue
     */
    static boolean hasCombatRewardPlaqueTiming(UUID playerId) {
        return supports(playerId, "hasCombatRewardPlaqueTiming");
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return 拡張が報酬プレートの可変幅に対応すると確認した場合はtrue
     */
    static boolean hasCombatRewardPlaqueWidth(UUID playerId) {
        return supports(playerId, "hasCombatRewardPlaqueWidth");
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
        return supports(playerId, "hasQuestHudV2");
    }

    static boolean sendQuestHud(UUID playerId, String text) {
        if (!Geyser.isRegistered()) return false;
        var geyser = GeyserApi.api();
        if (geyser.connectionByUuid(playerId) == null) return false;
        var extension = geyser.extensionManager().extension("astralrecordgeyser");
        if (extension == null || !extension.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(extension.getClass()
                .getMethod("sendQuestHudV2", UUID.class, String.class).invoke(extension, playerId, text));
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
