package io.github.maaasu.astralRecord.feature.menu.model;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * プレイヤー依存 GUI を同一時点の値で描画するための不変コンテキストです。
 *
 * @param account 選択中アカウント
 * @param statusSnapshot ステータス計算結果
 * @param availableClassPoints 利用可能クラス・ポイント
 * @param availablePassivePoints 利用可能パッシブ・ポイント
 * @param goldAmount 所持ゴールド
 * @param returnToBaseGoldCost 拠点帰還に必要なゴールド
 * @param equipment 現在装備の表示スナップショット
 */
public record PlayerGuiRenderContext(
    @NotNull AccountModel account,
    @NotNull StatusSnapshot statusSnapshot,
    int availableClassPoints,
    int availablePassivePoints,
    long goldAmount,
    long returnToBaseGoldCost,
    @NotNull PlayerEquipmentSnapshot equipment
) {
    public PlayerGuiRenderContext {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(statusSnapshot, "statusSnapshot");
        Objects.requireNonNull(equipment, "equipment");
    }
}
