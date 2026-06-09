package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * スキル発動前のアクションリング表示と選択状態を管理します。
 */
public final class SkillActionRingService {
    private static final int SLOT_COUNT = SkillBindPreset.SLOT_COUNT;
    private static final double RING_DISTANCE = 2.0D;
    private static final double RING_RADIUS = 1.12D;
    private static final int CIRCLE_DISPLAY_POINTS = 24;
    private static final int TIMER_BAR_LENGTH = 24;
    private static final long UPDATE_INTERVAL_TICKS = 1L;
    private static final long RING_DISPLAY_LIMIT_TICKS = 100L;
    private static final long CAST_WAIT_LIMIT_TICKS = 60L;
    private static final long SELECT_ANIMATION_TICKS = 4L;
    private static final long SWAP_CLOSE_DEBOUNCE_MILLIS = 2_000L;
    private static final double SELECTING_BLOCK_BREAK_SPEED = 1024.0D;
    private static final int CLOSE_SELECTION_INDEX = -2;
    private static final double CLOSE_SELECTION_PROJECTED_LENGTH = 0.28D;
    private static final ItemStack HIDDEN_ITEM = new ItemStack(Material.AIR);

    private final AstralRecord plugin;
    private final SkillBindPresetService presetService;
    private final SkillService skillService;
    private final SkillOwnershipService ownershipService;
    private final SkillActionRingPacketDisplay packetDisplay;
    private final Map<UUID, RingSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> swapCloseSuppressedUntil = new ConcurrentHashMap<>();
    private final Set<UUID> suppressedAttackPlayers = ConcurrentHashMap.newKeySet();
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
        this.packetDisplay = new SkillActionRingPacketDisplay(plugin);
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
            swapCloseSuppressedUntil.remove(playerId);
            current.destroy();
            GuiSound.CLOSE.play(player);
            return;
        }

        RingSession session = RingSession.create(player, resolveSlots(astPlayer), packetDisplay);
        sessions.put(playerId, session);
        GuiSound.RING_OPEN.play(player);
        ensureTask();
    }

    /**
     * オフハンド切替入力からアクションリング表示状態を切り替えます。
     * <p>
     * クライアントが同じ切替入力を短時間に複数送る場合があるため、開いた直後の close 側トグルだけを抑止します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void toggleBySwapInput(@NotNull AstPlayer astPlayer) {
        var player = astPlayer.getBukkit();
        var playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        var current = sessions.get(playerId);
        if (current != null) {
            Long suppressedUntil = swapCloseSuppressedUntil.get(playerId);
            if (suppressedUntil != null && now <= suppressedUntil) {
                return;
            }
            sessions.remove(playerId);
            swapCloseSuppressedUntil.remove(playerId);
            current.destroy();
            GuiSound.CLOSE.play(player);
            return;
        }

        RingSession session = RingSession.create(player, resolveSlots(astPlayer), packetDisplay);
        sessions.put(playerId, session);
        swapCloseSuppressedUntil.put(playerId, now + SWAP_CLOSE_DEBOUNCE_MILLIS);
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
            swapCloseSuppressedUntil.remove(player.getUniqueId());
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
        swapCloseSuppressedUntil.remove(player.getUniqueId());
        String skillId = session.selectedSkillId();
        int selectedSlot = session.selectedIndex + 1;
        session.destroy();
        if (skillId != null && !skillId.isBlank()) {
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
        astPlayer.sendMessage(PlayerMsgId.P_5807, SLOT_COUNT, selectedSlot, skillId);
    }

    /**
     * 確定待ちの選択を解除し、リング選択表示へ戻します。
     *
     * @param player 対象プレイヤー
     * @return 選択待ちへ戻した場合は true
     */
    public boolean returnToSelecting(@NotNull Player player) {
        RingSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.hasConfirmedSelection()) {
            return false;
        }
        session.returnToSelecting();
        GuiSound.RING_SWITCH.play(player);
        return true;
    }

    /**
     * 指定プレイヤーのリングを閉じます。
     *
     * @param player 対象プレイヤー
     */
    public void close(@NotNull Player player) {
        RingSession session = sessions.remove(player.getUniqueId());
        swapCloseSuppressedUntil.remove(player.getUniqueId());
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
        swapCloseSuppressedUntil.clear();
        suppressedAttackPlayers.clear();
    }

    private void ensureTask() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, UPDATE_INTERVAL_TICKS);
    }

    private void tick() {
        for (Map.Entry<UUID, RingSession> entry : sessions.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().destroy();
                sessions.remove(entry.getKey());
                swapCloseSuppressedUntil.remove(entry.getKey());
                continue;
            }
            if (!entry.getValue().tick(player)) {
                entry.getValue().destroy();
                sessions.remove(entry.getKey());
                swapCloseSuppressedUntil.remove(entry.getKey());
            }
        }
        if (sessions.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private @NotNull List<SlotView> resolveSlots(@NotNull AstPlayer astPlayer) {
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
                slots.add(new SlotView(null, "未設定", Material.BARRIER, false));
                continue;
            }
            SkillDefinition definition = skillService.registry().getDefinition(skillId);
            if (definition != null && definition.getKind() != SkillKind.ACTIVE) {
                slots.add(new SlotView(skillId, "設定不可", Material.BARRIER, false));
                continue;
            }
            String displayName = definition == null ? skillId : ColorCodeUtil.translateAlternateColorCodes(definition.getName());
            boolean owned = ownedSkillIds.contains(skillId);
            Material material = owned ? parseMaterial(definition == null ? null : definition.getIcon(), Material.BARRIER) : Material.BARRIER;
            slots.add(new SlotView(skillId, displayName, material, owned));
        }
        return slots;
    }

    private @NotNull Material parseMaterial(String value, @NotNull Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(value.trim());
        return material == null ? fallback : material;
    }

    private record SlotView(String skillId, @NotNull String name, @NotNull Material material, boolean selectable) {
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
        private final SkillActionRingPacketDisplay packetDisplay;
        private final List<SkillActionRingPacketDisplay.PacketEntity> icons = new ArrayList<>(SLOT_COUNT);
        private final List<SkillActionRingPacketDisplay.PacketEntity> labels = new ArrayList<>(SLOT_COUNT);
        private final List<SkillActionRingPacketDisplay.PacketEntity> circleDots = new ArrayList<>(CIRCLE_DISPLAY_POINTS);
        private final AttributeInstance blockBreakSpeedAttribute;
        private final Double originalBlockBreakSpeed;
        private SkillActionRingPacketDisplay.PacketEntity closeIcon;
        private SkillActionRingPacketDisplay.PacketEntity closeLabel;
        private SkillActionRingPacketDisplay.PacketEntity timerLabel;
        private int selectedIndex;
        private int confirmedIndex = -1;
        private RingPhase phase = RingPhase.SELECTING;
        private long ageTicks;
        private long phaseElapsedTicks;

        private RingSession(
            @NotNull Location baseEye,
            @NotNull Location baseCenter,
            @NotNull Vector normal,
            @NotNull Vector right,
            @NotNull Vector up,
            @NotNull List<SlotView> slots,
            @NotNull Player viewer,
            @NotNull SkillActionRingPacketDisplay packetDisplay,
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
            this.packetDisplay = packetDisplay;
            this.blockBreakSpeedAttribute = blockBreakSpeedAttribute;
            this.originalBlockBreakSpeed = originalBlockBreakSpeed;
            this.selectedIndex = firstSelectableSlot(slots);
        }

        private static @NotNull RingSession create(
            @NotNull Player player,
            @NotNull List<SlotView> slots,
            @NotNull SkillActionRingPacketDisplay packetDisplay
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
                packetDisplay,
                blockBreakSpeed,
                originalBlockBreakSpeed
            );
            session.spawnEntities(player);
            return session;
        }

        private void spawnEntities(@NotNull Player player) {
            for (int index = 0; index < CIRCLE_DISPLAY_POINTS; index++) {
                SkillActionRingPacketDisplay.PacketEntity dot = packetDisplay.text(
                    baseCenter,
                    legacyComponent(ColorCodeUtil.AQUA + "*"),
                    0.42F
                );
                dot.spawn(player);
                circleDots.add(dot);
            }
            for (int index = 0; index < SLOT_COUNT; index++) {
                Location location = baseCenter.clone();
                SkillActionRingPacketDisplay.PacketEntity icon = packetDisplay.item(
                    location,
                    new ItemStack(slots.get(index).material()),
                    false
                );
                SkillActionRingPacketDisplay.PacketEntity label = packetDisplay.text(location, Component.empty(), 0.60F);
                icon.spawn(player);
                label.spawn(player);
                icons.add(icon);
                labels.add(label);
            }
            closeIcon = packetDisplay.item(baseCenter, new ItemStack(Material.BARRIER), false);
            closeLabel = packetDisplay.text(baseCenter, legacyComponent(ColorCodeUtil.RED + "閉じる"), 0.60F);
            timerLabel = packetDisplay.text(baseCenter, Component.empty(), 0.60F);
            closeIcon.spawn(player);
            closeLabel.spawn(player);
            timerLabel.spawn(player);
        }

        private boolean tick(@NotNull Player player) {
            Location center = currentCenter(player);
            if (center.getWorld() == null) {
                return false;
            }
            ageTicks++;
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
                Location labelLocation = iconLocation.clone().subtract(up.clone().multiply(0.35D));
                SkillActionRingPacketDisplay.PacketEntity icon = icons.get(index);
                SkillActionRingPacketDisplay.PacketEntity label = labels.get(index);
                icon.teleport(player, iconLocation);
                packetDisplay.updateItem(
                    player,
                    icon,
                    hiddenByConfirmedSelection ? HIDDEN_ITEM : new ItemStack(slot.material()),
                    selected && !hiddenByConfirmedSelection
                );
                label.teleport(player, labelLocation);
                String color = selected ? ColorCodeUtil.YELLOW : slot.selectable() ? ColorCodeUtil.GRAY : ColorCodeUtil.DARK_GRAY;
                packetDisplay.updateText(
                    player,
                    label,
                    hiddenByConfirmedSelection ? Component.empty() : legacyComponent(color + slot.name()),
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

        private boolean isCloseSelected() {
            return phase == RingPhase.SELECTING && selectedIndex == CLOSE_SELECTION_INDEX;
        }

        private void confirmSelection() {
            confirmedIndex = selectedIndex;
            phase = RingPhase.WAITING_CAST;
            phaseElapsedTicks = 0L;
        }

        private void returnToSelecting() {
            phase = RingPhase.SELECTING;
            confirmedIndex = -1;
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
                SkillActionRingPacketDisplay.PacketEntity dot = circleDots.get(index);
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
            packetDisplay.updateItem(player, closeIcon, hidden ? HIDDEN_ITEM : new ItemStack(Material.BARRIER), selected && !hidden);
            packetDisplay.updateText(
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
            packetDisplay.updateText(viewer, timerLabel, legacyComponent(timerText()), 0.60F);
        }

        private @NotNull String timerText() {
            long limit = phase == RingPhase.SELECTING ? RING_DISPLAY_LIMIT_TICKS : CAST_WAIT_LIMIT_TICKS;
            long remainingTicks = Math.max(0L, limit - phaseElapsedTicks);
            double remaining = Math.max(0.0D, Math.min(1.0D, (double) remainingTicks / limit));
            int filled = (int) Math.round(remaining * TIMER_BAR_LENGTH);
            String label = phase == RingPhase.SELECTING ? "SELECT" : "CAST";
            String accent = phase == RingPhase.SELECTING ? ColorCodeUtil.AQUA : ColorCodeUtil.YELLOW;
            StringBuilder bar = new StringBuilder(TIMER_BAR_LENGTH + 32);
            bar.append(accent)
                .append(label)
                .append(' ')
                .append(ColorCodeUtil.WHITE)
                .append(String.format(Locale.ROOT, "%.1fs", remainingTicks / 20.0D))
                .append('\n')
                .append(ColorCodeUtil.GREEN);
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
            for (SkillActionRingPacketDisplay.PacketEntity icon : icons) {
                icon.destroy(viewer);
            }
            icons.clear();
            for (SkillActionRingPacketDisplay.PacketEntity label : labels) {
                label.destroy(viewer);
            }
            labels.clear();
            for (SkillActionRingPacketDisplay.PacketEntity dot : circleDots) {
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
