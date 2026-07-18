package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentDurabilityService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * スキル発動前のアクションリング表示と選択状態を管理します。
 */
public final class SkillActionRingService {
    private static final int SLOT_COUNT = SkillBindPreset.SLOT_COUNT;
    private static final double RING_DISTANCE = 3.0D;
    private static final double RING_RADIUS = 1.12D;
    private static final int CIRCLE_DISPLAY_POINTS = 24;
    private static final int TIMER_BAR_LENGTH = 24;
    private static final int COOLDOWN_BAR_LENGTH = 10;
    private static final long UPDATE_INTERVAL_TICKS = 1L;
    private static final long RING_DISPLAY_LIMIT_TICKS = 100L;
    private static final long CAST_WAIT_LIMIT_TICKS = 60L;
    private static final long SELECT_ANIMATION_TICKS = 4L;
    private static final double SELECTING_BLOCK_BREAK_SPEED = 1024.0D;
    private static final int CLOSE_SELECTION_INDEX = -2;
    private static final double CLOSE_SELECTION_PROJECTED_LENGTH = 0.28D;
    private static final ItemStack HIDDEN_ITEM = new ItemStack(Material.AIR);

    private final AstralRecord plugin;
    private final SkillBindPresetService presetService;
    private final SkillService skillService;
    private final SkillOwnershipService ownershipService;
    private final SkillActionRingDisplay actionRingDisplay;
    private final Map<UUID, RingSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> suppressedAttackPlayers = ConcurrentHashMap.newKeySet();
    private EquipmentDurabilityService equipmentDurabilityService;
    private BukkitTask task;

    /**
     * サービスを生成します。
     *
     * @param plugin scheduler とエンティティ生成に使用するプラグイン
     * @param presetService スキルバインド数の取得に使用するサービス
     */
    public SkillActionRingService(
        @NotNull AstralRecord plugin,
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillService skillService,
        @NotNull SkillOwnershipService ownershipService
    ) {
        this.plugin = plugin;
        this.presetService = presetService;
        this.skillService = skillService;
        this.ownershipService = ownershipService;
        this.actionRingDisplay = new SkillActionRingDisplay(plugin);
    }

    public void setEquipmentDurabilityService(@Nullable EquipmentDurabilityService equipmentDurabilityService) {
        this.equipmentDurabilityService = equipmentDurabilityService;
    }

    /**
     * プレイヤーのアクションリング表示状態を切り替えます。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void toggle(@NotNull AstPlayer astPlayer) {
        var player = astPlayer.getBukkit();
        var playerId = player.getUniqueId();
        var current = sessions.remove(playerId);
        if (current != null) {
            current.destroy();
            GuiSound.CLOSE.play(player);
            return;
        }
        if (equipmentDurabilityService != null && !equipmentDurabilityService.canUseMainHandWeapon(astPlayer)) {
            GuiSound.DENY.play(player);
            return;
        }

        PlayerSkillCaster caster = new PlayerSkillCaster(astPlayer);
        RingSession session = RingSession.create(
            player,
            resolveSlots(astPlayer, caster),
            actionRingDisplay,
            skillService,
            caster
        );
        sessions.put(playerId, session);
        GuiSound.RING_OPEN.play(player);
        ensureTask();
    }

    /**
     * プレイヤーがアクションリング表示中かを返します。
     *
     * @param player 対象プレイヤー
     * @return 表示中なら true
     */
    public boolean isOpen(@NotNull Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /**
     * アクションリングで消費した左クリックから通常攻撃が派生しないよう、次 tick まで攻撃入力を抑止します。
     *
     * @param player 対象プレイヤー
     */
    public void suppressAttack(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        suppressedAttackPlayers.add(playerId);
        plugin.getServer().getScheduler().runTask(plugin, () -> suppressedAttackPlayers.remove(playerId));
    }

    /**
     * 直前のアクションリング操作により通常攻撃を抑止中か判定します。
     *
     * @param player 対象プレイヤー
     * @return 抑止中の場合 true
     */
    public boolean isAttackSuppressed(@NotNull Player player) {
        return suppressedAttackPlayers.contains(player.getUniqueId());
    }

    /**
     * 表示中の選択をデバッグ発動として通知し、リングを閉じます。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void activateSelected(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        RingSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!session.canActivateSelected()) {
            GuiSound.DENY.play(player);
            return;
        }
        if (session.isCloseSelected()) {
            sessions.remove(player.getUniqueId());
            session.destroy();
            GuiSound.CLOSE.play(player);
            return;
        }
        if (!session.hasConfirmedSelection()) {
            session.confirmSelection();
            GuiSound.RING_SELECT.play(player);
            return;
        }

        sessions.remove(player.getUniqueId());
        String skillId = session.selectedSkillId();
        int selectedSlot = session.selectedIndex + 1;
        session.destroy();
        String skillDisplayName = "未設定";
        if (skillId != null && !skillId.isBlank()) {
            SkillDefinition definition = skillService.registry().getDefinition(skillId);
            skillDisplayName = SkillPresentationUtil.plainName(definition, "未定義スキル");
            skillService.castSkill(
                new PlayerSkillCaster(astPlayer),
                skillId,
                SkillCastTrigger.PLAYER_COMMAND,
                player.getEyeLocation(),
                null,
                List.of()
            );
        }
        GuiSound.RING_CAST.play(player);
        PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5807, SLOT_COUNT, selectedSlot, skillDisplayName);
    }

    /**
     * 指定プレイヤーのリングを閉じます。
     *
     * @param player 対象プレイヤー
     */
    public void close(@NotNull Player player) {
        RingSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.destroy();
        }
    }

    /**
     * すべてのリング表示を破棄し、更新タスクを停止します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (RingSession session : sessions.values()) {
            session.destroy();
        }
        sessions.clear();
        suppressedAttackPlayers.clear();
    }

    private void ensureTask() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, UPDATE_INTERVAL_TICKS);
    }

    private void tick() {
        for (Map.Entry<UUID, RingSession> entry : sessions.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().destroy();
                sessions.remove(entry.getKey());
                continue;
            }
            if (!entry.getValue().tick(player)) {
                entry.getValue().destroy();
                sessions.remove(entry.getKey());
            }
        }
        if (sessions.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private @NotNull List<SlotView> resolveSlots(@NotNull AstPlayer astPlayer, @NotNull PlayerSkillCaster caster) {
        List<SlotView> slots = new ArrayList<>(SLOT_COUNT);
        UUID accountId = astPlayer.getAccount().getUuid();
        int selectedPresetIndex = presetService.selectedPresetIndex(accountId);
        List<String> activeSlots = presetService.getPresets(accountId).stream()
            .filter(preset -> preset.isUnlocked() && preset.getPresetIndex() == selectedPresetIndex)
            .findFirst()
            .map(SkillBindPreset::getActiveSkillSlots)
            .orElse(List.of());
        Set<String> ownedSkillIds = ownershipService.ownedSkillIds(astPlayer);

        for (int index = 0; index < SLOT_COUNT; index++) {
            String skillId = index < activeSlots.size() ? activeSlots.get(index) : null;
            if (skillId == null || skillId.isBlank()) {
                slots.add(new SlotView(null, null, "未設定", Material.BARRIER, false, SlotAvailability.UNAVAILABLE));
                continue;
            }
            SkillDefinition definition = skillService.registry().getDefinition(skillId);
            if (definition != null && definition.getKind() != SkillKind.ACTIVE) {
                slots.add(new SlotView(skillId, definition, "設定不可", Material.BARRIER, false, SlotAvailability.UNAVAILABLE));
                continue;
            }
            String displayName = definition == null
                    ? "未定義スキル"
                    : SkillPresentationUtil.legacyName(definition, "未定義スキル");
            boolean owned = ownedSkillIds.contains(skillId);
            Material material = owned ? parseMaterial(definition == null ? null : definition.getIcon(), Material.BARRIER) : Material.BARRIER;
            SlotAvailability availability = definition == null || !owned
                ? SlotAvailability.UNAVAILABLE
                : availabilityFor(skillService.canCast(caster, definition));
            slots.add(new SlotView(skillId, definition, displayName, material, owned, availability));
        }
        return slots;
    }

    private static @NotNull SlotAvailability availabilityFor(@NotNull SkillCastResult result) {
        if (result.success()) {
            return SlotAvailability.AVAILABLE;
        }
        PlayerMsgId messageId = result.messageId();
        if (messageId == PlayerMsgId.P_5802) {
            return SlotAvailability.COOLDOWN;
        }
        if (messageId == PlayerMsgId.P_5801) {
            return SlotAvailability.MANA;
        }
        if (messageId == PlayerMsgId.P_5806) {
            return SlotAvailability.ENERGY;
        }
        return SlotAvailability.BLOCKED;
    }

    private @NotNull Material parseMaterial(String value, @NotNull Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(value.trim());
        return material == null ? fallback : material;
    }

    private record SlotView(
        String skillId,
        SkillDefinition definition,
        @NotNull String name,
        @NotNull Material material,
        boolean owned,
        @NotNull SlotAvailability availability
    ) {
        private boolean selectable() {
            return availability == SlotAvailability.AVAILABLE;
        }

        private @NotNull SlotView refreshAvailability(
            @NotNull SkillService skillService,
            @NotNull PlayerSkillCaster caster
        ) {
            if (!owned || definition == null || definition.getKind() != SkillKind.ACTIVE) {
                return this;
            }
            SlotAvailability nextAvailability = availabilityFor(skillService.canCast(caster, definition));
            return new SlotView(skillId, definition, name, material, true, nextAvailability);
        }

        private @NotNull String label(@NotNull SkillService skillService, @NotNull PlayerSkillCaster caster) {
            if (availability == SlotAvailability.COOLDOWN && definition != null && skillId != null) {
                return name + "\n" + cooldownBar(skillService, caster);
            }
            if (availability.label().isBlank()) {
                return name;
            }
            return name + "\n" + availability.label();
        }

        private boolean hasSecondaryLine() {
            return availability == SlotAvailability.COOLDOWN || !availability.label().isBlank();
        }

        private @NotNull String cooldownBar(
            @NotNull SkillService skillService,
            @NotNull PlayerSkillCaster caster
        ) {
            long totalTicks = Math.max(1L, definition.getCooldownTicks());
            long remainingTicks = Math.min(totalTicks, skillService.getRemainingCooldownTicks(caster, skillId));
            int filled = (int) Math.ceil((double) remainingTicks / totalTicks * COOLDOWN_BAR_LENGTH);
            StringBuilder bar = new StringBuilder(COOLDOWN_BAR_LENGTH + 8);
            bar.append(ColorCodeUtil.YELLOW);
            for (int index = 0; index < filled; index++) {
                bar.append('|');
            }
            bar.append(ColorCodeUtil.DARK_GRAY);
            for (int index = filled; index < COOLDOWN_BAR_LENGTH; index++) {
                bar.append('|');
            }
            return bar.toString();
        }

        private @NotNull String color(boolean selected) {
            if (selected) {
                return ColorCodeUtil.YELLOW;
            }
            if (availability.temporarilyUnavailable()) {
                return ColorCodeUtil.RED;
            }
            return selectable() ? ColorCodeUtil.GRAY : ColorCodeUtil.DARK_GRAY;
        }
    }

    private enum SlotAvailability {
        AVAILABLE("", false),
        COOLDOWN("", true),
        MANA("MP", true),
        ENERGY("ENG", true),
        BLOCKED("NG", true),
        UNAVAILABLE("", false);

        private final String label;
        private final boolean temporarilyUnavailable;

        SlotAvailability(@NotNull String label, boolean temporarilyUnavailable) {
            this.label = label;
            this.temporarilyUnavailable = temporarilyUnavailable;
        }

        private @NotNull String label() {
            return label;
        }

        private boolean temporarilyUnavailable() {
            return temporarilyUnavailable;
        }
    }

    private static @NotNull Component legacyComponent(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(
            ColorCodeUtil.translateAlternateColorCodes(text)
        );
    }

    private enum RingPhase {
        SELECTING,
        WAITING_CAST
    }

    private static final class RingSession {
        private final Location baseEye;
        private final Location baseCenter;
        private final Vector normal;
        private final Vector right;
        private final Vector up;
        private final List<SlotView> slots;
        private final Player viewer;
        private final SkillActionRingDisplay actionRingDisplay;
        private final SkillService skillService;
        private final PlayerSkillCaster caster;
        private final List<SkillActionRingDisplay.DisplayEntity> icons = new ArrayList<>(SLOT_COUNT);
        private final List<SkillActionRingDisplay.DisplayEntity> labels = new ArrayList<>(SLOT_COUNT);
        private final List<SkillActionRingDisplay.DisplayEntity> circleDots = new ArrayList<>(CIRCLE_DISPLAY_POINTS);
        private final AttributeInstance blockBreakSpeedAttribute;
        private final Double originalBlockBreakSpeed;
        private SkillActionRingDisplay.DisplayEntity closeIcon;
        private SkillActionRingDisplay.DisplayEntity closeLabel;
        private SkillActionRingDisplay.DisplayEntity timerLabel;
        private int selectedIndex;
        private int confirmedIndex = -1;
        private RingPhase phase = RingPhase.SELECTING;
        private long phaseElapsedTicks;

        private RingSession(
            @NotNull Location baseEye,
            @NotNull Location baseCenter,
            @NotNull Vector normal,
            @NotNull Vector right,
            @NotNull Vector up,
            @NotNull List<SlotView> slots,
            @NotNull Player viewer,
            @NotNull SkillActionRingDisplay actionRingDisplay,
            @NotNull SkillService skillService,
            @NotNull PlayerSkillCaster caster,
            AttributeInstance blockBreakSpeedAttribute,
            Double originalBlockBreakSpeed
        ) {
            this.baseEye = baseEye;
            this.baseCenter = baseCenter;
            this.normal = normal;
            this.right = right;
            this.up = up;
            this.slots = slots;
            this.viewer = viewer;
            this.actionRingDisplay = actionRingDisplay;
            this.skillService = skillService;
            this.caster = caster;
            this.blockBreakSpeedAttribute = blockBreakSpeedAttribute;
            this.originalBlockBreakSpeed = originalBlockBreakSpeed;
            this.selectedIndex = firstSelectableSlot(slots);
        }

        private static @NotNull RingSession create(
            @NotNull Player player,
            @NotNull List<SlotView> slots,
            @NotNull SkillActionRingDisplay actionRingDisplay,
            @NotNull SkillService skillService,
            @NotNull PlayerSkillCaster caster
        ) {
            Location eye = player.getEyeLocation();
            Vector normal = eye.getDirection().normalize();
            Vector right = normal.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
            if (right.lengthSquared() < 1.0E-6D) {
                right = new Vector(1.0D, 0.0D, 0.0D);
            } else {
                right.normalize();
            }
            Vector up = right.clone().crossProduct(normal).normalize();
            Location center = eye.clone().add(normal.clone().multiply(RING_DISTANCE));
            AttributeInstance blockBreakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
            Double originalBlockBreakSpeed = null;
            if (blockBreakSpeed != null) {
                originalBlockBreakSpeed = blockBreakSpeed.getBaseValue();
                blockBreakSpeed.setBaseValue(SELECTING_BLOCK_BREAK_SPEED);
            }
            RingSession session = new RingSession(
                eye.clone(),
                center,
                normal,
                right,
                up,
                slots,
                player,
                actionRingDisplay,
                skillService,
                caster,
                blockBreakSpeed,
                originalBlockBreakSpeed
            );
            session.spawnEntities(player);
            return session;
        }

        private void spawnEntities(@NotNull Player player) {
            for (int index = 0; index < CIRCLE_DISPLAY_POINTS; index++) {
                SkillActionRingDisplay.DisplayEntity dot = actionRingDisplay.text(
                    baseCenter,
                    legacyComponent(ColorCodeUtil.AQUA + "*"),
                    0.42F
                );
                dot.spawn(player);
                circleDots.add(dot);
            }
            for (int index = 0; index < SLOT_COUNT; index++) {
                Location location = baseCenter.clone();
                SkillActionRingDisplay.DisplayEntity icon = actionRingDisplay.item(
                    location,
                    new ItemStack(slots.get(index).material()),
                    false
                );
                SkillActionRingDisplay.DisplayEntity label = actionRingDisplay.text(location, Component.empty(), 0.60F);
                icon.spawn(player);
                label.spawn(player);
                icons.add(icon);
                labels.add(label);
            }
            closeIcon = actionRingDisplay.item(baseCenter, new ItemStack(Material.BARRIER), false);
            closeLabel = actionRingDisplay.text(baseCenter, legacyComponent(ColorCodeUtil.RED + "閉じる"), 0.60F);
            timerLabel = actionRingDisplay.text(baseCenter, Component.empty(), 0.60F);
            closeIcon.spawn(player);
            closeLabel.spawn(player);
            timerLabel.spawn(player);
        }

        private boolean tick(@NotNull Player player) {
            Location center = currentCenter(player);
            if (center.getWorld() == null) {
                return false;
            }
            phaseElapsedTicks++;
            if (phase == RingPhase.SELECTING && phaseElapsedTicks > RING_DISPLAY_LIMIT_TICKS) {
                GuiSound.CLOSE.play(player);
                return false;
            }
            if (phase == RingPhase.WAITING_CAST && phaseElapsedTicks > CAST_WAIT_LIMIT_TICKS) {
                GuiSound.CLOSE.play(player);
                return false;
            }

            if (phase == RingPhase.SELECTING) {
                refreshSlotAvailability();
                int nextSelectedIndex = resolveSelectedIndex(player);
                if (nextSelectedIndex != selectedIndex) {
                    selectedIndex = nextSelectedIndex;
                    if (selectedIndex >= 0) {
                        GuiSound.RING_SWITCH.play(player);
                    }
                }
            }

            updateCircle(center);
            for (int index = 0; index < SLOT_COUNT; index++) {
                SlotView slot = slots.get(index);
                boolean selected = index == selectedIndex && slot.selectable();
                boolean hiddenByConfirmedSelection = phase == RingPhase.WAITING_CAST && index != confirmedIndex;
                Vector slotOffset = animatedSlotOffset(index);
                Location iconLocation = center.clone().add(slotOffset);
                double labelOffset = 0.42D + (slot.hasSecondaryLine() ? 0.12D : 0.0D);
                Location labelLocation = iconLocation.clone().subtract(up.clone().multiply(labelOffset));
                SkillActionRingDisplay.DisplayEntity icon = icons.get(index);
                SkillActionRingDisplay.DisplayEntity label = labels.get(index);
                icon.teleport(player, iconLocation);
                actionRingDisplay.updateItem(
                    player,
                    icon,
                    hiddenByConfirmedSelection ? HIDDEN_ITEM : new ItemStack(slot.material()),
                    selected && !hiddenByConfirmedSelection
                );
                label.teleport(player, labelLocation);
                actionRingDisplay.updateText(
                    player,
                    label,
                    hiddenByConfirmedSelection
                        ? Component.empty()
                        : legacyComponent(slot.color(selected) + slot.label(skillService, caster)),
                    0.60F
                );
            }
            updateCloseButton(player, center);
            updateTimer(center);
            return true;
        }

        private boolean hasConfirmedSelection() {
            return phase == RingPhase.WAITING_CAST;
        }

        private boolean canActivateSelected() {
            return isCloseSelected()
                || selectedIndex >= 0
                && selectedIndex < slots.size()
                && slots.get(selectedIndex).selectable();
        }

        private void refreshSlotAvailability() {
            for (int index = 0; index < slots.size(); index++) {
                slots.set(index, slots.get(index).refreshAvailability(skillService, caster));
            }
            if (selectedIndex >= 0 && selectedIndex < slots.size() && !slots.get(selectedIndex).selectable()) {
                selectedIndex = firstSelectableSlot(slots);
            }
        }

        private boolean isCloseSelected() {
            return phase == RingPhase.SELECTING && selectedIndex == CLOSE_SELECTION_INDEX;
        }

        private void confirmSelection() {
            confirmedIndex = selectedIndex;
            phase = RingPhase.WAITING_CAST;
            phaseElapsedTicks = 0L;
        }

        private String selectedSkillId() {
            if (confirmedIndex < 0 || confirmedIndex >= slots.size()) {
                return null;
            }
            return slots.get(confirmedIndex).skillId();
        }

        private @NotNull Location currentCenter(@NotNull Player player) {
            Location currentEye = player.getEyeLocation();
            Vector movement = currentEye.toVector().subtract(baseEye.toVector());
            Location center = baseCenter.clone().add(movement);
            center.setWorld(currentEye.getWorld());
            return center;
        }

        private int resolveSelectedIndex(@NotNull Player player) {
            Vector view = player.getEyeLocation().getDirection().normalize();
            Vector projected = view.subtract(normal.clone().multiply(view.dot(normal)));
            if (projected.lengthSquared() < 1.0E-6D) {
                return CLOSE_SELECTION_INDEX;
            }
            if (projected.length() <= CLOSE_SELECTION_PROJECTED_LENGTH) {
                return CLOSE_SELECTION_INDEX;
            }
            projected.normalize();
            double angle = Math.atan2(projected.dot(right), projected.dot(up));
            double unit = (Math.PI * 2.0D) / SLOT_COUNT;
            int index = (int) Math.round(angle / unit);
            int resolved = Math.floorMod(index, SLOT_COUNT);
            return slots.get(resolved).selectable() ? resolved : -1;
        }

        private @NotNull Vector slotOffset(int index) {
            double angle = ((Math.PI * 2.0D) / SLOT_COUNT) * index;
            return up.clone().multiply(Math.cos(angle) * RING_RADIUS)
                .add(right.clone().multiply(Math.sin(angle) * RING_RADIUS));
        }

        private @NotNull Vector animatedSlotOffset(int index) {
            Vector offset = slotOffset(index);
            if (phase != RingPhase.WAITING_CAST || index != confirmedIndex) {
                return offset;
            }
            double progress = Math.min(1.0D, (double) phaseElapsedTicks / SELECT_ANIMATION_TICKS);
            return offset.multiply(1.0D - progress);
        }

        private void updateCircle(@NotNull Location center) {
            for (int index = 0; index < circleDots.size(); index++) {
                SkillActionRingDisplay.DisplayEntity dot = circleDots.get(index);
                double angle = ((Math.PI * 2.0D) / CIRCLE_DISPLAY_POINTS) * index;
                Vector offset = up.clone().multiply(Math.cos(angle) * RING_RADIUS)
                    .add(right.clone().multiply(Math.sin(angle) * RING_RADIUS));
                dot.teleport(viewer, center.clone().add(offset));
            }
        }

        private void updateCloseButton(@NotNull Player player, @NotNull Location center) {
            if (closeIcon == null || closeLabel == null) {
                return;
            }
            boolean hidden = phase == RingPhase.WAITING_CAST;
            boolean selected = isCloseSelected();
            Location labelLocation = center.clone().subtract(up.clone().multiply(0.45D));
            closeIcon.teleport(player, center);
            closeLabel.teleport(player, labelLocation);
            actionRingDisplay.updateItem(player, closeIcon, hidden ? HIDDEN_ITEM : new ItemStack(Material.BARRIER), selected && !hidden);
            actionRingDisplay.updateText(
                player,
                closeLabel,
                hidden ? Component.empty() : legacyComponent((selected ? ColorCodeUtil.YELLOW : ColorCodeUtil.RED) + "閉じる"),
                0.60F
            );
        }

        private void updateTimer(@NotNull Location center) {
            if (timerLabel == null) {
                return;
            }
            Location timerLocation = center.clone().subtract(up.clone().multiply(0.30D));
            timerLabel.teleport(viewer, timerLocation);
            actionRingDisplay.updateText(viewer, timerLabel, legacyComponent(timerText()), 0.60F);
        }

        private @NotNull String timerText() {
            long limit = phase == RingPhase.SELECTING ? RING_DISPLAY_LIMIT_TICKS : CAST_WAIT_LIMIT_TICKS;
            long remainingTicks = Math.max(0L, limit - phaseElapsedTicks);
            double remaining = Math.max(0.0D, Math.min(1.0D, (double) remainingTicks / limit));
            int filled = (int) Math.round(remaining * TIMER_BAR_LENGTH);
            StringBuilder bar = new StringBuilder(TIMER_BAR_LENGTH + 16);
            bar.append(ColorCodeUtil.GREEN);
            for (int index = 0; index < filled; index++) {
                bar.append('|');
            }
            bar.append(ColorCodeUtil.DARK_GRAY);
            for (int index = filled; index < TIMER_BAR_LENGTH; index++) {
                bar.append('|');
            }
            return bar.toString();
        }

        private void destroy() {
            if (!viewer.isOnline()) {
                restoreBlockBreakSpeed();
                return;
            }
            for (SkillActionRingDisplay.DisplayEntity icon : icons) {
                icon.destroy(viewer);
            }
            icons.clear();
            for (SkillActionRingDisplay.DisplayEntity label : labels) {
                label.destroy(viewer);
            }
            labels.clear();
            for (SkillActionRingDisplay.DisplayEntity dot : circleDots) {
                dot.destroy(viewer);
            }
            circleDots.clear();
            if (closeIcon != null) {
                closeIcon.destroy(viewer);
                closeIcon = null;
            }
            if (closeLabel != null) {
                closeLabel.destroy(viewer);
                closeLabel = null;
            }
            if (timerLabel != null) {
                timerLabel.destroy(viewer);
                timerLabel = null;
            }
            restoreBlockBreakSpeed();
        }

        private void restoreBlockBreakSpeed() {
            if (blockBreakSpeedAttribute != null && originalBlockBreakSpeed != null) {
                blockBreakSpeedAttribute.setBaseValue(originalBlockBreakSpeed);
            }
        }

        private static int firstSelectableSlot(@NotNull List<SlotView> slots) {
            for (int index = 0; index < slots.size(); index++) {
                if (slots.get(index).selectable()) {
                    return index;
                }
            }
            return -1;
        }
    }
}
