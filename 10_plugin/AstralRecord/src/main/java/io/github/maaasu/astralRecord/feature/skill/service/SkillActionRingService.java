package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import java.util.function.Consumer;

/**
 * スキル発動前のアクションリング表示と選択状態を管理します。
 */
public final class SkillActionRingService {
    private static final int SLOT_COUNT = SkillBindPreset.ACTION_RING_SLOT_COUNT;
    private static final double RING_DISTANCE = 3.0D;
    private static final double RING_RADIUS = 1.12D;
    private static final int CIRCLE_DISPLAY_POINTS = 24;
    private static final int TIMER_BAR_LENGTH = 24;
    private static final int COOLDOWN_BAR_LENGTH = 10;
    private static final long UPDATE_INTERVAL_TICKS = 1L;
    private static final long RING_DISPLAY_LIMIT_TICKS = 100L;
    private static final long SELECT_ANIMATION_TICKS = 4L;
    private static final double SELECTING_BLOCK_BREAK_SPEED = 1024.0D;
    private static final ItemStack HIDDEN_ITEM = new ItemStack(Material.AIR);

    private final AstralRecord plugin;
    private final SkillBindPresetService presetService;
    private final SkillService skillService;
    private final SkillOwnershipService ownershipService;
    private final SkillPermissionService permissionService;
    private final SkillActionRingDisplay actionRingDisplay;
    private final Map<UUID, RingSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> suppressedAttackPlayers = ConcurrentHashMap.newKeySet();
    private ItemWeaponAttackService itemWeaponAttackService;
    private Consumer<AstPlayer> openListener = player -> { };
    private Consumer<Player> closeListener = player -> { };
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
        @NotNull SkillOwnershipService ownershipService,
        @NotNull SkillPermissionService permissionService
    ) {
        this.plugin = plugin;
        this.presetService = presetService;
        this.skillService = skillService;
        this.ownershipService = ownershipService;
        this.permissionService = permissionService;
        this.actionRingDisplay = new SkillActionRingDisplay(plugin);
    }

    /**
     * 武器通常攻撃の予約バインドを解決するサービスを設定します。
     *
     * @param itemWeaponAttackService 主手武器の通常攻撃サービス
     */
    public void setItemWeaponAttackService(@NotNull ItemWeaponAttackService itemWeaponAttackService) {
        this.itemWeaponAttackService = itemWeaponAttackService;
    }

    /**
     * アクションリング表示成功を受け取る listener を設定します。
     *
     * @param listener 表示したプレイヤーを受け取る listener
     */
    public void setOpenListener(@NotNull Consumer<AstPlayer> listener) {
        this.openListener = listener;
    }

    /**
     * アクションリングを閉じた後に実行する listener を設定します。
     *
     * @param listener 表示を閉じたプレイヤーを受け取る listener
     */
    public void setCloseListener(@NotNull Consumer<Player> listener) {
        this.closeListener = listener;
    }

    /**
     * プレイヤーのアクションリング表示状態を切り替えます。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void toggle(@NotNull AstPlayer astPlayer) {
        var player = astPlayer.getBukkit();
        if (isOpen(player)) {
            close(player);
            GuiSound.CLOSE.play(player);
            return;
        }
        open(astPlayer);
    }

    /**
     * 既定の選択案内でアクションリングを表示します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 表示に成功した場合は {@code true}
     */
    public boolean open(@NotNull AstPlayer astPlayer) {
        return open(astPlayer, PlayerMsgId.P_5854);
    }

    /**
     * 指定した選択案内でアクションリングを表示します。
     *
     * @param astPlayer 対象プレイヤー
     * @param selectionInstruction 選択中にリング内へ表示する案内メッセージ
     * @return 表示に成功した場合は {@code true}
     */
    public boolean open(@NotNull AstPlayer astPlayer, @NotNull PlayerMsgId selectionInstruction) {
        var player = astPlayer.getBukkit();
        var playerId = player.getUniqueId();
        if (sessions.containsKey(playerId)) {
            return false;
        }
        if (!hasUsableMainHandWeapon(astPlayer)) {
            GuiSound.DENY.play(player);
            return false;
        }

        PlayerSkillCaster caster = new PlayerSkillCaster(astPlayer);
        RingSession session = RingSession.create(
            player,
            resolveSlots(astPlayer, caster),
            actionRingDisplay,
            skillService,
            caster,
            selectionInstruction
        );
        sessions.put(playerId, session);
        GuiSound.RING_OPEN.play(player);
        openListener.accept(astPlayer);
        ensureTask();
        return true;
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
     * 表示中の選択を確定し、次の左クリックで発動できる状態へ遷移します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 選択を確定できた場合は {@code true}
     */
    public boolean confirmSelected(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        RingSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        if (!hasUsableMainHandWeapon(astPlayer)) {
            GuiSound.DENY.play(player);
            return false;
        }
        if (session.hasConfirmedSelection() || !session.canActivateSelected()) {
            GuiSound.DENY.play(player);
            return false;
        }
        session.confirmSelection();
        GuiSound.RING_SELECT.play(player);
        return true;
    }

    /**
     * 表示中の選択を発動し、リングを閉じます。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void activateSelected(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        RingSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (!hasUsableMainHandWeapon(astPlayer)) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!session.canActivateSelected()) {
            GuiSound.DENY.play(player);
            return;
        }

        if (!session.hasConfirmedSelection()) {
            confirmSelected(astPlayer);
            return;
        }

        if (!sessions.remove(player.getUniqueId(), session)) {
            return;
        }
        String skillId = session.selectedSkillId();
        int selectedSlot = session.selectedIndex + 1;
        destroySession(player, session);
        String skillDisplayName = "未設定";
        if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(skillId)) {
            if (itemWeaponAttackService == null) {
                return;
            }
            itemWeaponAttackService.handleLeftClick(astPlayer, player.getEyeLocation());
            skillDisplayName = "武器通常攻撃";
        } else if (skillId != null && !skillId.isBlank()) {
            LearnedSkillInstance learned = ownershipService.findInstance(astPlayer, skillId);
            SkillDefinition definition = learned == null ? null : skillService.registry().getDefinition(learned.getSkillId());
            skillDisplayName = SkillPresentationUtil.plainName(definition, "未定義スキル");
            SkillCastResult castResult = skillService.castLearnedSkill(
                new PlayerSkillCaster(astPlayer),
                skillId,
                SkillCastTrigger.PLAYER_COMMAND,
                player.getEyeLocation(),
                null,
                List.of()
            );
            if (!castResult.success()) {
                return;
            }
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
            destroySession(player, session);
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
        for (RingSession session : List.copyOf(sessions.values())) {
            if (sessions.remove(session.viewer.getUniqueId(), session)) {
                destroySession(session.viewer, session);
            }
        }
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
                if (sessions.remove(entry.getKey(), entry.getValue())) {
                    destroySession(entry.getValue().viewer, entry.getValue());
                }
                continue;
            }
            if (!entry.getValue().tick(player)) {
                if (sessions.remove(entry.getKey(), entry.getValue())) {
                    destroySession(player, entry.getValue());
                }
            }
        }
        if (sessions.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void destroySession(@NotNull Player player, @NotNull RingSession session) {
        session.destroy();
        closeListener.accept(player);
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
        for (int index = 0; index < SLOT_COUNT; index++) {
            String skillId = index < activeSlots.size() ? activeSlots.get(index) : null;
            if (skillId == null || skillId.isBlank()) {
                slots.add(new SlotView(null, null, "未設定", Material.BARRIER, false, SlotAvailability.UNAVAILABLE));
                continue;
            }
            if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(skillId)) {
                String weaponSkillId = itemWeaponAttackService == null ? null : itemWeaponAttackService.currentLeftClickSkillId(astPlayer);
                SkillDefinition definition = weaponSkillId == null ? null : skillService.registry().getDefinition(weaponSkillId);
                if (definition == null) {
                    slots.add(new SlotView(skillId, null, "武器通常攻撃", Material.BARRIER, false, SlotAvailability.UNAVAILABLE));
                    continue;
                }
                slots.add(new SlotView(
                    skillId,
                    definition,
                    "武器通常攻撃",
                    parseMaterial(definition.getIcon(), Material.IRON_SWORD),
                    true,
                    availabilityFor(skillService.canCast(caster, definition))
                ));
                continue;
            }
            LearnedSkillInstance learned = ownershipService.findInstance(astPlayer, skillId);
            ResolvedLearnedSkill resolved = learned == null ? null : skillService.resolveLearnedSkill(learned);
            SkillDefinition definition = resolved == null ? null : resolved.definition();
            if (definition != null && definition.getKind() != SkillKind.ACTIVE) {
                slots.add(new SlotView(skillId, definition, "設定不可", Material.BARRIER, false, SlotAvailability.UNAVAILABLE));
                continue;
            }
            String displayName = definition == null
                    ? "未習得スキル"
                    : SkillPresentationUtil.legacyName(definition, "未習得スキル");
            boolean owned = learned != null;
            boolean permitted = learned != null && permissionService.isPermitted(astPlayer, learned.getSkillId());
            Material material = owned ? parseMaterial(definition == null ? null : definition.getIcon(), Material.BARRIER) : Material.BARRIER;
            SlotAvailability availability = definition == null || !owned || !permitted
                ? SlotAvailability.UNAVAILABLE
                : availabilityFor(skillService.canCast(caster, resolved));
            slots.add(new SlotView(skillId, definition, displayName, material, owned, availability, resolved));
        }
        return slots;
    }

    /**
     * 現在選択プリセットの左クリックバインドを発動します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void activateLeftClickBind(@NotNull AstPlayer astPlayer) {
        if (!hasUsableMainHandWeapon(astPlayer)) {
            return;
        }
        SkillBindPreset preset = selectedPreset(astPlayer);
        if (preset == null) {
            return;
        }
        String skillId = preset.getLeftClickSkillId();
        if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(skillId)) {
            if (itemWeaponAttackService != null) {
                itemWeaponAttackService.handleLeftClick(astPlayer, astPlayer.getBukkit().getEyeLocation());
            }
            return;
        }
        if (skillId != null && !skillId.isBlank()) {
            skillService.castLearnedSkill(
                new PlayerSkillCaster(astPlayer), skillId, SkillCastTrigger.PLAYER_COMMAND,
                astPlayer.getBukkit().getEyeLocation(), null, List.of()
            );
        }
    }

    /**
     * 現在選択プリセットに左クリック発動可能なバインドがあるかを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 発動候補がある場合は true
     */
    public boolean hasLeftClickBind(@NotNull AstPlayer astPlayer) {
        if (!hasUsableMainHandWeapon(astPlayer)) {
            return false;
        }
        SkillBindPreset preset = selectedPreset(astPlayer);
        if (preset == null || preset.getLeftClickSkillId() == null) {
            return false;
        }
        if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(preset.getLeftClickSkillId())) {
            return itemWeaponAttackService != null && itemWeaponAttackService.hasLeftClickAction(astPlayer);
        }
        LearnedSkillInstance learned = ownershipService.findInstance(astPlayer, preset.getLeftClickSkillId());
        if (learned == null) {
            // 忘却済みでもバインドは保持するため、実行時の所有チェックで P_5809 を通知します。
            return true;
        }
        SkillDefinition definition = skillService.registry().getDefinition(learned.getSkillId());
        // 使用許可の最終判定は SkillService が行い、未許可時は P_5863 を表示します。
        return definition != null && definition.getKind() == SkillKind.ACTIVE;
    }

    private @Nullable SkillBindPreset selectedPreset(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        int selectedPresetIndex = presetService.selectedPresetIndex(accountId);
        return presetService.getPresets(accountId).stream()
            .filter(preset -> preset.isUnlocked() && preset.getPresetIndex() == selectedPresetIndex)
            .findFirst()
            .orElse(null);
    }

    private boolean hasUsableMainHandWeapon(@NotNull AstPlayer astPlayer) {
        return itemWeaponAttackService != null
            && itemWeaponAttackService.hasUsableMainHandWeapon(astPlayer);
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
        Material material = MaterialNameResolver.match(value);
        return material == null ? fallback : material;
    }

    private record SlotView(
        String skillId,
        SkillDefinition definition,
        @NotNull String name,
        @NotNull Material material,
        boolean owned,
        @NotNull SlotAvailability availability,
        ResolvedLearnedSkill learnedResolution
    ) {
        private SlotView(
            String skillId,
            SkillDefinition definition,
            @NotNull String name,
            @NotNull Material material,
            boolean owned,
            @NotNull SlotAvailability availability
        ) {
            this(skillId, definition, name, material, owned, availability, null);
        }

        private boolean selectable() {
            // 所持済みだが使用許可を失ったスキルは赤表示のまま選択を許可し、
            // SkillService の統一経路から P_5863 をプレイヤーへ通知します。
            return availability == SlotAvailability.AVAILABLE
                || (availability == SlotAvailability.UNAVAILABLE
                    && skillId != null
                    && (owned && definition != null || !owned));
        }

        private @NotNull SlotView refreshAvailability(
            @NotNull SkillService skillService,
            @NotNull PlayerSkillCaster caster
        ) {
            if (!owned || definition == null || definition.getKind() != SkillKind.ACTIVE) {
                return this;
            }
            SlotAvailability nextAvailability = availabilityFor(learnedResolution == null
                ? skillService.canCast(caster, definition)
                : skillService.canCast(caster, learnedResolution));
            return new SlotView(skillId, definition, name, material, true, nextAvailability, learnedResolution);
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
            String cooldownId = skillService.resolveCooldownId(definition.getId());
            long totalTicks = skillService.getCooldownDurationTicks(caster, cooldownId);
            if (totalTicks <= 0L) {
                totalTicks = learnedResolution == null
                    ? definition.getCooldownTicks()
                    : skillService.resolvedCooldownTicks(caster, learnedResolution);
            }
            totalTicks = Math.max(1L, totalTicks);
            long remainingTicks = Math.min(
                totalTicks,
                skillService.getRemainingCooldownTicks(caster, cooldownId)
            );
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
            if (availability == SlotAvailability.COOLDOWN) {
                return ColorCodeUtil.GRAY;
            }
            if (availability != SlotAvailability.AVAILABLE) {
                return ColorCodeUtil.RED;
            }
            return ColorCodeUtil.GREEN;
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
        private SkillActionRingDisplay.DisplayEntity timerLabel;
        private SkillActionRingDisplay.DisplayEntity instructionLabel;
        private Location renderedCenter;
        private int selectedIndex;
        private int confirmedIndex = -1;
        private RingPhase phase = RingPhase.SELECTING;
        private long phaseElapsedTicks;
        private PlayerMsgId selectionInstruction = PlayerMsgId.P_5854;

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
            @NotNull PlayerSkillCaster caster,
            @NotNull PlayerMsgId selectionInstruction
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
            session.selectionInstruction = selectionInstruction;
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
            timerLabel = actionRingDisplay.text(baseCenter, Component.empty(), 0.60F);
            timerLabel.spawn(player);
            instructionLabel = actionRingDisplay.text(baseCenter, Component.empty(), 0.60F);
            instructionLabel.spawn(player);
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

            boolean layoutChanged = renderedCenter == null
                || !renderedCenter.equals(center)
                || isSelectionAnimationActive();
            if (layoutChanged) {
                renderedCenter = center.clone();
            }
            updateCircle(center, layoutChanged);
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
                if (layoutChanged) {
                    icon.teleport(player, iconLocation);
                }
                actionRingDisplay.updateItem(
                    player,
                    icon,
                    hiddenByConfirmedSelection ? HIDDEN_ITEM : new ItemStack(slot.material()),
                    selected && !hiddenByConfirmedSelection
                );
                if (layoutChanged) {
                    label.teleport(player, labelLocation);
                }
                actionRingDisplay.updateText(
                    player,
                    label,
                    labelComponent(index, slot, selected, hiddenByConfirmedSelection),
                    0.60F
                );
            }
            updateTimer(center, layoutChanged);
            updateInstruction(center, layoutChanged);
            return true;
        }

        private boolean hasConfirmedSelection() {
            return phase == RingPhase.WAITING_CAST;
        }

        private boolean canActivateSelected() {
            return selectedIndex >= 0
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

        private void confirmSelection() {
            confirmedIndex = selectedIndex;
            phase = RingPhase.WAITING_CAST;
            phaseElapsedTicks = 0L;
            if (timerLabel != null) {
                actionRingDisplay.updateText(viewer, timerLabel, Component.empty(), 0.60F);
            }
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
                return -1;
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

        private boolean isSelectionAnimationActive() {
            return phase == RingPhase.WAITING_CAST && phaseElapsedTicks <= SELECT_ANIMATION_TICKS;
        }

        private @NotNull Component labelComponent(
            int index,
            @NotNull SlotView slot,
            boolean selected,
            boolean hidden
        ) {
            if (hidden) {
                return Component.empty();
            }
            Component label = Component.text("[" + (index + 1) + "] ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(legacyComponent(slot.color(selected) + slot.label(skillService, caster)));
            return selected ? label.decorate(TextDecoration.BOLD) : label;
        }

        private void updateCircle(@NotNull Location center, boolean layoutChanged) {
            if (!layoutChanged) {
                return;
            }
            for (int index = 0; index < circleDots.size(); index++) {
                SkillActionRingDisplay.DisplayEntity dot = circleDots.get(index);
                double angle = ((Math.PI * 2.0D) / CIRCLE_DISPLAY_POINTS) * index;
                Vector offset = up.clone().multiply(Math.cos(angle) * RING_RADIUS)
                    .add(right.clone().multiply(Math.sin(angle) * RING_RADIUS));
                dot.teleport(viewer, center.clone().add(offset));
            }
        }

        private void updateTimer(@NotNull Location center, boolean layoutChanged) {
            if (timerLabel == null || phase == RingPhase.WAITING_CAST) {
                return;
            }
            Location timerLocation = center.clone().subtract(up.clone().multiply(0.30D));
            if (layoutChanged) {
                timerLabel.teleport(viewer, timerLocation);
            }
            actionRingDisplay.updateText(viewer, timerLabel, legacyComponent(timerText()), 0.60F);
        }

        private void updateInstruction(@NotNull Location center, boolean layoutChanged) {
            if (instructionLabel == null) {
                return;
            }
            Component instruction;
            Location instructionLocation;
            if (phase == RingPhase.SELECTING) {
                instruction = PlayerMsgResource.getComponent(selectionInstruction.getId());
                instructionLocation = center.clone().add(up.clone().multiply(0.30D));
            } else {
                instruction = PlayerMsgResource.getComponent(PlayerMsgId.P_5855.getId());
                Vector selectedOffset = animatedSlotOffset(confirmedIndex);
                instructionLocation = center.clone().add(selectedOffset).add(up.clone().multiply(0.42D));
            }
            if (layoutChanged) {
                instructionLabel.teleport(viewer, instructionLocation);
            }
            actionRingDisplay.updateText(viewer, instructionLabel, instruction, 0.60F);
        }

        private @NotNull String timerText() {
            long remainingTicks = Math.max(0L, RING_DISPLAY_LIMIT_TICKS - phaseElapsedTicks);
            double remaining = Math.max(0.0D, Math.min(1.0D, (double) remainingTicks / RING_DISPLAY_LIMIT_TICKS));
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
            if (timerLabel != null) {
                timerLabel.destroy(viewer);
                timerLabel = null;
            }
            if (instructionLabel != null) {
                instructionLabel.destroy(viewer);
                instructionLabel = null;
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
