package io.github.maaasu.astralRecord.core.event;

import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;

/**
 * 基本的なイベントハンドラの抽象実装
 * 共通処理を提供するが、必須ではない（インターフェースを直接実装してもOK）
 */
public abstract class AbstractEventHandler implements EventHandler {

    protected final String handlerName;

    protected AbstractEventHandler() {
        this.handlerName = this.getClass().getSimpleName();
    }

    @Override
    public void initialize() {
        Logger.log(LogId.I_3000, handlerName);
    }

    @Override
    public void cleanup() {
        Logger.log(LogId.I_3001, handlerName);
    }

    @Override
    public String getHandlerName() {
        return handlerName;
    }

    /**
     * イベント処理を安全に実行し、例外時は指定の LogId で記録します。
     */
    protected void runSafely(Runnable action, LogId errorLogId, Object... logArgs) {
        try {
            action.run();
        } catch (Exception e) {
            Logger.log(errorLogId, e, logArgs);
        }
    }
}





