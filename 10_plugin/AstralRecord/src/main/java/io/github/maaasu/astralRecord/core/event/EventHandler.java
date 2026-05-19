package io.github.maaasu.astralRecord.core.event;

import org.bukkit.event.Listener;

/**
 * イベントハンドラのインターフェース
 * 必要に応じて実装クラスが独自の基底クラスを持つ
 */
public interface EventHandler extends Listener {


    /**
     * イベントハンドラの初期化処理
     */
    default void initialize() {
        // デフォルト実装（必要なら上書き）
    }

    /**
     * イベントハンドラのクリーンアップ処理
     */
    default void cleanup() {
        // デフォルト実装（必要なら上書き）
    }

    /**
     * イベントハンドラの名前を取得
     */
    default String getHandlerName() {
        return this.getClass().getSimpleName();
    }

    /**
     * このイベントハンドラが有効かどうか
     */
    default boolean isEnabled() {
        return true;
    }
}



