package io.github.maaasu.astralRecord.feature.fishing.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingEntityProtection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.jetbrains.annotations.NotNull;

/** 一時魚のバニラHP変更・バケツ捕獲・バニラ報酬を抑止し、管理HPの戦闘処理と分離します。 */
public final class FishingEntityEventHandler extends AbstractEventHandler {
    /**
     * 窒息・落下等でバニラHPが減ることを防ぎます。
     * 所有者の通常攻撃・スキル・ペットはDamageServiceの管理HPへ反映するため、この取消では妨げません。
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
