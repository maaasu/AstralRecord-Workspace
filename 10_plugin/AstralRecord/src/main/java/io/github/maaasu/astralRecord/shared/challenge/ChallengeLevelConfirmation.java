package io.github.maaasu.astralRecord.shared.challenge;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** ダンジョンとボスで共通の推奨レベル確認をメインスレッド上で管理します。 */
public final class ChallengeLevelConfirmation {
    private final ChallengeLevelConfirmationGui gui;
    private final Map<UUID, Prompt> pendingByPlayer = new HashMap<>();

    /**
     * @param gui 確認画面を表示するビュー
     */
    public ChallengeLevelConfirmation(@NotNull ChallengeLevelConfirmationGui gui) {
        this.gui = gui;
    }

    /**
     * 確認が必要なら本人の返答まで受付を保留します。未設定または推奨以上なら表示しません。
     *
     * @param player 判定対象のオンラインプレイヤー
     * @param attemptId 既存の挑戦ID。新規受付の表示ではnull
     * @param displayName ダンジョンまたはボスの表示名
     * @param recommendedLevel 推奨プレイヤーレベル。未設定の場合はnull
     * @param onConfirm 本人が続行を選んだときの処理
     * @param onCancel 本人が画面で取り消したときの処理
     * @return 確認のためこの呼び出しで受付を進められない場合はtrue
     */
    public boolean deferIfNeeded(
            @NotNull AstPlayer player,
            @Nullable UUID attemptId,
            @NotNull String displayName,
            @Nullable Integer recommendedLevel,
            @NotNull Runnable onConfirm,
            @NotNull Runnable onCancel
    ) {
        Warning warning = findWarning(player.getAccount(), recommendedLevel);
        if (warning == null) {
            return false;
        }
        UUID playerId = player.getBukkit().getUniqueId();
        if (pendingByPlayer.containsKey(playerId)) {
            return true;
        }
        Prompt prompt = new Prompt(attemptId);
        pendingByPlayer.put(playerId, prompt);
        boolean shown = gui.show(player, displayName, recommendedLevel, warning.level(), warning.rebirth(), response -> {
            if (pendingByPlayer.get(playerId) != prompt) {
                return;
            }
            pendingByPlayer.remove(playerId);
            switch (response) {
                case CONFIRMED -> onConfirm.run();
                case DECLINED -> onCancel.run();
                case UNAVAILABLE -> { }
            }
        });
        if (!shown && pendingByPlayer.get(playerId) == prompt) {
            pendingByPlayer.remove(playerId);
        }
        return true;
    }

    /**
     * @param player 判定対象
     * @param recommendedLevel 推奨レベル。未設定の場合はnull
     * @return 推奨レベルが設定されており、判定に使うプレイヤーレベルが推奨未満ならtrue
     */
    public static boolean requiresConfirmation(@NotNull AstPlayer player, @Nullable Integer recommendedLevel) {
        return findWarning(player.getAccount(), recommendedLevel) != null;
    }

    /** 警告で表示する判定レベルを、現在のアカウント状態から取得します。 */
    static @Nullable Warning findWarning(@NotNull AccountModel account, @Nullable Integer recommendedLevel) {
        if (recommendedLevel == null) {
            return null;
        }
        Integer originalLevel = account.getRebirthOriginalLevel();
        int comparisonLevel = originalLevel == null ? account.getLevel() : originalLevel;
        return comparisonLevel < recommendedLevel ? new Warning(comparisonLevel, originalLevel != null) : null;
    }

    /**
     * 挑戦が終了したとき、その挑戦の確認画面を応答を実行せず閉じます。
     *
     * @param attemptId 終了した挑戦ID
     */
    public void dismiss(@NotNull UUID attemptId) {
        for (UUID playerId : pendingByPlayer.keySet().toArray(UUID[]::new)) {
            dismiss(attemptId, playerId);
        }
    }

    /**
     * パーティーから外れた参加者の確認を取り消します。
     *
     * @param attemptId 対象の挑戦ID
     * @param playerId 対象のプレイヤーUUID
     */
    public void dismiss(@NotNull UUID attemptId, @NotNull UUID playerId) {
        Prompt prompt = pendingByPlayer.get(playerId);
        if (prompt != null && attemptId.equals(prompt.attemptId())) {
            pendingByPlayer.remove(playerId);
            gui.dismiss(playerId);
        }
    }

    /** プラグイン停止時に未確定の受付と画面を破棄します。 */
    public void closeAll() {
        UUID[] viewers = pendingByPlayer.keySet().toArray(UUID[]::new);
        pendingByPlayer.clear();
        for (UUID viewer : viewers) {
            gui.dismiss(viewer);
        }
    }

    private record Prompt(@Nullable UUID attemptId) {}

    record Warning(int level, boolean rebirth) {}
}
