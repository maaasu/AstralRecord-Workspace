package io.github.maaasu.astralRecord.core.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 全イベントハンドラを一括管理・登録するマネージャー
 */
public class EventManager {

    private final AstralRecord plugin;
    private final List<EventHandler> handlers;

    public EventManager(AstralRecord plugin) {
        this.plugin = plugin;
        this.handlers = new ArrayList<>();
    }

    /**
     * 全イベントハンドラを登録
     */
    public void registerAllHandlers() {
        Logger.log(LogId.I_3050);

        PluginManager pm = plugin.getServer().getPluginManager();

        // ここに具体的なイベントハンドラの登録を追加
        // registerHandler(new PlayerQuitEventHandler(), pm);
        // registerHandler(new EntityDamageEventHandler(), pm);

        Logger.log(LogId.I_3051, handlers.size());
    }

    /**
     * 指定したイベントハンドラを一括登録します。
     * feature 層からハンドラを渡して登録する際に使用します。
     *
     * @param eventHandlers 登録するイベントハンドラ（可変長）
     */
    public void registerAllHandlers(EventHandler... eventHandlers) {
        Logger.log(LogId.I_3050);

        PluginManager pm = plugin.getServer().getPluginManager();
        for (EventHandler handler : eventHandlers) {
            registerHandler(handler, pm);
        }

        Logger.log(LogId.I_3051, handlers.size());
    }

    /**
     * イベントハンドラを登録
     * @param handler 登録するイベントハンドラ
     * @param pm プラグインマネージャー
     */
    public void registerHandler(EventHandler handler, PluginManager pm) {
        try {
            // 有効性チェック
            if (!handler.isEnabled()) {
                Logger.log(LogId.D_3001, handler.getHandlerName());
                return;
            }

            handler.initialize();
            pm.registerEvents(handler, plugin);
            handlers.add(handler);
            Logger.log(LogId.D_3000, handler.getHandlerName());
        } catch (Exception e) {
            Logger.log(LogId.E_3000, e, handler.getHandlerName());
        }
    }

    /**
     * 全イベントハンドラをクリーンアップ
     */
    public void unregisterAllHandlers() {
        Logger.log(LogId.I_3052);

        for (EventHandler handler : handlers) {
            try {
                handler.cleanup();
            } catch (Exception e) {
                Logger.log(LogId.E_3001, e, handler.getHandlerName());
            }
        }

        handlers.clear();
        Logger.log(LogId.I_3053);
    }

    /**
     * 登録されているイベントハンドラの数を取得
     * @return ハンドラ数
     */
    public int getHandlerCount() {
        return handlers.size();
    }

    /**
     * 登録されているイベントハンドラのリストを取得
     * @return ハンドラのコピーリスト
     */
    public List<EventHandler> getHandlers() {
        return new ArrayList<>(handlers);
    }
}


