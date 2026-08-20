package io.github.maaasu.astralRecord.feature.combat.event;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BukkitDamageBlockEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-イベント.md
     * 章・見出し: # 14_3-イベント > ## 2. Bukkit ダメージ抑止
     * 検証契約: Bukkit 経由のエンティティ間ダメージは、対象・原因・事前キャンセル状態にかかわらず damage を0にしてキャンセルする。
     */
    @Test
    void cancelsEveryBukkitDamageEvent() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Entity entity = mock(Entity.class);
        when(event.getEntity()).thenReturn(entity);

        new BukkitDamageBlockEventHandler().onEntityDamage(event);

        verify(event).setDamage(0.0D);
        verify(event).setCancelled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-イベント.md
     * 章・見出し: # 14_3-イベント > ## 2. Bukkit ダメージ抑止
     * 検証契約: Bukkit ダメージ抑止 handler は、事前キャンセル済み event も HIGHEST で受け取る登録設定を維持する。
     */
    @Test
    void receivesAlreadyCancelledEventsAtHighestPriority() throws NoSuchMethodException {
        EventHandler annotation = BukkitDamageBlockEventHandler.class
                .getDeclaredMethod("onEntityDamage", org.bukkit.event.entity.EntityDamageEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, annotation.priority());
        assertFalse(annotation.ignoreCancelled());
    }
}
