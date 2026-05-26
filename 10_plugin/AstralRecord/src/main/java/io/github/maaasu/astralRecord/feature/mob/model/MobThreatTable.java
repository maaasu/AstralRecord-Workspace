package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob 単位の脅威値（ヘイト）を保持するテーブル。
 * プレイヤー UUID をキーとし、加算・減衰・最大値取得を提供する。
 */
public final class MobThreatTable {

    private final Map<UUID, Double> entries = new ConcurrentHashMap<>();

    /**
     * 指定プレイヤーの脅威値に加算します。
     *
     * @param playerId プレイヤー UUID
     * @param amount   加算量（負値も可）
     */
    public void add(@NotNull UUID playerId, double amount) {
        entries.merge(playerId, amount, Double::sum);
        if (entries.getOrDefault(playerId, 0.0) <= 0.0) {
            entries.remove(playerId);
        }
    }

    /**
     * 指定プレイヤーの脅威値を上書きします。
     *
     * @param playerId プレイヤー UUID
     * @param amount   新しい値（0 以下なら削除）
     */
    public void set(@NotNull UUID playerId, double amount) {
        if (amount <= 0.0) {
            entries.remove(playerId);
        } else {
            entries.put(playerId, amount);
        }
    }

    /**
     * 指定プレイヤーのエントリを削除します。
     *
     * @param playerId プレイヤー UUID
     */
    public void remove(@NotNull UUID playerId) {
        entries.remove(playerId);
    }

    /**
     * 最大脅威値を持つプレイヤー UUID を返します。
     *
     * @return 最大脅威値のプレイヤー UUID。空なら {@code null}
     */
    @Nullable
    public UUID top() {
        return entries.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * すべてのエントリに減衰係数を乗算します。1.0 未満の値となったエントリは削除します。
     *
     * @param factor 減衰係数（0.0 〜 1.0 にクランプ）
     */
    public void decay(double factor) {
        double clamped = Math.max(0.0, Math.min(1.0, factor));
        entries.replaceAll((id, value) -> value * clamped);
        entries.entrySet().removeIf(entry -> entry.getValue() < 1.0);
    }

    /**
     * テーブルが空かを返します。
     *
     * @return エントリが 1 件もなければ {@code true}
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 現在のエントリ集合を変更不可ビューで返します。
     *
     * @return プレイヤー UUID -> 脅威値 のマップ（変更不可）
     */
    @NotNull
    public Map<UUID, Double> snapshot() {
        return Collections.unmodifiableMap(entries);
    }
}
