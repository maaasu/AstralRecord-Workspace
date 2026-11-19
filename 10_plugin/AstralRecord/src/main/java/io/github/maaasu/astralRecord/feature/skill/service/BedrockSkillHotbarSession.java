package io.github.maaasu.astralRecord.feature.skill.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.ActionRingCooldownEntry;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 実インベントリの主手を維持したまま、本人に送るホットバーと選択位置を保持します。 */
final class BedrockSkillHotbarSession {
    static final int WEAPON_SLOT = SkillBindPreset.ACTION_RING_SLOT_COUNT;
    static final int CLICK_INTERVAL_TICKS = 3;
    private static final int TICKS_PER_SECOND = 20;
    private static final int MAX_COOLDOWN_COUNT = 64;
    private static final int MAX_PENDING_SLOT_INPUTS = 16;
    private static final int MAX_SLOT_INPUTS_PER_SECOND = 64;

    private final UUID accountId;
    private final UUID worldId;
    private final int actualWeaponSlot;
    private final String weaponId;
    private final @Nullable String equipmentInstanceId;
    private final List<String> bindings;
    private final List<ItemStack> skillIcons;
    private volatile List<ItemStack> projection;
    private int selectedSlot = WEAPON_SLOT;
    private boolean inventoryDismissalPending;
    private long slotInputWindowStart = System.nanoTime();
    private int slotInputsInWindow;
    private int pendingSlotInputs;
    private volatile boolean invalidSlotInput;

    BedrockSkillHotbarSession(UUID accountId, UUID worldId, int actualWeaponSlot, String weaponId,
                              @Nullable String equipmentInstanceId, List<String> bindings,
                              List<ItemStack> skillIcons, ItemStack weapon) {
        this.accountId = accountId;
        this.worldId = worldId;
        this.actualWeaponSlot = actualWeaponSlot;
        this.weaponId = weaponId;
        this.equipmentInstanceId = equipmentInstanceId;
        this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
        this.skillIcons = List.copyOf(skillIcons);
        List<ItemStack> initial = new ArrayList<>(skillIcons);
        initial.add(weapon.clone());
        projection = List.copyOf(initial);
    }

    boolean matches(AstPlayer player) {
        Player viewer = player.getBukkit();
        return accountId.equals(player.getAccount().getUuid()) && worldId.equals(viewer.getWorld().getUID())
            && SkillActionRingHoldService.matchesHeldWeapon(viewer, actualWeaponSlot, weaponId, equipmentInstanceId);
    }

    List<String> bindings() {
        return bindings;
    }

    int selectedSlot() {
        return selectedSlot;
    }

    void select(int slot) {
        if (slot >= 0 && slot <= WEAPON_SLOT) {
            selectedSlot = slot;
        }
    }

    synchronized boolean beginSlotInput() {
        if (invalidSlotInput) {
            return false;
        }
        long now = System.nanoTime();
        if (now - slotInputWindowStart >= TimeUnit.SECONDS.toNanos(1)) {
            slotInputWindowStart = now;
            slotInputsInWindow = 0;
        }
        if (pendingSlotInputs >= MAX_PENDING_SLOT_INPUTS || slotInputsInWindow >= MAX_SLOT_INPUTS_PER_SECOND) {
            invalidateSlotInput();
            return false;
        }
        pendingSlotInputs++;
        slotInputsInWindow++;
        return true;
    }

    synchronized void endSlotInput() {
        pendingSlotInputs--;
    }

    void invalidateSlotInput() {
        invalidSlotInput = true;
    }

    boolean hasInvalidSlotInput() {
        return invalidSlotInput;
    }

    boolean inventoryDismissalPending() {
        return inventoryDismissalPending;
    }

    void requestInventoryDismissal() {
        inventoryDismissalPending = true;
    }

    List<ItemStack> projection() {
        return projection;
    }

    boolean refresh(ItemStack currentWeapon, List<ActionRingCooldownEntry> cooldowns) {
        List<ItemStack> previous = projection;
        List<ItemStack> next = new ArrayList<>(skillIcons);
        for (ActionRingCooldownEntry cooldown : cooldowns) {
            int index = cooldown.index();
            int count = cooldownCount(cooldown.remainingTicks());
            if (count == 0) {
                continue;
            }
            ItemStack old = previous.get(index);
            if (old != skillIcons.get(index) && old.getAmount() == count) {
                next.set(index, old);
            } else {
                ItemStack clock = new ItemStack(Material.CLOCK, count);
                clock.editMeta(meta -> meta.displayName(SkillActionRingService.bedrockText(PlayerMsgId.P_5891, count)));
                next.set(index, clock);
            }
        }
        ItemStack weapon = previous.get(WEAPON_SLOT);
        next.add(weapon.equals(currentWeapon) ? weapon : currentWeapon.clone());
        if (previous.equals(next)) {
            return false;
        }
        projection = List.copyOf(next);
        return true;
    }

    static int cooldownCount(long remainingTicks) {
        if (remainingTicks <= 0) {
            return 0;
        }
        return (int) Math.min(MAX_COOLDOWN_COUNT, 1 + (remainingTicks - 1) / TICKS_PER_SECOND);
    }

    static boolean isClickCoolingDown(@Nullable Integer lastClickTick, int inputTick) {
        return lastClickTick != null && inputTick - lastClickTick < CLICK_INTERVAL_TICKS;
    }

    static void showHeldSlot(Player player, int slot) {
        var manager = ProtocolLibrary.getProtocolManager();
        PacketContainer packet = manager.createPacket(PacketType.Play.Server.HELD_ITEM_SLOT);
        packet.getIntegers().write(0, slot);
        manager.sendServerPacket(player, packet);
    }
}
