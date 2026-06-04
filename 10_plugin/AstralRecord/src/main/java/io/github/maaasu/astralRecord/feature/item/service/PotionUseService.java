package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.service.BuffAcquisitionDisplayService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumable;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableOnUse;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ポーションなどの消耗品を右クリック使用したときの効果適用を担当します。
 */
public final class PotionUseService {
    private static final String STATUS_HP = "hp";
    private static final String STATUS_HEALTH = "health";
    private static final String STATUS_MAX_HEALTH = "max_health";
    private static final String STATUS_MP = "mp";
    private static final String STATUS_MANA = "mana";
    private static final String STATUS_MAX_MANA = "max_mana";
    private static final String STATUS_EN = "en";
    private static final String STATUS_ENERGY = "energy";
    private static final String STATUS_MAX_ENERGY = "max_energy";

    private final InventoryService inventoryService;
    private final StatusService statusService;
    private final BuffAcquisitionDisplayService buffDisplayService;
    private final ParticleDisplayService particleDisplayService = new ParticleDisplayService();

    /**
     * サービスを生成します。
     *
     * @param inventoryService 消費元ホットバー更新サービス
     * @param statusService ステータス・バフ適用サービス
     * @param buffDisplayService バフ獲得通知サービス
     */
    public PotionUseService(
        @NotNull InventoryService inventoryService,
        @NotNull StatusService statusService,
        @NotNull BuffAcquisitionDisplayService buffDisplayService
    ) {
        this.inventoryService = inventoryService;
        this.statusService = statusService;
        this.buffDisplayService = buffDisplayService;
    }

    /**
     * 消耗品を使用し、効果適用後にアイテムを消費します。
     *
     * @param astPlayer 使用プレイヤー
     * @param hand      使用した手
     * @param model     使用アイテム定義
     * @return 何らかの効果が適用され、アイテム消費まで成功した場合 true
     */
    public boolean use(@NotNull AstPlayer astPlayer, @NotNull EquipmentSlot hand, @NotNull ItemModel model) {
        ItemConsumable consumable = model.getConsumable();
        if (consumable == null || consumable.getEffects().isEmpty()) {
            return false;
        }

        boolean applied = false;
        for (ItemConsumableEffect effect : consumable.getEffects()) {
            if (!passesRate(effect.getRate())) {
                continue;
            }
            applied |= applyEffect(astPlayer, effect);
        }

        if (!applied) {
            return false;
        }

        int consumeAmount = consumable.getOnUse() == null ? 1 : Math.max(1, consumable.getOnUse().getAmount());
        if (!inventoryService.consumeHotbarItemInHand(astPlayer, hand, model.getId(), consumeAmount)) {
            astPlayer.sendMessage(PlayerMsgId.P_5245);
            return false;
        }

        playOnUse(astPlayer, consumable.getOnUse());
        return true;
    }

    private boolean applyEffect(@NotNull AstPlayer astPlayer, @NotNull ItemConsumableEffect effect) {
        if (effect.getType() == ItemConsumableEffectType.BUFF) {
            return applyBuff(astPlayer, effect.getBuffId());
        }
        if (effect.getType() == ItemConsumableEffectType.RECOVER) {
            return applyRecover(astPlayer, effect);
        }
        return false;
    }

    private boolean applyBuff(@NotNull AstPlayer astPlayer, @Nullable String buffId) {
        if (buffId == null || buffId.isBlank()) {
            Logger.log(LogId.W_5204, "consumable");
            return false;
        }
        statusService.applyBuff(astPlayer, buffId);
        ActiveBuff activeBuff = statusService.getActiveBuffs(astPlayer).stream()
            .filter(buff -> buff.getType().getId().equalsIgnoreCase(buffId))
            .findFirst()
            .orElse(null);
        if (activeBuff == null) {
            return false;
        }
        buffDisplayService.show(astPlayer.getBukkit(), activeBuff);
        return true;
    }

    private boolean applyRecover(@NotNull AstPlayer astPlayer, @NotNull ItemConsumableEffect effect) {
        double value = effect.getValue() == null ? 0.0D : effect.getValue();
        if (value <= 0.0D) {
            return false;
        }

        String status = normalizeStatus(effect.getStatus());
        StatusSnapshot snapshot = statusService.getStatus(astPlayer);
        if (STATUS_HP.equals(status) || STATUS_HEALTH.equals(status) || STATUS_MAX_HEALTH.equals(status)) {
            statusService.recoverHp(astPlayer, resolveRecoverAmount(value, effect.isPercent(), snapshot, StatusType.MAX_HEALTH));
            return true;
        }
        if (STATUS_MP.equals(status) || STATUS_MANA.equals(status) || STATUS_MAX_MANA.equals(status)) {
            statusService.recoverMp(astPlayer, resolveRecoverAmount(value, effect.isPercent(), snapshot, StatusType.MAX_MANA));
            return true;
        }
        if (STATUS_EN.equals(status) || STATUS_ENERGY.equals(status) || STATUS_MAX_ENERGY.equals(status)) {
            statusService.recoverEnergy(astPlayer, resolveRecoverAmount(value, effect.isPercent(), snapshot, StatusType.MAX_ENERGY));
            return true;
        }
        return false;
    }

    private double resolveRecoverAmount(
        double value,
        boolean percent,
        @NotNull StatusSnapshot snapshot,
        @NotNull StatusType maxType
    ) {
        return percent ? snapshot.getMaxValue(maxType) * (value / 100.0D) : value;
    }

    private boolean passesRate(double rate) {
        if (rate >= 100.0D) {
            return true;
        }
        if (rate <= 0.0D) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble(100.0D) < rate;
    }

    private void playOnUse(@NotNull AstPlayer astPlayer, @Nullable ItemConsumableOnUse onUse) {
        if (onUse == null) {
            return;
        }

        Location location = astPlayer.getBukkit().getLocation();
        World world = astPlayer.getBukkit().getWorld();
        if (world == null) {
            return;
        }
        if (onUse.getSound() != null && !onUse.getSound().isBlank()) {
            astPlayer.getBukkit().playSound(location, onUse.getSound(), 1.0F, 1.0F);
        } else {
            astPlayer.getBukkit().playSound(location, Sound.ENTITY_GENERIC_DRINK, 0.8F, 1.15F);
        }
        if (onUse.getEffect() != null && !onUse.getEffect().isBlank()) {
            Particle particle = parseParticle(onUse.getEffect());
            if (particle != null) {
                particleDisplayService.spawnWorld(
                    astPlayer,
                    world,
                    location.clone().add(0.0D, 1.0D, 0.0D),
                    particle,
                    16,
                    0.35D,
                    0.45D,
                    0.35D,
                    0.02D
                );
            }
        }
    }

    private @NotNull String normalizeStatus(@Nullable String raw) {
        return raw == null ? "" : raw.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private @Nullable Particle parseParticle(@NotNull String raw) {
        return SharedParticleDefinitions.resolveParticle(raw);
    }
}
