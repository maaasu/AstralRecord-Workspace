package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * スキルの発動主体（プレイヤー / Mob）を共通契約として扱うための抽象。
 * <p>
 * {@link io.github.maaasu.astralRecord.feature.skill.service.SkillService} は
 * 共通検証（要求レベル・MP・クールダウン）と共通副作用（MP 消費・通知）を
 * この契約越しに行う。実装側は発動主体の種別に応じて適切な振る舞いを提供する。
 */
public interface SkillCaster {

    /**
     * クールダウン管理や識別に用いる発動者一意 ID を返します。
     *
     * @return 発動者一意 ID
     */
    @NotNull
    UUID casterId();

    /**
     * 発動者の現在レベル。レベル概念を持たない発動者は {@link Integer#MAX_VALUE} を返し
     * 共通レベル検証をスルーします。
     *
     * @return 発動者レベル
     */
    int level();

    /**
     * 現在ステータススナップショット。レベル・MP・各種能力値の参照元として使用します。
     *
     * @return 現在のステータススナップショット
     */
    @NotNull
    StatusSnapshot statusSnapshot();

    /**
     * 現在 MP を取得します。
     *
     * @return 現在 MP
     */
    double currentMana();

    /**
     * 現在 ENG を返します。
     * ENG を扱わない発動主体は {@code 0.0} を返して構いません。
     *
     * @return 現在 ENG
     */
    default double currentEnergy() {
        return 0.0D;
    }

    /**
     * MP を消費します。
     * 上限・下限のクランプは呼び出し先の責務とします。
     *
     * @param amount 消費量
     */
    void consumeMana(double amount);

    /**
     * ENG を消費します。
     * ENG を扱わない発動主体は no-op で構いません。
     *
     * @param amount 消費量
     */
    default void consumeEnergy(double amount) {
        // no-op
    }

    /**
     * 発動者向け通知メッセージを送信します。
     * 通知概念を持たない発動者（Mob 等）は no-op で構いません。
     *
     * @param messageId メッセージ ID
     * @param args      メッセージ引数
     */
    void notify(@NotNull PlayerMsgId messageId, Object... args);
}
