package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class EquipmentDurabilityService {
    private static final double WEAPON_HIT_CONSUME_CHANCE = 0.50D;
    private static final double ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE = 0.80D;
    private static final double ACCESSORY_HIT_CONSUME_CHANCE = 0.20D;
    private static final double ACCESSORY_DAMAGE_TAKEN_CONSUME_CHANCE = 1.00D;

    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final ItemReferenceResolver itemReferenceResolver;
    private StatusService statusService;

    public EquipmentDurabilityService(
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService
    ) {
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
    }

    public void setStatusService(@Nullable StatusService statusService) {
        this.statusService = statusService;
    }

    public boolean canUseMainHandWeapon(@NotNull AstPlayer player) {
        ItemReference reference = inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND);
        if (reference == null || !reference.hasEquipmentInstanceId()) {
            return true;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        if (model == null || model.getEquipment() == null || model.getEquipment().getSlot() != ItemEquipmentSlot.WEAPON) {
            return true;
        }
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        return !isBroken(instance);
    }

    public void consumeOnAttackHit(@Nullable AstEntity attacker, @NotNull DamageResult result) {
        if (!isEffectiveHit(result) || attacker == null || !attacker.isPlayer() || attacker.player() == null) {
            return;
        }
        AstPlayer player = attacker.player();
        Set<String> consumed = new HashSet<>();
        consumeReference(
            player,
            inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND),
            equipment -> equipment.getSlot() == ItemEquipmentSlot.WEAPON,
            WEAPON_HIT_CONSUME_CHANCE,
            consumed
        );
        consumeAccessories(player, ACCESSORY_HIT_CONSUME_CHANCE, consumed);
    }

    public void consumeOnDamageTaken(@NotNull AstEntity victim, @NotNull DamageResult result) {
        if (!isEffectiveHit(result) || !victim.isPlayer() || victim.player() == null) {
            return;
        }
        AstPlayer player = victim.player();
        Set<String> consumed = new HashSet<>();
        PlayerInventory inventory = player.getBukkit().getInventory();
        Predicate<ItemEquipment> armorPredicate = equipment -> switch (equipment.getSlot()) {
            case HEAD, CHEST, LEGS, FEET -> true;
            default -> false;
        };
        consumeStack(player, inventory.getHelmet(), armorPredicate, ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
        consumeStack(player, inventory.getChestplate(), armorPredicate, ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
        consumeStack(player, inventory.getLeggings(), armorPredicate, ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
        consumeStack(player, inventory.getBoots(), armorPredicate, ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
        consumeAccessories(player, ACCESSORY_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
    }

    public boolean isBroken(@Nullable EquipmentInstance instance) {
        return instance != null && instance.getDurabilityMax() > 0 && instance.getDurabilityValue() <= 0;
    }

    private void consumeAccessories(
        @NotNull AstPlayer player,
        double chance,
        @NotNull Set<String> consumed
    ) {
        for (ItemStack itemStack : inventoryService.getEquippedAccessorySnapshotItems(player)) {
            consumeStack(player, itemStack, equipment -> equipment.getSlot() == ItemEquipmentSlot.ACCESSORY, chance, consumed);
        }
    }

    private void consumeStack(
        @NotNull AstPlayer player,
        @Nullable ItemStack itemStack,
        @NotNull Predicate<ItemEquipment> equipmentPredicate,
        double chance,
        @NotNull Set<String> consumed
    ) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return;
        }
        consumeReference(player, itemReferenceResolver.resolve(itemStack), equipmentPredicate, chance, consumed);
    }

    private void consumeReference(
        @NotNull AstPlayer player,
        @Nullable ItemReference reference,
        @NotNull Predicate<ItemEquipment> equipmentPredicate,
        double chance,
        @NotNull Set<String> consumed
    ) {
        if (reference == null
            || !reference.hasEquipmentInstanceId()
            || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT) {
            return;
        }
        if (!consumed.add(reference.equipmentInstanceId())) {
            return;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (model == null || model.getEquipment() == null || instance == null) {
            return;
        }
        if (!equipmentPredicate.test(model.getEquipment())) {
            return;
        }
        consumeDurability(player, model, instance, chance);
    }

    private void consumeDurability(
        @NotNull AstPlayer player,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        double chance
    ) {
        if (instance.getDurabilityMax() <= 0 || instance.getDurabilityValue() <= 0) {
            return;
        }
        if (chance < 1.0D && ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        int consumeAmount = model.getEquipment() == null || model.getEquipment().getDurability() == null
            ? 1
            : Math.max(1, model.getEquipment().getDurability().getConsume());
        int nextValue = Math.max(0, instance.getDurabilityValue() - consumeAmount);
        if (nextValue == instance.getDurabilityValue()) {
            return;
        }
        EquipmentInstance updated = itemService.updateEquipmentDurability(
            instance.getEquipmentInstanceId(),
            nextValue,
            player.getAccount().getUuid().toString()
        );
        if (updated == null) {
            return;
        }
        inventoryService.refreshEquipmentInstanceDisplay(player, updated);
        inventoryService.saveNow(player.getAccount().getUuid());
        if (nextValue <= 0 && statusService != null) {
            statusService.refreshStatus(player);
        }
    }

    private boolean isEffectiveHit(@NotNull DamageResult result) {
        return result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D;
    }
}
