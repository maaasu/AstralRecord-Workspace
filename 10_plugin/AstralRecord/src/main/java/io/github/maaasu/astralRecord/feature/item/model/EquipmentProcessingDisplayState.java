package io.github.maaasu.astralRecord.feature.item.model;

import org.jetbrains.annotations.NotNull;

/** 装備加工 GUI で現在プレイヤーへ表示する操作状態です。 */
public enum EquipmentProcessingDisplayState {
    REPAIR("修理", "修理モード"),
    ENHANCEMENT("強化", "強化モード"),
    TRANSCENDENCE("状態変化", "状態変化中");

    private final String displayName;
    private final String identityLabel;

    EquipmentProcessingDisplayState(
        @NotNull String displayName,
        @NotNull String identityLabel
    ) {
        this.displayName = displayName;
        this.identityLabel = identityLabel;
    }

    /**
     * プレイヤーへ表示する現在の操作名を返します。
     *
     * @return 修理、強化、または状態変化の表示名
     */
    public @NotNull String displayName() {
        return displayName;
    }

    /**
     * 画面上部の常時表示へ使う状態ラベルを返します。
     *
     * @return 現在の加工または状態変化中を示すラベル
     */
    public @NotNull String identityLabel() {
        return identityLabel;
    }

    /**
     * 選択中タブと次に実行する処理から、画面へ表示する状態を決定します。
     * 修理タブでは状態変化の候補があっても修理表示を優先します。
     *
     * @param processingMode 選択中の修理または強化タブ
     * @param transcendenceReady 次の実行が状態変化である場合は {@code true}
     * @return プレイヤーに常時表示する加工状態
     */
    public static @NotNull EquipmentProcessingDisplayState from(
        @NotNull EquipmentProcessingMode processingMode,
        boolean transcendenceReady
    ) {
        if (processingMode == EquipmentProcessingMode.REPAIR) {
            return REPAIR;
        }
        return transcendenceReady ? TRANSCENDENCE : ENHANCEMENT;
    }
}
