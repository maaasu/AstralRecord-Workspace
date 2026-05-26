package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * {@link AstPlayer} を発動主体として扱う {@link SkillCaster} 実装。
 * <p>
 * レベル概念はアカウントレベル相当を仮定し、現状は
 * {@link AstPlayer#getStatusSnapshot()} のスナップショット時点情報を参照する。
 */
public final class PlayerSkillCaster implements SkillCaster {

    private final AstPlayer player;

    /**
     * 発動主体を {@link AstPlayer} で初期化します。
     *
     * @param player 発動主体プレイヤー
     */
    public PlayerSkillCaster(@NotNull AstPlayer player) {
        this.player = player;
    }

    /**
     * ラップしている {@link AstPlayer} を返します。
     *
     * @return プレイヤー
     */
    @NotNull
    public AstPlayer player() {
        return player;
    }

    @Override
    @NotNull
    public UUID casterId() {
        return player.getBukkit().getUniqueId();
    }

    @Override
    public int level() {
        // クラス/プレイヤーレベルの実装が未確定（[[13_9.00-未決事項]]）のため、
        // 当面は要求レベル検証をスルーする扱いとする。
        return Integer.MAX_VALUE;
    }

    @Override
    @NotNull
    public StatusSnapshot statusSnapshot() {
        return player.getStatusSnapshot();
    }

    @Override
    public double currentMana() {
        return player.getStatusSnapshot().getCurrentMp();
    }

    @Override
    public void consumeMana(double amount) {
        if (amount <= 0.0) return;
        StatusSnapshot snapshot = player.getStatusSnapshot();
        player.setStatusSnapshot(snapshot.withCurrentValues(
                snapshot.getCurrentHp(),
                snapshot.getCurrentMp() - amount
        ));
    }

    @Override
    public void notify(@NotNull PlayerMsgId messageId, Object... args) {
        player.sendMessage(messageId, args == null ? new Object[0] : args);
    }
}
