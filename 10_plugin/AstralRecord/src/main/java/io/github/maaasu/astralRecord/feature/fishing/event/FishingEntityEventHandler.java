package io.github.maaasu.astralRecord.feature.fishing.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingEntityProtection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.jetbrains.annotations.NotNull;

/** 釣りセッション以外のダメージ・捕獲・報酬による、一時魚への干渉を抑止します。 */
public final class FishingEntityEventHandler extends AbstractEventHandler {
    /**
     * 窒息・落下・他人・スキル等で魚が死亡しないようにします。所有者の命中は入力仲裁で別途処理します。
     * @param event ダメージイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(@NotNull EntityDamageEvent event) {
        if (FishingEntityProtection.isManaged(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * 一時魚をバケツのアイテムに変換することを禁止します。
     * @param event バケツ捕獲イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucket(@NotNull PlayerBucketEntityEvent event) {
        if (FishingEntityProtection.isManaged(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * 外部プラグイン等により死亡した場合にもバニラのドロップ・経験値を生成しません。
     * @param event 死亡イベント。釣果成立として扱わない
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(@NotNull EntityDeathEvent event) {
        if (FishingEntityProtection.isManaged(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }
}
