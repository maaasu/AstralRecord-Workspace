package io.github.maaasu.astralRecord.shared.gui;

import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GuiOpenSupportCancellationTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 終了した送信の保留遷移を取消し、同じ取消の再呼出でも通知は一度だけ、他人の遷移は維持する。
     */
    @Test
    void cancellationStopsOnlySelectedPendingTaskAndNotifiesOnce() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Runnable opened = mock(Runnable.class);
        Runnable cancelled = mock(Runnable.class);
        Runnable otherCancelled = mock(Runnable.class);
        BukkitTask task = mock(BukkitTask.class);
        Map<UUID, Object> pending = pendingTransitions();
        pending.put(sender, transition(opened, cancelled, task));
        pending.put(other, transition(mock(Runnable.class), otherCancelled, mock(BukkitTask.class)));
        try {
            GuiOpenSupport.cancelPending(sender);
            GuiOpenSupport.cancelPending(sender);

            assertFalse(pending.containsKey(sender));
            assertTrue(pending.containsKey(other));
            verify(task, times(1)).cancel();
            verify(cancelled, times(1)).run();
            verify(opened, never()).run();
            verify(otherCancelled, never()).run();
        } finally {
            pending.remove(sender);
            pending.remove(other);
        }
    }

    /** Bukkitのスケジュールを実行せず、遅延表示待ちのfixtureを組み立てます。 */
    private static Object transition(Runnable opened, Runnable cancelled, BukkitTask task) throws Exception {
        Class<?> type = Class.forName(GuiOpenSupport.class.getName() + "$PendingTransition");
        Constructor<?> constructor = type.getDeclaredConstructor(Runnable.class, Runnable.class);
        constructor.setAccessible(true);
        Object transition = constructor.newInstance(opened, cancelled);
        Method attach = type.getDeclaredMethod("attach", BukkitTask.class);
        attach.setAccessible(true);
        attach.invoke(transition, task);
        return transition;
    }

    /** テスト対象プレイヤーの保留遷移だけをfixtureとして登録します。 */
    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> pendingTransitions() throws Exception {
        Field field = GuiOpenSupport.class.getDeclaredField("PENDING_TRANSITIONS");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(null);
    }
}
