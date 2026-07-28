package io.github.maaasu.astralRecord.feature.buff.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifierType;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.buff.repository.BuffRepository;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * バフ機能のビジネスロジックを担うサービスクラスです。
 *
 * 最小構成として、セッション中のアクティブバフを管理します。
 */
public class BuffService {

    private static final String TEMPORARY_FLAT_BUFF_ID_PREFIX = "temporary-flat:";
    private static final String TEMPORARY_FLAT_BUFF_TYPE = "ADMIN";
    private static final String TEMPORARY_FLAT_BUFF_DISPLAY_NAME = "管理者ステータスバフ";
    private static final long MAX_TEMPORARY_DURATION_SECONDS = Integer.MAX_VALUE / 20L;

    private final BuffRepository buffRepository;
    private final Map<String, BuffType> buffCache;

    public BuffService() {
        this.buffRepository = new BuffRepository();
        this.buffCache = new ConcurrentHashMap<>();
    }

    /**
     * バフを付与します。
     * 同一 buffId は重複保持せず、再付与時は時間を更新します。
     *
     * @param player 対象プレイヤー
     * @param buffId 付与するバフID
     * @return バフが取得できて付与できた場合 true
     */
    public boolean apply(@NotNull AstPlayer player, @NotNull String buffId) {
        BuffType type = getOrLoad(buffId);
        if (type == null) {
            return false;
        }

        purgeExpired(player);
        remove(player, buffId);

        LocalDateTime now = LocalDateTime.now();
        long durationSeconds = Math.max(0L, type.getDurationTicks() / 20L);
        LocalDateTime expiresAt = now.plusSeconds(durationSeconds);
        player.getActiveBuffs().add(new ActiveBuff(type, now, expiresAt));
        return true;
    }

    /**
     * 指定ステータスを固定値で上昇させる一時バフを付与します。
     * <p>
     * 同じ対象・同じステータスに対する一時バフは重複させず、値と失効時刻を更新します。
     *
     * @param player          対象プレイヤー
     * @param statusType      上昇させるステータス種別
     * @param value           上昇値（正の有限値）
     * @param durationSeconds 持続秒数（1〜{@value #MAX_TEMPORARY_DURATION_SECONDS}）
     * @return 付与したアクティブバフ
     * @throws IllegalArgumentException 値または持続秒数が有効範囲外の場合
     */
    public @NotNull ActiveBuff applyTemporaryFlat(
        @NotNull AstPlayer player,
        @NotNull StatusType statusType,
        double value,
        long durationSeconds
    ) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException("value must be a positive finite number");
        }
        if (durationSeconds <= 0L || durationSeconds > MAX_TEMPORARY_DURATION_SECONDS) {
            throw new IllegalArgumentException("durationSeconds is out of range");
        }

        String buffId = TEMPORARY_FLAT_BUFF_ID_PREFIX + statusType.getId();
        BuffType type = new BuffType(
            buffId,
            TEMPORARY_FLAT_BUFF_TYPE,
            TEMPORARY_FLAT_BUFF_DISPLAY_NAME,
            Math.toIntExact(durationSeconds * 20L),
            false,
            List.of(new BuffModifier(statusType, BuffModifierType.FLAT, value))
        );
        purgeExpired(player);
        remove(player, buffId);

        LocalDateTime now = LocalDateTime.now();
        ActiveBuff activeBuff = new ActiveBuff(type, now, now.plusSeconds(durationSeconds));
        player.getActiveBuffs().add(activeBuff);
        return activeBuff;
    }

    /**
     * 指定バフを解除します。
     *
     * @param player 対象プレイヤー
     * @param buffId 解除するバフID
     * @return 解除できた場合 true
     */
    public boolean remove(@NotNull AstPlayer player, @NotNull String buffId) {
        return player.getActiveBuffs().removeIf(buff -> buff.getType().getId().equals(buffId));
    }

    /**
     * 期限切れバフを削除します。
     *
     * @param player 対象プレイヤー
     * @return 削除件数
     */
    public int purgeExpired(@NotNull AstPlayer player) {
        LocalDateTime now = LocalDateTime.now();
        int before = player.getActiveBuffs().size();
        player.getActiveBuffs().removeIf(buff -> buff.isExpired(now));
        return before - player.getActiveBuffs().size();
    }

    /**
     * 指定ステータスに対するバフ補正合計を返します。
     *
     * @param player     対象プレイヤー
     * @param statusType 対象ステータス
     * @param baseValue  補正計算の基準値（SCALAR 用）
     * @return バフ補正の合計値
     */
    public double getTotalBonus(@NotNull AstPlayer player, @NotNull StatusType statusType, double baseValue) {
        purgeExpired(player);

        double flat = 0.0D;
        double scalar = 0.0D;
        for (ActiveBuff buff : player.getActiveBuffs()) {
            for (BuffModifier modifier : buff.getType().getModifiers()) {
                if (modifier.getStatus() != statusType) {
                    continue;
                }

                if (modifier.getType() == BuffModifierType.SCALAR) {
                    scalar += modifier.getValue();
                } else {
                    flat += modifier.getValue();
                }
            }
        }

        return flat + (baseValue * scalar);
    }

    /**
     * 現在有効なバフ一覧を返します。
     *
     * @param player 対象プレイヤー
     * @return 変更不可のバフ一覧
     */
    public @NotNull List<ActiveBuff> getActiveBuffs(@NotNull AstPlayer player) {
        purgeExpired(player);
        return List.copyOf(player.getActiveBuffs());
    }

    @Nullable
    private BuffType getOrLoad(@NotNull String buffId) {
        BuffType cached = buffCache.get(buffId);
        if (cached != null) {
            return cached;
        }

        BuffType loaded = buffRepository.findById(buffId);
        if (loaded != null) {
            buffCache.put(buffId, loaded);
            Logger.log(LogId.D_5451, loaded);
        }
        return loaded;
    }
}
