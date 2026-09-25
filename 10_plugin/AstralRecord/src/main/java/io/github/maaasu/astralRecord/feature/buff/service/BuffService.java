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
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
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
        this(new BuffRepository());
    }

    BuffService(@NotNull BuffRepository buffRepository) {
        this.buffRepository = buffRepository;
        this.buffCache = new ConcurrentHashMap<>();
    }

    /**
     * バフを付与します。
     * 同一 buffId は重複保持せず、再付与時は時間を更新します。
     *
     * @param player 対象プレイヤー
     * @param buffId 付与するバフID
     * @param durationIncreasePercent 付与前に確定したバフ持続時間増加率（%）
     * @return バフが取得できて付与できた場合 true
     */
    public boolean apply(@NotNull AstPlayer player, @NotNull String buffId, double durationIncreasePercent) {
        BuffType type = getOrLoad(buffId);
        if (type == null) {
            return false;
        }

        purgeExpired(player);
        removeOverlapping(player, type);

        LocalDateTime now = LocalDateTime.now();
        long durationTicks = type.isDebuff()
            ? Math.max(0L, type.getDurationTicks())
            : scaledDurationTicks(type.getDurationTicks(), durationIncreasePercent);
        LocalDateTime expiresAt = now.plus(durationTicks * 50L, ChronoUnit.MILLIS);
        player.getActiveBuffs().add(new ActiveBuff(type, now, expiresAt));
        return true;
    }

    /**
     * バフの基礎tick数へ持続時間増加率を適用し、tick単位で四捨五入します。
     *
     * @param baseTicks バフマスターの基礎持続tick数
     * @param increasePercent 付与前に確定した増加率（%）。負値と非有限値は0扱い
     * @return 0以上、int上限以下の持続tick数
     */
    static long scaledDurationTicks(int baseTicks, double increasePercent) {
        double increase = Double.isFinite(increasePercent) ? Math.max(0.0D, increasePercent) : 0.0D;
        double scaled = Math.max(0, baseTicks) * (1.0D + increase / 100.0D);
        return Math.min(Integer.MAX_VALUE, Math.round(scaled));
    }

    /**
     * 指定ステータスを固定値で補正する一時バフを付与します。
     * <p>
     * 同じ対象・同じステータスに対する一時バフは重複させず、値と失効時刻を更新します。
     *
     * @param player          対象プレイヤー
     * @param statusType      上昇させるステータス種別
     * @param value           補正値（0以外の有限値）
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
        return applyTemporaryFlat(
            player,
            TEMPORARY_FLAT_BUFF_ID_PREFIX + statusType.getId(),
            TEMPORARY_FLAT_BUFF_DISPLAY_NAME,
            statusType,
            value,
            durationSeconds,
            false
        );
    }

    /**
     * 指定IDで固定値のスキル用一時バフを付与します。挑戦開始時には解除します。
     * <p>
     * 同じIDのバフだけを置き換えるため、独立して寿命を管理するフィールド効果などで使用します。
     *
     * @param player 対象プレイヤー
     * @param buffId バフ識別子
     * @param displayName 表示名
     * @param statusType 補正するステータス種別
     * @param value 補正値（0以外の有限値）
     * @param durationSeconds 持続秒数
     * @return 付与したアクティブバフ
     * @throws IllegalArgumentException 引数が有効範囲外の場合
     */
    public @NotNull ActiveBuff applyTemporaryFlat(
        @NotNull AstPlayer player,
        @NotNull String buffId,
        @NotNull String displayName,
        @NotNull StatusType statusType,
        double value,
        long durationSeconds
    ) {
        return applyTemporaryFlat(player, buffId, displayName, statusType, value, durationSeconds, true);
    }

    /** 管理者バフとスキル由来バフの挑戦時解除区分を保持して生成します。 */
    private @NotNull ActiveBuff applyTemporaryFlat(
        @NotNull AstPlayer player, @NotNull String buffId, @NotNull String displayName,
        @NotNull StatusType statusType, double value, long durationSeconds, boolean resetOnChallenge
    ) {
        if (buffId.isBlank() || displayName.isBlank()) {
            throw new IllegalArgumentException("buffId and displayName must not be blank");
        }
        if (!Double.isFinite(value) || value == 0.0D) {
            throw new IllegalArgumentException("value must be a non-zero finite number");
        }
        if (durationSeconds <= 0L || durationSeconds > MAX_TEMPORARY_DURATION_SECONDS) {
            throw new IllegalArgumentException("durationSeconds is out of range");
        }
        BuffType type = new BuffType(
            buffId,
            TEMPORARY_FLAT_BUFF_TYPE,
            displayName,
            Math.toIntExact(durationSeconds * 20L),
            false,
            resetOnChallenge,
            null,
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
     * 挑戦開始時にリセット対象と期限切れのバフを解除します。
     * 期限切れだけを除去した場合も再計算対象としてtrueを返します。
     *
     * @param player 対象プレイヤー
     * @return 1件以上のバフを解除した場合 true
     */
    public boolean removeChallengeResettableBuffs(@NotNull AstPlayer player) {
        int expired = purgeExpired(player);
        boolean removed = player.getActiveBuffs().removeIf(buff -> buff.getType().getResetOnChallenge());
        return expired > 0 || removed;
    }

    /**
     * 現在付与中の指定バフ個体から持続時間を消費します。
     * <p>
     * 消費後の失効時刻が現在以前となる場合は、指定個体を解除します。
     *
     * @param player 対象プレイヤー
     * @param expected 消費対象として期待する現在のバフ個体
     * @param ticks 消費する正の tick 数
     * @return 指定個体を消費または解除できた場合 true。失効済みまたは別個体の場合 false
     * @throws IllegalArgumentException tick 数が正でない場合
     */
    public boolean consumeDuration(
        @NotNull AstPlayer player,
        @NotNull ActiveBuff expected,
        long ticks
    ) {
        if (ticks <= 0L) {
            throw new IllegalArgumentException("ticks must be positive");
        }

        purgeExpired(player);
        List<ActiveBuff> activeBuffs = player.getActiveBuffs();
        for (int index = 0; index < activeBuffs.size(); index++) {
            ActiveBuff current = activeBuffs.get(index);
            if (current != expected) {
                continue;
            }

            LocalDateTime expiresAt = current.getExpiresAt().minus(
                Math.multiplyExact(ticks, 50L),
                ChronoUnit.MILLIS
            );
            if (!expiresAt.isAfter(LocalDateTime.now())) {
                activeBuffs.remove(index);
            } else {
                activeBuffs.set(index, new ActiveBuff(current.getType(), current.getStartedAt(), expiresAt));
            }
            return true;
        }
        return false;
    }

    private boolean removeOverlapping(@NotNull AstPlayer player, @NotNull BuffType type) {
        return player.getActiveBuffs().removeIf(buff -> isOverlapping(buff.getType(), type));
    }

    private boolean isOverlapping(@NotNull BuffType existing, @NotNull BuffType incoming) {
        if (existing.getId().equals(incoming.getId())) {
            return true;
        }

        String existingGroup = normalizeStackGroup(existing.getStackGroup());
        String incomingGroup = normalizeStackGroup(incoming.getStackGroup());
        return existingGroup != null && existingGroup.equals(incomingGroup);
    }

    private @Nullable String normalizeStackGroup(@Nullable String stackGroup) {
        if (stackGroup == null || stackGroup.isBlank()) {
            return null;
        }
        return stackGroup.trim().toLowerCase(Locale.ROOT);
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

                switch (modifier.getType()) {
                    case FLAT -> flat += modifier.getValue();
                    case SCALAR -> scalar += modifier.getValue();
                    case FINAL_SCALAR -> { }
                }
            }
        }

        return flat + (baseValue * scalar);
    }

    /**
     * 他のバフと派生ステータスを合成した後に掛ける最終倍率を返します。
     * @param player 対象プレイヤー
     * @return ステータス別 FINAL_SCALAR 係数の積。指定のないステータスは含まない
     */
    public @NotNull Map<StatusType, Double> getFinalScalarFactors(@NotNull AstPlayer player) {
        purgeExpired(player);
        Map<StatusType, Double> factors = new EnumMap<>(StatusType.class);
        for (ActiveBuff buff : player.getActiveBuffs()) {
            for (BuffModifier modifier : buff.getType().getModifiers()) {
                if (modifier.getType() == BuffModifierType.FINAL_SCALAR) {
                    factors.merge(modifier.getStatus(), Math.max(0.0D, 1.0D + modifier.getValue()),
                            (previous, current) -> previous * current);
                }
            }
        }
        return factors;
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
