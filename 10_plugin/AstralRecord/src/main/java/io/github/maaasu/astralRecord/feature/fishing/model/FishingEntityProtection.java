package io.github.maaasu.astralRecord.feature.fishing.model;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.jetbrains.annotations.NotNull;

/** 釣りセッションが所有する一時魚の識別と、バニラAIからの分離を扱います。 */
public final class FishingEntityProtection {
    public static final String TAG = "astralrecord_fishing_fish";

    private FishingEntityProtection() {
    }

    /**
     * spawn callback内で呼び、スポーンイベントより先に一時魚を識別します。
     * メインスレッド専用です。保存、自然消滅、AI、エンティティ同士の衝突を無効にします。
     * 釣り上げまでは無敵にし、釣り上げ後は戦闘登録側が無敵を解除して管理HPを使います。
     *
     * @param fish セッションが必ず終了時にremoveする魚
     */
    public static void mark(@NotNull Fish fish) {
        fish.addScoreboardTag(TAG);
        fish.setPersistent(false);
        fish.setRemoveWhenFarAway(false);
        fish.setAI(false);
        fish.setGravity(false);
        fish.setInvulnerable(true);
        fish.setCollidable(false);
        fish.setSilent(true);
        fish.setCanPickupItems(false);
    }

    /**
     * 釣り魚だけを通常Mobの掃除・報酬・ダメージ処理から識別します。
     *
     * @param entity 判定対象
     * @return 魚で、釣り専用の生成タグを持つ場合true
     */
    public static boolean isManaged(@NotNull Entity entity) {
        return entity instanceof Fish && entity.getScoreboardTags().contains(TAG);
    }
}
