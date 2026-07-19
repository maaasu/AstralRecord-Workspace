package io.github.maaasu.astralRecord.feature.menu.model;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.List;
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
 * @param currencyBalances メニューアイコンへ表示する通貨残高
 */
public record PlayerGuiRenderContext(
    @NotNull AccountModel account,
    @NotNull StatusSnapshot statusSnapshot,
    int availableClassPoints,
    int availablePassivePoints,
    long goldAmount,
    long returnToBaseGoldCost,
    @NotNull PlayerEquipmentSnapshot equipment,
    @NotNull List<CurrencyDisplayEntry> currencyBalances
) {
    public PlayerGuiRenderContext {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(statusSnapshot, "statusSnapshot");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(currencyBalances, "currencyBalances");
        currencyBalances = List.copyOf(currencyBalances);
    }

    /**
     * 通貨一覧をまだ保持しない呼び出し元向けに、ゴールドだけを含むコンテキストを生成します。
     *
     * @param account 選択中アカウント
     * @param statusSnapshot ステータス計算結果
     * @param availableClassPoints 利用可能クラス・ポイント
     * @param availablePassivePoints 利用可能パッシブ・ポイント
     * @param goldAmount 所持ゴールド
     * @param returnToBaseGoldCost 拠点帰還に必要なゴールド
     * @param equipment 現在装備の表示スナップショット
     */
    public PlayerGuiRenderContext(
        @NotNull AccountModel account,
        @NotNull StatusSnapshot statusSnapshot,
        int availableClassPoints,
        int availablePassivePoints,
        long goldAmount,
        long returnToBaseGoldCost,
        @NotNull PlayerEquipmentSnapshot equipment
    ) {
        this(
            account,
            statusSnapshot,
            availableClassPoints,
            availablePassivePoints,
            goldAmount,
            returnToBaseGoldCost,
            equipment,
            List.of(new CurrencyDisplayEntry(
                io.github.maaasu.astralRecord.feature.item.service.ItemService.DEFAULT_CURRENCY_ITEM_ID,
                net.kyori.adventure.text.Component.text("ゴールド"),
                goldAmount
            ))
        );
    }
}
