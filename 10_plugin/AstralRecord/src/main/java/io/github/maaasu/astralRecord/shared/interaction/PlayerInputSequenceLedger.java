package io.github.maaasu.astralRecord.shared.interaction;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同一 tick 内の既知の重複配送だけを同じ論理入力へ相関し、勝者確定状態を保持します。
 * wall-clock の猶予時間は使用せず、次 tick の独立入力を抑止しません。
 */
public final class PlayerInputSequenceLedger {
    private final AtomicLong nextSequence = new AtomicLong();
    private final Map<UUID, List<Entry>> entriesByPlayer = new HashMap<>();

    /**
     * イベントを論理入力へ関連付けます。
     *
     * @param playerId プレイヤー UUID
     * @param serverTick 現在のサーバー tick
     * @param family 入力ファミリー
     * @param source イベント入口
     * @param handKey hand 識別子。hand を持たないイベントは空文字
     * @param targetKey イベントが直接示す対象。対象なしは空文字
     * @return 既存の相関入力または新規入力のトークン
     */
    public synchronized @NotNull PlayerInputToken correlate(
        @NotNull UUID playerId,
        int serverTick,
        @NotNull InputFamily family,
        @NotNull InputSource source,
        @NotNull String handKey,
        @NotNull String targetKey
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(handKey, "handKey");
        Objects.requireNonNull(targetKey, "targetKey");
        if (serverTick < 0) {
            throw new IllegalArgumentException("serverTick must be zero or greater");
        }

        List<Entry> entries = entriesByPlayer.computeIfAbsent(playerId, ignored -> new ArrayList<>());
        entries.removeIf(entry -> entry.token.serverTick() < serverTick - 1);
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (entry.token.serverTick() != serverTick || entry.token.family() != family) {
                continue;
            }
            if (isCorrelated(entry, source, handKey, targetKey)) {
                entry.sources.add(source);
                entry.hands.add(handKey);
                if (!targetKey.isEmpty()) {
                    entry.targets.add(targetKey);
                }
                return entry.token;
            }
        }

        PlayerInputToken token = new PlayerInputToken(
            playerId,
            serverTick,
            nextSequence.getAndIncrement(),
            family
        );
        entries.add(new Entry(token, source, handKey, targetKey));
        return token;
    }

    /**
     * トークンの入力が既に勝者を確定済みか判定します。
     *
     * @param token 入力トークン
     * @return 確定済みなら true
     */
    public synchronized boolean isClaimed(@NotNull PlayerInputToken token) {
        Entry entry = find(token);
        return entry != null && entry.claimed;
    }

    /**
     * トークンの勝者を確定済みとして記録します。
     *
     * @param token 入力トークン
     */
    public synchronized void claim(@NotNull PlayerInputToken token) {
        claim(token, false);
    }

    /**
     * トークンを確定済みにし、勝者が関連する元イベントのcancelを要求したか記録します。
     *
     * @param token 入力トークン
     * @param cancelRequested 勝者がcancelを要求した場合はtrue
     */
    public synchronized void claim(@NotNull PlayerInputToken token, boolean cancelRequested) {
        Entry entry = find(token);
        if (entry != null) {
            entry.claimed = true;
            entry.cancelRequested |= cancelRequested;
        }
    }

    /**
     * 確定した勝者が元イベントのcancelを要求したか返します。
     *
     * @param token 入力トークン
     * @return cancel要求が記録済みならtrue
     */
    public synchronized boolean isCancelRequested(@NotNull PlayerInputToken token) {
        Entry entry = find(Objects.requireNonNull(token, "token"));
        return entry != null && entry.cancelRequested;
    }

    /**
     * 指定した論理入力に semantic な入口を観測したことを記録します。
     * ARM_SWING fallback の二重実行防止に使用します。
     *
     * @param token 入力トークン
     */
    public synchronized void observeSemanticInput(@NotNull PlayerInputToken token) {
        Entry entry = find(Objects.requireNonNull(token, "token"));
        if (entry != null) {
            entry.semanticObserved = true;
        }
    }

    /**
     * 指定した論理入力に semantic な入口が観測済みか判定します。
     *
     * @param token 入力トークン
     * @return 観測済みなら true
     */
    public synchronized boolean hasSemanticInput(@NotNull PlayerInputToken token) {
        Entry entry = find(Objects.requireNonNull(token, "token"));
        return entry != null && entry.semanticObserved;
    }

    /**
     * プレイヤー退出時に相関状態を破棄します。
     *
     * @param playerId プレイヤー UUID
     */
    public synchronized void clear(@NotNull UUID playerId) {
        entriesByPlayer.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    private boolean isCorrelated(Entry entry, InputSource source, String handKey, String targetKey) {
        if (!entry.sources.contains(source)) {
            return targetKey.isEmpty() || entry.targets.isEmpty() || entry.targets.contains(targetKey);
        }
        if (!handKey.isEmpty() && !entry.hands.contains(handKey)) {
            return targetKey.isEmpty() || entry.targets.isEmpty() || entry.targets.contains(targetKey);
        }
        return false;
    }

    private @Nullable Entry find(PlayerInputToken token) {
        List<Entry> entries = entriesByPlayer.get(token.playerId());
        if (entries == null) {
            return null;
        }
        return entries.stream().filter(entry -> entry.token.equals(token)).findFirst().orElse(null);
    }

    private static final class Entry {
        private final PlayerInputToken token;
        private final List<InputSource> sources = new ArrayList<>();
        private final List<String> hands = new ArrayList<>();
        private final List<String> targets = new ArrayList<>();
        private boolean claimed;
        private boolean cancelRequested;
        private boolean semanticObserved;

        private Entry(PlayerInputToken token, InputSource source, String handKey, String targetKey) {
            this.token = token;
            sources.add(source);
            hands.add(handKey);
            if (!targetKey.isEmpty()) {
                targets.add(targetKey);
            }
        }
    }
}
