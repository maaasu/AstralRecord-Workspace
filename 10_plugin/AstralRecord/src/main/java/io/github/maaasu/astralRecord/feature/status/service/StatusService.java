package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.service.BuffService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * ステータス機能のビジネスロジックを担うサービスクラスです。
 * <p>
 * 現時点ではプレイヤーの {@code account mode} と {@code permission} から簡易的なステータスを計算します。
 * 将来的にレベル・装備・バフ補正を追加する際も、このサービスへ集約する想定です。
 */
public class StatusService {

    private final BuffService buffService;

    public StatusService() {
        this.buffService = new BuffService();
    }

    /**
     * プレイヤーの現在ステータスを取得します。
     * 未計算の場合は初回計算を行い、その結果を返します。
     *
     * @param player 対象プレイヤー
     * @return 現在のステータススナップショット
     */
    public @NotNull StatusSnapshot getStatus(@NotNull AstPlayer player) {
        if (player.getStatusSnapshot().getValues().isEmpty()) {
            return refreshStatus(player);
        }
        return player.getStatusSnapshot();
    }

    /**
     * プレイヤーのステータスを再計算し、{@link AstPlayer} に反映します。
     *
     * @param player 対象プレイヤー
     * @return 再計算後のステータススナップショット
     */
    public @NotNull StatusSnapshot refreshStatus(@NotNull AstPlayer player) {
        buffService.purgeExpired(player);

        StatusSnapshot previous = player.getStatusSnapshot();
        StatusSnapshot refreshed = createSnapshot(player);

        StatusSnapshot merged;
        if (previous.getValues().isEmpty()) {
            // 初回は全快状態で開始
            merged = restoreAllInternal(refreshed);
        } else {
            // 再計算時は現在値を維持しつつ、新しい最大値へクランプ
            merged = refreshed.withCurrentValues(previous.getCurrentHp(), previous.getCurrentMp());
        }

        player.setStatusSnapshot(merged);
        return merged;
    }

    /**
     * バフを付与し、ステータスを再計算します。
     *
     * @param player   対象プレイヤー
     * @param buffId   付与するバフID
     * @return 再計算後のステータススナップショット
     */
    public @NotNull StatusSnapshot applyBuff(@NotNull AstPlayer player, @NotNull String buffId) {
        if (!buffService.apply(player, buffId)) {
            return getStatus(player);
        }
        return refreshStatus(player);
    }

    /**
     * バフを解除し、ステータスを再計算します。
     *
     * @param player   対象プレイヤー
     * @param buffId   解除するバフID
     * @return 再計算後のステータススナップショット
     */
    public @NotNull StatusSnapshot removeBuff(@NotNull AstPlayer player, @NotNull String buffId) {
        buffService.remove(player, buffId);
        return refreshStatus(player);
    }

    /**
     * 現在有効なバフ一覧を返します。
     *
     * @param player 対象プレイヤー
     * @return 有効バフ一覧
     */
    public @NotNull List<ActiveBuff> getActiveBuffs(@NotNull AstPlayer player) {
        return buffService.getActiveBuffs(player);
    }

    /**
     * 現在HPを減少させます。
     *
     * @param player 対象プレイヤー
     * @param amount 減少量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot consumeHp(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(snapshot.getCurrentHp() - amount, snapshot.getCurrentMp());
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在MPを減少させます。
     *
     * @param player 対象プレイヤー
     * @param amount 減少量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot consumeMp(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(snapshot.getCurrentHp(), snapshot.getCurrentMp() - amount);
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在HPを回復します。
     *
     * @param player 対象プレイヤー
     * @param amount 回復量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot recoverHp(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(snapshot.getCurrentHp() + amount, snapshot.getCurrentMp());
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在MPを回復します。
     *
     * @param player 対象プレイヤー
     * @param amount 回復量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot recoverMp(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(snapshot.getCurrentHp(), snapshot.getCurrentMp() + amount);
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在エネルギーを減少させます。
     *
     * @param player 対象プレイヤー
     * @param amount 減少量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot consumeEnergy(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(
            snapshot.getCurrentHp(), snapshot.getCurrentMp(), snapshot.getCurrentEnergy() - amount
        );
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在エネルギーを回復します。
     *
     * @param player 対象プレイヤー
     * @param amount 回復量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot recoverEnergy(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(
            snapshot.getCurrentHp(), snapshot.getCurrentMp(), snapshot.getCurrentEnergy() + amount
        );
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在HP/MP/エネルギーを最大値まで回復します。
     *
     * @param player 対象プレイヤー
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot restoreAll(@NotNull AstPlayer player) {
        StatusSnapshot snapshot = restoreAllInternal(getStatus(player));
        player.setStatusSnapshot(snapshot);
        return snapshot;
    }

    private @NotNull StatusSnapshot createSnapshot(@NotNull AstPlayer player) {
        Map<StatusType, StatusValue> values = new EnumMap<>(StatusType.class);

        for (StatusType type : StatusType.values()) {
            double baseValue = getBaseValue(type);
            double bonusValue = getBonusValue(player, type, baseValue);
            values.put(type, new StatusValue(baseValue, bonusValue));
        }

        return new StatusSnapshot(values, 0.0D, 0.0D, 0.0D, LocalDateTime.now());
    }

    private @NotNull StatusSnapshot restoreAllInternal(@NotNull StatusSnapshot snapshot) {
        double maxHp = snapshot.getMaxValue(StatusType.MAX_HEALTH);
        double maxMp = snapshot.getMaxValue(StatusType.MAX_MANA);
        double maxEnergy = snapshot.getMaxValue(StatusType.MAX_ENERGY);
        return snapshot.withCurrentValues(maxHp, maxMp, maxEnergy);
    }

    private double getBaseValue(@NotNull StatusType type) {
        return switch (type) {
            // リソース系
            case MAX_HEALTH -> 20.0D;
            case MAX_MANA -> 10.0D;
            case MAX_ENERGY -> 100.0D;
            // 基本能力値
            case STRENGTH -> 5.0D;
            case DEXTERITY -> 5.0D;
            case INTELLIGENCE -> 5.0D;
            case VITALITY -> 5.0D;
            case AGILITY -> 5.0D;
            case LUCK -> 5.0D;
            // 攻撃系
            case ATTACK -> 8.0D;
            case MELEE_ATTACK -> 0.0D;     // ATTACK × STR から派生（将来の戦闘システムで算出）
            case RANGED_ATTACK -> 0.0D;    // ATTACK × DEX から派生
            case MAGIC_ATTACK -> 0.0D;     // ATTACK × INT から派生
            case CRITICAL_RATE -> 5.0D;
            case CRITICAL_DAMAGE -> 150.0D;
            case SUPER_CRITICAL_RATE -> 0.0D;
            case SUPER_CRITICAL_DAMAGE -> 200.0D;
            case FINAL_DAMAGE_RATE -> 0.0D;
            case FINAL_DAMAGE_MULTIPLIER -> 130.0D;
            case ACCURACY -> 95.0D;
            case ATTACK_SPEED -> 100.0D;
            // 防御系
            case DEFENSE -> 5.0D;
            case MAGIC_DEFENSE -> 3.0D;
            case EVASION -> 3.0D;
            // 回復・ユーティリティ系
            case HP_REGEN -> 1.0D;
            case MP_REGEN -> 0.5D;
            case ENERGY_REGEN -> 5.0D;
            case MOVEMENT_SPEED -> 100.0D;
            case COOLDOWN_REDUCTION -> 0.0D;
        };
    }

    private double getBonusValue(@NotNull AstPlayer player, @NotNull StatusType type, double baseValue) {
        double nonBuffBonus = getAccountModeBonus(player.getAccount().getMode(), type);
        nonBuffBonus += getPermissionBonus(player.getUser().getPermission(), type);

        double preBuffTotal = baseValue + nonBuffBonus;
        double buffBonus = buffService.getTotalBonus(player, type, preBuffTotal);
        return nonBuffBonus + buffBonus;
    }

    private double getAccountModeBonus(@NotNull AccountMode mode, @NotNull StatusType type) {
        return switch (mode) {
            case PLAYER -> 0.0D;
            case BUILDER -> switch (type) {
                case MAX_HEALTH -> 0.0D;
                case MAX_MANA -> 4.0D;
                case MAX_ENERGY -> 0.0D;
                case STRENGTH -> 0.0D;
                case DEXTERITY -> 0.0D;
                case INTELLIGENCE -> 0.0D;
                case VITALITY -> 0.0D;
                case AGILITY -> 0.0D;
                case LUCK -> 0.0D;
                case ATTACK -> 2.0D;
                case MELEE_ATTACK -> 0.0D;
                case RANGED_ATTACK -> 0.0D;
                case MAGIC_ATTACK -> 0.0D;
                case CRITICAL_RATE -> 0.0D;
                case CRITICAL_DAMAGE -> 0.0D;
                case SUPER_CRITICAL_RATE -> 0.0D;
                case SUPER_CRITICAL_DAMAGE -> 0.0D;
                case FINAL_DAMAGE_RATE -> 0.0D;
                case FINAL_DAMAGE_MULTIPLIER -> 0.0D;
                case ACCURACY -> 0.0D;
                case ATTACK_SPEED -> 0.0D;
                case DEFENSE -> 3.0D;
                case MAGIC_DEFENSE -> 2.0D;
                case EVASION -> 0.0D;
                case HP_REGEN -> 0.0D;
                case MP_REGEN -> 0.0D;
                case ENERGY_REGEN -> 0.0D;
                case MOVEMENT_SPEED -> 5.0D;
                case COOLDOWN_REDUCTION -> 0.0D;
            };
            case ADMIN -> switch (type) {
                case MAX_HEALTH -> 10.0D;
                case MAX_MANA -> 10.0D;
                case MAX_ENERGY -> 50.0D;
                case STRENGTH -> 5.0D;
                case DEXTERITY -> 5.0D;
                case INTELLIGENCE -> 5.0D;
                case VITALITY -> 5.0D;
                case AGILITY -> 5.0D;
                case LUCK -> 5.0D;
                case ATTACK -> 6.0D;
                case MELEE_ATTACK -> 0.0D;
                case RANGED_ATTACK -> 0.0D;
                case MAGIC_ATTACK -> 0.0D;
                case CRITICAL_RATE -> 5.0D;
                case CRITICAL_DAMAGE -> 25.0D;
                case SUPER_CRITICAL_RATE -> 5.0D;
                case SUPER_CRITICAL_DAMAGE -> 20.0D;
                case FINAL_DAMAGE_RATE -> 5.0D;
                case FINAL_DAMAGE_MULTIPLIER -> 10.0D;
                case ACCURACY -> 5.0D;
                case ATTACK_SPEED -> 10.0D;
                case DEFENSE -> 6.0D;
                case MAGIC_DEFENSE -> 6.0D;
                case EVASION -> 5.0D;
                case HP_REGEN -> 2.0D;
                case MP_REGEN -> 2.0D;
                case ENERGY_REGEN -> 3.0D;
                case MOVEMENT_SPEED -> 10.0D;
                case COOLDOWN_REDUCTION -> 5.0D;
            };
        };
    }

    private double getPermissionBonus(int permission, @NotNull StatusType type) {
        if (permission >= AstPlayer.OP_PERMISSION_THRESHOLD) {
            return switch (type) {
                case MAX_HEALTH -> 5.0D;
                case MAX_MANA -> 5.0D;
                case MAX_ENERGY -> 20.0D;
                case STRENGTH -> 3.0D;
                case DEXTERITY -> 3.0D;
                case INTELLIGENCE -> 3.0D;
                case VITALITY -> 3.0D;
                case AGILITY -> 3.0D;
                case LUCK -> 3.0D;
                case ATTACK -> 4.0D;
                case MELEE_ATTACK -> 0.0D;
                case RANGED_ATTACK -> 0.0D;
                case MAGIC_ATTACK -> 0.0D;
                case CRITICAL_RATE -> 2.5D;
                case CRITICAL_DAMAGE -> 10.0D;
                case SUPER_CRITICAL_RATE -> 2.0D;
                case SUPER_CRITICAL_DAMAGE -> 10.0D;
                case FINAL_DAMAGE_RATE -> 2.0D;
                case FINAL_DAMAGE_MULTIPLIER -> 5.0D;
                case ACCURACY -> 2.0D;
                case ATTACK_SPEED -> 5.0D;
                case DEFENSE -> 4.0D;
                case MAGIC_DEFENSE -> 4.0D;
                case EVASION -> 2.0D;
                case HP_REGEN -> 1.0D;
                case MP_REGEN -> 1.0D;
                case ENERGY_REGEN -> 2.0D;
                case MOVEMENT_SPEED -> 0.0D;
                case COOLDOWN_REDUCTION -> 2.0D;
            };
        }

        if (permission >= 10) {
            return switch (type) {
                case MAX_HEALTH -> 0.0D;
                case MAX_MANA -> 2.0D;
                case MAX_ENERGY -> 10.0D;
                case STRENGTH -> 1.0D;
                case DEXTERITY -> 1.0D;
                case INTELLIGENCE -> 1.0D;
                case VITALITY -> 1.0D;
                case AGILITY -> 1.0D;
                case LUCK -> 1.0D;
                case ATTACK -> 1.0D;
                case MELEE_ATTACK -> 0.0D;
                case RANGED_ATTACK -> 0.0D;
                case MAGIC_ATTACK -> 0.0D;
                case CRITICAL_RATE -> 0.5D;
                case CRITICAL_DAMAGE -> 5.0D;
                case SUPER_CRITICAL_RATE -> 0.0D;
                case SUPER_CRITICAL_DAMAGE -> 0.0D;
                case FINAL_DAMAGE_RATE -> 0.0D;
                case FINAL_DAMAGE_MULTIPLIER -> 0.0D;
                case ACCURACY -> 1.0D;
                case ATTACK_SPEED -> 0.0D;
                case DEFENSE -> 1.0D;
                case MAGIC_DEFENSE -> 1.0D;
                case EVASION -> 0.5D;
                case HP_REGEN -> 0.0D;
                case MP_REGEN -> 0.0D;
                case ENERGY_REGEN -> 0.0D;
                case MOVEMENT_SPEED -> 0.0D;
                case COOLDOWN_REDUCTION -> 0.0D;
            };
        }

        return 0.0D;
    }
}
