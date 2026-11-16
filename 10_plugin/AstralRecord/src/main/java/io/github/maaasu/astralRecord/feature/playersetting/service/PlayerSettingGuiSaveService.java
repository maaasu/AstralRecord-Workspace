package io.github.maaasu.astralRecord.feature.playersetting.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** インベントリ GUI と統合版フォームの設定変更を適用します。 */
public final class PlayerSettingGuiSaveService {
    private final AstralRecord plugin;
    private final PlayerSettingService settings;
    private final Predicate<UUID> bedrockQuestHudSupported;

    /**
     * @param plugin 表示に関わる設定効果の同期に使用するプラグイン
     * @param settings プレイヤー設定サービス
     * @param bedrockQuestHudSupported 統合版の接続単位の追跡HUD可否を判定する処理
     */
    public PlayerSettingGuiSaveService(
        @NotNull AstralRecord plugin,
        @NotNull PlayerSettingService settings,
        @NotNull Predicate<UUID> bedrockQuestHudSupported
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.bedrockQuestHudSupported = bedrockQuestHudSupported;
    }

    /**
     * @param player 設定フォームの表示対象
     * @return 現在の統合版接続が専用TIP表示に対応している場合はtrue
     */
    public boolean hasBedrockQuestHud(@NotNull Player player) {
        return bedrockQuestHudSupported.test(player.getUniqueId());
    }

    /**
     * 現在のプレイヤーに属する下書きだけをメインスレッドで反映します。
     * @param player 操作したプレイヤー
     * @param userId 下書きの所有ユーザー
     * @param sessionToken 下書きの設定セッション
     * @param changes 変更候補
     */
    public void save(
        @NotNull Player player,
        @NotNull UUID userId,
        long sessionToken,
        @NotNull Map<PlayerSettingKey, Object> changes
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getUser().getUuid().equals(userId)) {
            return;
        }
        Map<PlayerSettingKey, Object> updated = new EnumMap<>(PlayerSettingKey.class);
        for (var change : changes.entrySet()) {
            PlayerSettingKey key = change.getKey();
            if (key == PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE && !astPlayer.hasAdminPermission()) {
                continue;
            }
            if (key == PlayerSettingKey.QUEST_HUD_DISPLAY && astPlayer.isBedrock()
                && !hasBedrockQuestHud(player)) {
                continue;
            }
            Object value = change.getValue();
            if (settings.getPlayerSetting(userId, key).equals(value)) {
                continue;
            }
            var result = settings.updatePlayerSetting(
                new PlayerSettingChangeRequest(userId, key, value, userId),
                sessionToken
            );
            if (result.staleSession()) {
                break;
            }
            if (result.success()) {
                updated.put(key, value);
            }
        }
        updated.forEach((key, value) -> PlayerMessageService.getInstance().sendRaw(player, PlayerMsgResource.format(
            PlayerSettingMsgId.P_5321.getId(), key.getDisplayNameJa(), key.formatValue(value)
        )));
        if (updated.containsKey(PlayerSettingKey.ARMOR_DISPLAY)) {
            plugin.getItemStackPacketAdapter().refreshEquipmentView(player);
        }
        if (updated.containsKey(PlayerSettingKey.ACTION_RING_HOLD_SELECT)
            || updated.containsKey(PlayerSettingKey.RANDOM_STATUS_RANGE_DISPLAY)) {
            player.updateInventory();
        }
        if (updated.containsKey(PlayerSettingKey.SKILL_TREE_COMPACT_DISPLAY)) {
            plugin.getSkillTreeService().markViewerContextDirty(player);
        }
        if (updated.containsKey(PlayerSettingKey.QUEST_HUD_DISPLAY)) {
            plugin.refreshQuestHud(astPlayer);
        }
        if (updated.containsKey(PlayerSettingKey.NIGHT_VISION)) {
            plugin.getPlayerSettingEffectService().synchronizeNightVision(player);
        }
    }
}
