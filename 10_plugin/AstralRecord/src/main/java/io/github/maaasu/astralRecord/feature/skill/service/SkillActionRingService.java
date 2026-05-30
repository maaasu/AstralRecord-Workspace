package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    private static final long OPEN_ANIMATION_TICKS = 10L;
    private static final long SELECT_ANIMATION_TICKS = 4L;

    private static final List<DummySkillSlot> DUMMY_SLOTS = List.of(
        new DummySkillSlot("スキル 1", Material.BLAZE_POWDER),
        new DummySkillSlot("スキル 2", Material.FEATHER),
        new DummySkillSlot("スキル 3", Material.IRON_SWORD),
        new DummySkillSlot("スキル 4", Material.SHIELD),
        new DummySkillSlot("スキル 5", Material.ENDER_PEARL),
        new DummySkillSlot("スキル 6", Material.AMETHYST_SHARD),
        new DummySkillSlot("スキル 7", Material.EMERALD),
        new DummySkillSlot("スキル 8", Material.NETHER_STAR)
    );

    private final AstralRecord plugin;
    private final SkillBindPresetService presetService;
    private final SkillService skillService;
    private final Map<UUID, RingSession> sessions = new ConcurrentHashMap<>();
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
        @NotNull SkillService skillService
    ) {
        this.plugin = plugin;
        this.presetService = presetService;
        this.skillService = skillService;
    }

    /**
     * プレイヤーのアクションリング表示状態を切り替えます。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void toggle(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        UUID playerId = player.getUniqueId();
        RingSession current = sessions.remove(playerId);
        if (current != null) {
            current.destroy();
            GuiSound.CLOSE.play(player);
            return;
        }

        sessions.put(playerId, RingSession.create(player, resolveSlots(astPlayer)));
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
        if (!session.hasConfirmedSelection()) {
            session.confirmSelection();
            GuiSound.RING_SELECT.play(player);
            return;
        }

        sessions.remove(player.getUniqueId());
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
        astPlayer.sendMessage(PlayerMsgId.P_5807, SLOT_COUNT, selectedSlot);
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
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, UPDATE_INTERVAL_TICKS);
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

    private @NotNull List<SlotView> resolveSlots(@NotNull AstPlayer astPlayer) {
        List<SlotView> slots = new ArrayList<>(SLOT_COUNT);
        List<String> activeSlots = presetService.getPresets(astPlayer.getAccount().getUuid()).stream()
            .filter(SkillBindPreset::isUnlocked)
            .findFirst()
            .map(SkillBindPreset::getActiveSkillSlots)
            .orElse(List.of());

        for (int index = 0; index < SLOT_COUNT; index++) {
            String skillId = index < activeSlots.size() ? activeSlots.get(index) : null;
            String displayName = skillId == null || skillId.isBlank() ? DUMMY_SLOTS.get(index).name() : skillId;
            slots.add(new SlotView(skillId, displayName));
        }
        return slots;
    }

    private record DummySkillSlot(@NotNull String name, @NotNull Material material) {
    }

    private record SlotView(String skillId, @NotNull String name) {
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
        private final List<ItemDisplay> icons = new ArrayList<>(SLOT_COUNT);
        private final List<TextDisplay> labels = new ArrayList<>(SLOT_COUNT);
        private final List<TextDisplay> circleDots = new ArrayList<>(CIRCLE_DISPLAY_POINTS);
        private TextDisplay timerLabel;
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
            @NotNull List<SlotView> slots
        ) {
            this.baseEye = baseEye;
            this.baseCenter = baseCenter;
            this.normal = normal;
            this.right = right;
            this.up = up;
            this.slots = slots;
        }

        private static @NotNull RingSession create(
            @NotNull Player player,
            @NotNull List<SlotView> slots
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
            RingSession session = new RingSession(eye.clone(), center, normal, right, up, slots);
            session.spawnEntities(player.getWorld());
            return session;
        }

        private void spawnEntities(@NotNull World world) {
            for (int index = 0; index < CIRCLE_DISPLAY_POINTS; index++) {
                TextDisplay dot = world.spawn(baseCenter, TextDisplay.class, display -> {
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setPersistent(false);
                    display.setSilent(true);
                    display.setViewRange(16.0F);
                    display.setSeeThrough(true);
                    display.setShadowed(false);
                    display.setDefaultBackground(false);
                    display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    display.text(legacyComponent(ColorCodeUtil.AQUA + "*"));
                    display.setTransformation(scaleTransformation(0.42F));
                });
                circleDots.add(dot);
            }
            for (int index = 0; index < SLOT_COUNT; index++) {
                Location location = baseCenter.clone();
                int slotIndex = index;
                ItemDisplay icon = world.spawn(location, ItemDisplay.class, display -> {
                    display.setItemStack(new ItemStack(DUMMY_SLOTS.get(slotIndex).material()));
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setPersistent(false);
                    display.setSilent(true);
                    display.setViewRange(16.0F);
                });
                TextDisplay label = world.spawn(location, TextDisplay.class, display -> {
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setPersistent(false);
                    display.setSilent(true);
                    display.setViewRange(16.0F);
                    display.setLineWidth(120);
                    display.setSeeThrough(true);
                    display.setShadowed(true);
                    display.setDefaultBackground(false);
                    display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                });
                icons.add(icon);
                labels.add(label);
            }
            timerLabel = world.spawn(baseCenter, TextDisplay.class, display -> {
                display.setBillboard(Display.Billboard.CENTER);
                display.setGravity(false);
                display.setInvulnerable(true);
                display.setPersistent(false);
                display.setSilent(true);
                display.setViewRange(16.0F);
                display.setLineWidth(180);
                display.setSeeThrough(true);
                display.setShadowed(true);
                display.setDefaultBackground(false);
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                display.setTransformation(scaleTransformation(0.60F));
            });
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
                    GuiSound.RING_SWITCH.play(player);
                }
            }

            updateCircle(center);
            for (int index = 0; index < SLOT_COUNT; index++) {
                boolean selected = index == selectedIndex;
                boolean hiddenByConfirmedSelection = phase == RingPhase.WAITING_CAST && index != confirmedIndex;
                Vector slotOffset = animatedSlotOffset(index);
                Location iconLocation = center.clone().add(slotOffset);
                Location labelLocation = iconLocation.clone().subtract(up.clone().multiply(selected ? 0.43D : 0.35D));
                ItemDisplay icon = icons.get(index);
                TextDisplay label = labels.get(index);
                if (icon.isValid()) {
                    icon.teleport(iconLocation);
                    icon.setGlowing(selected && !hiddenByConfirmedSelection);
                    icon.setTransformation(scaleTransformation(iconScale(selected, hiddenByConfirmedSelection)));
                }
                if (label.isValid()) {
                    label.teleport(labelLocation);
                    if (hiddenByConfirmedSelection) {
                        label.text(Component.empty());
                        label.setTransformation(scaleTransformation(0.0F));
                        continue;
                    }
                    String color = selected ? ColorCodeUtil.YELLOW : ColorCodeUtil.GRAY;
                    label.text(legacyComponent(color + slots.get(index).name()));
                    label.setTransformation(scaleTransformation(labelScale(selected)));
                }
            }
            updateTimer(center);
            return true;
        }

        private boolean hasConfirmedSelection() {
            return phase == RingPhase.WAITING_CAST;
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
                return selectedIndex;
            }
            projected.normalize();
            double angle = Math.atan2(projected.dot(right), projected.dot(up));
            double unit = (Math.PI * 2.0D) / SLOT_COUNT;
            int index = (int) Math.round(angle / unit);
            return Math.floorMod(index, SLOT_COUNT);
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

        private float iconScale(boolean selected, boolean hiddenByConfirmedSelection) {
            if (hiddenByConfirmedSelection) {
                return 0.0F;
            }
            float targetScale = selected ? 0.86F : 0.58F;
            return targetScale * openAnimationProgress();
        }

        private float labelScale(boolean selected) {
            float targetScale = selected ? 0.864F : 0.672F;
            return targetScale * openAnimationProgress();
        }

        private float openAnimationProgress() {
            return Math.min(1.0F, (float) ageTicks / OPEN_ANIMATION_TICKS);
        }

        private void updateCircle(@NotNull Location center) {
            for (int index = 0; index < circleDots.size(); index++) {
                TextDisplay dot = circleDots.get(index);
                if (!dot.isValid()) {
                    continue;
                }
                double angle = ((Math.PI * 2.0D) / CIRCLE_DISPLAY_POINTS) * index;
                Vector offset = up.clone().multiply(Math.cos(angle) * RING_RADIUS)
                    .add(right.clone().multiply(Math.sin(angle) * RING_RADIUS));
                dot.teleport(center.clone().add(offset));
            }
        }

        private void updateTimer(@NotNull Location center) {
            if (timerLabel == null || !timerLabel.isValid()) {
                return;
            }
            Location timerLocation = center.clone().subtract(up.clone().multiply(0.30D));
            timerLabel.teleport(timerLocation);
            timerLabel.text(legacyComponent(timerText()));
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

        private @NotNull Transformation scaleTransformation(float scale) {
            return new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
            );
        }

        private void destroy() {
            for (ItemDisplay icon : icons) {
                if (icon.isValid()) {
                    icon.remove();
                }
            }
            icons.clear();
            for (TextDisplay label : labels) {
                if (label.isValid()) {
                    label.remove();
                }
            }
            labels.clear();
            for (TextDisplay dot : circleDots) {
                if (dot.isValid()) {
                    dot.remove();
                }
            }
            circleDots.clear();
            if (timerLabel != null && timerLabel.isValid()) {
                timerLabel.remove();
            }
            timerLabel = null;
        }
    }
}
