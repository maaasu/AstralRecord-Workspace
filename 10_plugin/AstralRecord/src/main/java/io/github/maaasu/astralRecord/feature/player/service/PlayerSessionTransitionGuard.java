package io.github.maaasu.astralRecord.feature.player.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * プレイヤー単位で、セッションを解放し得る遷移を一つだけ許可します。
 * チャンネル移動とアカウント切替が同時に旧セッションを終了しないための共有ゲートです。
 */
public final class PlayerSessionTransitionGuard {
    private final ConcurrentHashMap<UUID, Transition> activeTransitions = new ConcurrentHashMap<>();
    private volatile @Nullable BiPredicate<UUID, Transition> additionalTransitionGuard;

    /**
     * セッション遷移の所有権を取得する前に実行する追加判定を設定します。
     * 判定が {@code false} の場合は所有権を取得しません。{@code null} は全遷移を許可します。
     * 判定は {@link #tryBegin(UUID, Transition)} の呼び出しスレッドで実行されます。
     *
     * @param guard 追加判定。解除する場合は {@code null}
     */
    public void setAdditionalTransitionGuard(@Nullable BiPredicate<UUID, Transition> guard) {
        additionalTransitionGuard = guard;
    }

    /**
     * 対象プレイヤーの遷移所有権を取得します。
     *
     * @param playerId 対象プレイヤー UUID
     * @param transition 開始する遷移
     * @return 他の遷移が進行中でなく、所有権を取得できた場合 {@code true}
     */
    public boolean tryBegin(@NotNull UUID playerId, @NotNull Transition transition) {
        BiPredicate<UUID, Transition> guard = additionalTransitionGuard;
        if (guard != null && !guard.test(playerId, transition)) {
            return false;
        }
        return activeTransitions.putIfAbsent(playerId, transition) == null;
    }

    /**
     * 対象プレイヤーで現在進行中の遷移を返します。
     *
     * @param playerId 対象プレイヤー UUID
     * @return 進行中の遷移。存在しない場合は {@code null}
     */
    public @Nullable Transition current(@NotNull UUID playerId) {
        return activeTransitions.get(playerId);
    }

    /**
     * 呼び出し元が所有する遷移だけを終了します。
     * 別処理が取得し直した所有権を、古い完了処理が解除することはありません。
     *
     * @param playerId 対象プレイヤー UUID
     * @param transition 終了する遷移
     */
    public void end(@NotNull UUID playerId, @NotNull Transition transition) {
        activeTransitions.remove(playerId, transition);
    }

    /** セッションを解放し得る遷移種別です。 */
    public enum Transition {
        ACCOUNT_SWITCH,
        CHANNEL_TRANSFER,
        /** 保存不能 state を破棄して同一 account を再ロードする復旧処理。 */
        PLAYER_STATE_RECOVERY,
    }
}
