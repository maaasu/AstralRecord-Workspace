package io.github.maaasu.astralRecord.feature.inventory.service;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * インベントリ系クリックの高速連打を抑制するためのクールタイム管理。
 * <p>
 * 主目的は連打による entry の不整合や API への重複リクエストを防ぐことであり、
 * 既定 100ms 程度の短いクールタイムを採用してユーザビリティへの影響を最小限にしています。
 * Bukkit イベントは主にメインスレッドから呼び出されますが、
 * 念のため {@link ConcurrentHashMap} とアトミックな更新で実装しています。
 */
public final class InventoryClickGuard {
    /** クールタイム対象のクリック種別。 */
    public enum ClickAction {
        /** ホットバースロット直接クリック（取り外し・選択トグル含む）。 */
        HOTBAR_SLOT,
        /** GUI 表示中のホットバーショートカット切替。 */
        HOTBAR_SHORTCUT,
        /** Bukkit 防具スロットクリック。 */
        ARMOR_SLOT,
        /** 表示中インベントリ内のアイテムクリック（装備割り当て・ホットバー割当）。 */
        DISPLAYED_ITEM,
        /** 装備 GUI 上の装備スロットクリック。 */
        EQUIPMENT_GUI_SLOT,
        /** クラフトショートカットなどによる表示インベントリ種別切替。 */
        INVENTORY_SWITCH
    }

    /** 既定 100ms。秒 10 回までは通常クリックとして許容しつつ、連打マクロを抑止する。 */
    public static final long DEFAULT_COOLDOWN_MS = 100L;

    private final Map<UUID, Map<ClickAction, Long>> nextAllowedAt = new ConcurrentHashMap<>();

    /**
     * 既定のクールタイムでクリック実行権を取得します。
     *
     * @param accountId 対象アカウントID
     * @param action 実行しようとしているクリック種別
     * @return 取得できた場合 true（呼び出し元は処理を進めてよい）
     */
    public boolean tryAcquire(@NotNull UUID accountId, @NotNull ClickAction action) {
        return tryAcquire(accountId, action, DEFAULT_COOLDOWN_MS);
    }

    /**
     * 指定クールタイムでクリック実行権を取得します。
     *
     * @param accountId 対象アカウントID
     * @param action 実行しようとしているクリック種別
     * @param cooldownMs ミリ秒単位のクールタイム
     * @return 取得できた場合 true。クールタイム中の場合は false を返し、呼び出し元はクリックを無視してください
     */
    public boolean tryAcquire(@NotNull UUID accountId, @NotNull ClickAction action, long cooldownMs) {
        long now = System.currentTimeMillis();
        boolean[] acquired = {false};
        nextAllowedAt
            .computeIfAbsent(accountId, key -> new ConcurrentHashMap<>())
            .compute(action, (key, existing) -> {
                if (existing != null && now < existing) {
                    acquired[0] = false;
                    return existing;
                }
                acquired[0] = true;
                return now + cooldownMs;
            });
        return acquired[0];
    }

    /**
     * 指定アカウントのクールタイム情報を破棄します。
     * プレイヤーの退出時などに呼び出して不要なメモリ保持を避けてください。
     *
     * @param accountId 対象アカウントID
     */
    public void clear(@NotNull UUID accountId) {
        nextAllowedAt.remove(accountId);
    }
}
