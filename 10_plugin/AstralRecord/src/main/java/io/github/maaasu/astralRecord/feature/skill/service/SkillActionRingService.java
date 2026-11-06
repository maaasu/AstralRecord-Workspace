package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.HeadTextureItemStackSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    // テクスチャの 128 px に対しアイコン中心の半径は 42 px、意匠の最大半径は 61 px。
    private static final double OUTLINE_SIZE = RING_RADIUS * 128.0D / 42.0D;
    private static final double OUTLINE_RADIUS = OUTLINE_SIZE * 61.0D / 128.0D;
    private static final double OUTLINE_DEPTH = 0.14D;
    private static final double SELECTION_DEAD_ZONE_SQUARED = Math.pow(Math.sin(Math.toRadians(4.0D)), 2);
    private static final double SELECTION_RELEASE_ZONE_SQUARED = Math.pow(Math.sin(Math.toRadians(3.0D)), 2);
    private static final double SELECTION_BOUNDARY_MARGIN = Math.toRadians(3.0D);
    private static final double VIEW_FOLLOW_DEAD_ZONE_RADIANS = Math.toRadians(30.0D);
    private static final double VIEW_FOLLOW_MAX_LAG_RADIANS = Math.toRadians(40.0D);
    private static final double VIEW_FOLLOW_BLEND = 0.50D;
    private static final double VIEW_FOLLOW_EPSILON_RADIANS = Math.toRadians(0.05D);
    private static final double COLLISION_SAFETY_MARGIN = 0.06D;
    private static final int COLLISION_REFRESH_INTERVAL_TICKS = 4;
    private static final double ITEM_COLLISION_HALF_SIZE = 0.34D;
    private static final double TEXT_COLLISION_HALF_WIDTH = 1.40D;
    private static final double BEDROCK_STATUS_COLLISION_HALF_WIDTH = 0.60D;
    private static final double TEXT_COLLISION_HALF_HEIGHT = 0.24D;
    private static final int COLLISION_PERIMETER_SAMPLES = 12;
    private static final float LABEL_TEXT_SCALE = 0.60F;
    private static final float DETAIL_TEXT_SCALE = 0.44F;
    private static final float RIM_TEXT_SCALE = 0.48F;
    private static final double DETAIL_LABEL_OFFSET = 0.02D;
    private static final double CONFIRMED_LABEL_OFFSET = -0.43D;
    private static final double SCALE_RECOVERY_BLEND = 0.35D;
    private static final double SCALE_RECOVERY_EPSILON = 0.005D;
    private static final double ORIENTATION_EPSILON_SQUARED = 1.0E-6D;
    private static final int TIMER_BAR_LENGTH = ActionRingPanel.TIMER_STEPS;
    private static final int COOLDOWN_BAR_LENGTH = ActionRingPanel.GAUGE_STEPS;
    private static final long UPDATE_INTERVAL_TICKS = 1L;
    private static final long RING_DISPLAY_LIMIT_TICKS = 100L;
    private static final int BEDROCK_CLICK_COOLDOWN_TICKS = 2;
    private static final double SELECTING_BLOCK_BREAK_SPEED = 1024.0D;
    private static final SlotView EMPTY_SLOT = new SlotView(null, null, "", Material.AIR, false, SlotAvailability.UNAVAILABLE);

    private final AstralRecord plugin;
    private final SkillBindPresetService presetService;
    private final SkillService skillService;
    private final SkillOwnershipService ownershipService;
    private final SkillPermissionService permissionService;
    private final ResourcePackService resourcePackService;
    private final SkillActionRingDisplay actionRingDisplay;
    private final Map<UUID, RingSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> suppressedAttackPlayers = ConcurrentHashMap.newKeySet();
    private ItemWeaponAttackService itemWeaponAttackService;
    private @Nullable StatusService statusService;
    private Consumer<AstPlayer> openListener = player -> { };
    private Consumer<Player> closeListener = player -> { };
    private BukkitTask task;

    /**
     * サービスを生成します。
     *
     * @param plugin scheduler とエンティティ生成に使用するプラグイン
     * @param presetService スキルバインド数の取得に使用するサービス
     * @param skillService 発動可否とクールダウンの参照先
     * @param ownershipService 習得スキルの参照先
     * @param permissionService スキル使用許可の参照先
     * @param resourcePackService Java 版のパック適用状態の参照先
     */
    public SkillActionRingService(
        @NotNull AstralRecord plugin,
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillService skillService,
        @NotNull SkillOwnershipService ownershipService,
        @NotNull SkillPermissionService permissionService,
        @NotNull ResourcePackService resourcePackService
    ) {
        this.plugin = plugin;
        this.presetService = presetService;
        this.skillService = skillService;
        this.ownershipService = ownershipService;
        this.permissionService = permissionService;
        this.resourcePackService = resourcePackService;
        this.actionRingDisplay = new SkillActionRingDisplay();
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
     * キャストディスクが一時的に切り替える武器をステータスへ反映するサービスを設定します。
     *
     * @param statusService 主手切替前後のステータス再計算に使用するサービス
     */
    public void setStatusService(@NotNull StatusService statusService) {
        this.statusService = statusService;
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
        List<String> bindings = ringBindings(astPlayer);
        int configuredCount = configuredCount(bindings);
        if (configuredCount == 0) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().sendClickable(player, PlayerMsgId.P_5856, "/skill gui");
            return;
        }
        if (!hasUsableMainHandWeapon(astPlayer)) {
            GuiSound.DENY.play(player);
            return;
        }

        PlayerSkillCaster caster = new PlayerSkillCaster(astPlayer);
        List<SlotView> slots = resolveSlots(astPlayer, caster, bindings);
        if (configuredCount == 1) {
            SlotView onlySlot = slots.stream().filter(SlotView::configured).findFirst().orElseThrow();
            castSlot(astPlayer, onlySlot, 1, 1);
            return;
        }
        open(astPlayer, PlayerMsgId.P_5854, slots, caster);
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
     * <p>表示開始時に本人の inventory を再送し、長押し選択設定が有効な場合の
     * 選択中武器のクライアント表示を同期します。</p>
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

        List<String> bindings = ringBindings(astPlayer);
        if (configuredCount(bindings) < 2) {
            return false;
        }
        PlayerSkillCaster caster = new PlayerSkillCaster(astPlayer);
        return open(astPlayer, selectionInstruction, resolveSlots(astPlayer, caster, bindings), caster);
    }

    private boolean open(
        @NotNull AstPlayer astPlayer,
        @NotNull PlayerMsgId selectionInstruction,
        @NotNull List<SlotView> slots,
        @NotNull PlayerSkillCaster caster
    ) {
        Player player = astPlayer.getBukkit();
        UUID playerId = player.getUniqueId();
        if (sessions.containsKey(playerId)) {
            return false;
        }
        RingSession session = RingSession.create(
            player,
            slots,
            actionRingDisplay,
            skillService,
            caster,
            astPlayer.isBedrock() || resourcePackService.hasManagedJavaPack(playerId),
            astPlayer.isBedrock(),
            !astPlayer.isBedrock() && resourcePackService.hasActionRingPanel(playerId, SkillIconCatalog.revision()),
            selectionInstruction
        );
        sessions.put(playerId, session);
        GuiSound.RING_OPEN.play(player);
        openListener.accept(astPlayer);
        if (player.isOnline()) {
            player.updateInventory();
        }
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
     * プレイヤーが選択確定後の発動待ちかを返します。
     *
     * @param player 対象プレイヤー
     * @return 発動待ちのリング session がある場合は {@code true}
     */
    public boolean isWaitingForCast(@NotNull Player player) {
        RingSession session = sessions.get(player.getUniqueId());
        return session != null && session.hasConfirmedSelection();
    }

    /**
     * 表示中のアクションリングを開いた時点で選択していた hotbar slot を返します。
     *
     * <p>アクションリング表示中は hotbar slot の変更入力をガードするため、
     * パケット表示側がメインスレッド外から Bukkit inventory を読む必要がないよう、
     * リングセッションに保存した値を返します。</p>
     *
     * @param player 対象プレイヤー
     * @return hotbar slot（0-8）、リング非表示時は負値
     */
    public int getSelectedHotbarSlot(@NotNull Player player) {
        RingSession session = sessions.get(player.getUniqueId());
        return session == null ? -1 : session.hotbarSlot();
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
     * 表示中の選択を確定し、確定済みの場合は発動してリングを閉じます。
     * 統合版では同じリング内の左クリックを2 tick間隔で受け付けます。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void activateSelected(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        RingSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (astPlayer.isBedrock()) {
            int currentTick = plugin.getServer().getCurrentTick();
            if (session.lastBedrockClickTick != null
                && currentTick - session.lastBedrockClickTick < BEDROCK_CLICK_COOLDOWN_TICKS) {
                return;
            }
            session.lastBedrockClickTick = currentTick;
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
        SlotView selectedSlot = session.selectedSlot();
        int selectedPosition = session.selectedIndex + 1;
        int slotCount = session.slots.size();
        destroySession(player, session);
        castSlot(astPlayer, selectedSlot, selectedPosition, slotCount);
    }

    private boolean castSlot(
        @NotNull AstPlayer astPlayer,
        @Nullable SlotView slot,
        int selectedPosition,
        int slotCount
    ) {
        if (slot == null) {
            return false;
        }
        Player player = astPlayer.getBukkit();
        String skillId = slot.skillId();
        String skillDisplayName = "未設定";
        if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(skillId)) {
            if (itemWeaponAttackService == null) {
                return false;
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
                return false;
            }
        } else {
            return false;
        }
        GuiSound.RING_CAST.play(player);
        PlayerMessageService.getInstance().send(
            astPlayer,
            PlayerMsgId.P_5807,
            slotCount,
            selectedPosition,
            skillDisplayName
        );
        return true;
    }

    /**
     * 指定プレイヤーのリングを閉じます。
     * <p>表示を破棄した後に本人の inventory を再送し、現在の設定と選択中 hotbar slot に
     * 応じたクライアント専用表示へ再同期します。</p>
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
        if (player.isOnline()) {
            player.updateInventory();
        }
    }

    private @NotNull List<SlotView> resolveSlots(
        @NotNull AstPlayer astPlayer, @NotNull PlayerSkillCaster caster, List<String> bindings
    ) {
        List<SlotView> slots = new ArrayList<>(SLOT_COUNT);
        for (String skillId : bindings) {
            if (skillId == null) {
                slots.add(EMPTY_SLOT);
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
     * 現在選択中のプリセットに設定されているアクションスキル数を返します。
     *
     * <p>スキルの習得状態や発動可否には依存せず、空でないバインド数だけを数えます。</p>
     *
     * @param astPlayer 対象プレイヤー
     * @return 設定済みアクションスキル数
     */
    public int configuredActionCount(@NotNull AstPlayer astPlayer) {
        return configuredCount(ringBindings(astPlayer));
    }

    /**
     * 現在選択中のプリセットにアクションリング選択が必要な数のスキルがあるか返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 2件以上設定されている場合は {@code true}
     */
    public boolean hasMultipleConfiguredActions(@NotNull AstPlayer astPlayer) {
        return configuredActionCount(astPlayer) >= 2;
    }

    private static int configuredCount(List<String> bindings) {
        return (int) bindings.stream().filter(Objects::nonNull).count();
    }

    private @NotNull List<String> ringBindings(@NotNull AstPlayer astPlayer) {
        SkillBindPreset preset = selectedPreset(astPlayer);
        return preset == null ? List.of() : preset.getActiveSkillSlots().subList(0, SLOT_COUNT);
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

    /**
     * 指定したアクションスロットを、指定ホットバー枠の武器を主手として発動します。
     *
     * <p>発動中だけ Bukkit の選択枠を切り替え、必ず元の枠へ復帰します。スキルの解決値は
     * 呼び出し時点の選択プリセットから読み取るため、ディスクにはスキル ID を保存しません。</p>
     *
     * @param astPlayer 対象プレイヤー
     * @param actionSlotIndex アクションスロット番号（0始まり）
     * @param weaponHotbarSlot 使用武器を置いたホットバー番号（0始まり）
     * @return スキル発動が成功した場合は {@code true}
     */
    public boolean castActionSlotWithHotbarWeapon(
        @NotNull AstPlayer astPlayer,
        int actionSlotIndex,
        int weaponHotbarSlot
    ) {
        return castActionSlotWithHotbarWeapon(astPlayer, actionSlotIndex, weaponHotbarSlot, null);
    }

    /**
     * 対象アカウントが現在利用できる発動スキル枠数を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 所持する拡張トークンを反映した発動枠数
     */
    public int activeSkillSlotCount(@NotNull AstPlayer astPlayer) {
        return presetService.activeSkillSlotCount(astPlayer.getAccount().getUuid());
    }

    /**
     * 指定したアクションスロットを武器で発動し、実際のスキル実行完了時に結果を通知します。
     * 詠唱時間がある場合、戻り値は詠唱開始の結果であり、完了通知は後から呼び出されます。
     *
     * @param astPlayer 対象プレイヤー
     * @param actionSlotIndex アクションスロット番号（0始まり）
     * @param weaponHotbarSlot 使用武器を置いたホットバー番号（0始まり）
     * @param completionListener 実行結果の通知先。不要なら null
     * @return スキル発動が開始または即時成功した場合は {@code true}
     */
    public boolean castActionSlotWithHotbarWeapon(
        @NotNull AstPlayer astPlayer,
        int actionSlotIndex,
        int weaponHotbarSlot,
        @Nullable Consumer<SkillCastResult> completionListener
    ) {
        if (actionSlotIndex < 0 || actionSlotIndex >= activeSkillSlotCount(astPlayer)
            || weaponHotbarSlot < 0 || weaponHotbarSlot > 8) {
            return false;
        }
        Player player = astPlayer.getBukkit();
        int originalHotbarSlot = player.getInventory().getHeldItemSlot();
        StatusSnapshot originalStatus = statusService == null ? null : astPlayer.getStatusSnapshot();
        StatusSnapshot temporaryWeaponStatus = null;
        player.getInventory().setHeldItemSlot(weaponHotbarSlot);
        try {
            if (!hasUsableMainHandWeapon(astPlayer)) {
                return false;
            }
            if (statusService != null) {
                statusService.refreshStatus(astPlayer);
                temporaryWeaponStatus = astPlayer.getStatusSnapshot();
            }
            SkillBindPreset preset = selectedPreset(astPlayer);
            if (preset == null || actionSlotIndex >= preset.getActiveSkillSlots().size()) {
                return false;
            }
            String skillId = preset.getActiveSkillSlots().get(actionSlotIndex);
            if (skillId == null || skillId.isBlank()) {
                return false;
            }
            if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(skillId)) {
                if (itemWeaponAttackService == null) {
                    return false;
                }
                SkillCastResult result = itemWeaponAttackService.handleLeftClick(
                    astPlayer, player.getEyeLocation(), completionListener);
                return result.success();
            }
            PlayerSkillCaster caster = new PlayerSkillCaster(astPlayer);
            SkillCastResult result = completionListener == null
                ? skillService.castLearnedSkill(
                    caster,
                    skillId,
                    SkillCastTrigger.PLAYER_COMMAND,
                    player.getEyeLocation(),
                    null,
                    List.of()
                )
                : skillService.castLearnedSkill(
                    caster,
                    skillId,
                    SkillCastTrigger.PLAYER_COMMAND,
                    player.getEyeLocation(),
                    null,
                    List.of(),
                    completionListener
                );
            return result.success();
        } finally {
            player.getInventory().setHeldItemSlot(originalHotbarSlot);
            if (statusService != null) {
                statusService.refreshStatus(astPlayer);
                restoreTemporarilyClampedResources(astPlayer, originalStatus, temporaryWeaponStatus);
            }
            player.updateInventory();
        }
    }

    /**
     * 一時武器の最大値クランプで失われた現在リソースだけを、主手復帰後のスナップショットへ戻します。
     *
     * @param astPlayer 対象プレイヤー
     * @param originalStatus 切替前のステータス
     * @param temporaryWeaponStatus 一時武器へ切替後、発動前のステータス
     */
    private void restoreTemporarilyClampedResources(
        @NotNull AstPlayer astPlayer,
        @Nullable StatusSnapshot originalStatus,
        @Nullable StatusSnapshot temporaryWeaponStatus
    ) {
        if (originalStatus == null || temporaryWeaponStatus == null) {
            return;
        }
        double hpLoss = positiveDifference(originalStatus.getCurrentHp(), temporaryWeaponStatus.getCurrentHp());
        double mpLoss = positiveDifference(originalStatus.getCurrentMp(), temporaryWeaponStatus.getCurrentMp());
        double energyLoss = positiveDifference(
            originalStatus.getCurrentEnergy(), temporaryWeaponStatus.getCurrentEnergy());
        double shieldLoss = positiveDifference(
            originalStatus.getCurrentShield(), temporaryWeaponStatus.getCurrentShield());
        if (hpLoss == 0.0D && mpLoss == 0.0D && energyLoss == 0.0D && shieldLoss == 0.0D) {
            return;
        }

        StatusSnapshot restored = astPlayer.getStatusSnapshot();
        astPlayer.setStatusSnapshot(restored.withCurrentValues(
            restored.getCurrentHp() + hpLoss,
            restored.getCurrentMp() + mpLoss,
            restored.getCurrentEnergy() + energyLoss,
            restored.getCurrentShield() + shieldLoss
        ));
    }

    private double positiveDifference(double original, double temporary) {
        return Math.max(0.0D, original - temporary);
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

        private boolean configured() {
            return skillId != null;
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
            return nextAvailability == availability
                ? this
                : new SlotView(skillId, definition, name, material, true, nextAvailability, learnedResolution);
        }

        private int sprite() {
            if (!configured()) {
                return 0;
            }
            if (definition == null || material == Material.BARRIER) {
                return SkillIconCatalog.sprite("__unavailable__");
            }
            String spriteKey = SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(skillId)
                ? skillId : definition.getId();
            return SkillIconCatalog.sprite(spriteKey);
        }

        private @NotNull String statusText(@Nullable CooldownView cooldown, boolean javaPanel, boolean bedrock) {
            return cooldown == null ? availability.label() : cooldown.text(javaPanel, bedrock);
        }

        private @Nullable CooldownView cooldown(
            @NotNull SkillService skillService,
            @NotNull PlayerSkillCaster caster
        ) {
            if (availability != SlotAvailability.COOLDOWN || definition == null || skillId == null) {
                return null;
            }
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
            return new CooldownView(remainingTicks, totalTicks);
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

    private record CooldownView(long remaining, long total) {
        private int filled() {
            return (int) Math.ceil((double) remaining / total * COOLDOWN_BAR_LENGTH);
        }

        private String text(boolean javaPanel, boolean bedrock) {
            String value = seconds(remaining);
            if (bedrock) {
                value += " / " + seconds(total);
            }
            String label = PlayerMsgResource.format(PlayerMsgId.P_5857.getId(), value);
            if (javaPanel || bedrock) {
                return label;
            }
            int filled = filled();
            return label + " " + ColorCodeUtil.YELLOW + "|".repeat(filled)
                + ColorCodeUtil.DARK_GRAY + "|".repeat(COOLDOWN_BAR_LENGTH - filled);
        }

        private static String seconds(long ticks) {
            long tenths = ticks / 2 + ticks % 2;
            return (tenths / 10) + "." + (tenths % 10);
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
        private Vector normal;
        private Vector right;
        private Vector up;
        private final List<SlotView> slots;
        private final List<ItemStack> iconItemStacks;
        private final long spriteIds;
        private final CooldownView[] cooldownViews = new CooldownView[SLOT_COUNT];
        private final boolean bedrock;
        private final boolean javaPanel;
        private final Player viewer;
        private final SkillActionRingDisplay actionRingDisplay;
        private final SkillService skillService;
        private final PlayerSkillCaster caster;
        private final List<SkillActionRingDisplay.DisplayEntity> icons;
        private final List<SkillActionRingDisplay.DisplayEntity> fallbackRim = new ArrayList<>();
        private final @Nullable SkillActionRingDisplay.Outline outline;
        private final AttributeInstance blockBreakSpeedAttribute;
        private final Double originalBlockBreakSpeed;
        private final int hotbarSlot;
        private SkillActionRingDisplay.DisplayEntity timerLabel;
        private SkillActionRingDisplay.DisplayEntity instructionLabel;
        private SkillActionRingDisplay.DisplayEntity detailLabel;
        private Location renderedCenter;
        private Vector renderedNormal;
        private Vector renderedRight;
        private Vector renderedUp;
        private double renderedScale = Double.NaN;
        private boolean snappingMotion;
        private double layoutScale = 1.0D;
        private double collisionScale = 1.0D;
        private Location collisionEye;
        private Vector collisionNormal;
        private Vector collisionRight;
        private Vector collisionUp;
        private int ticksSinceCollisionCheck;
        private int focusedIndex = -1;
        private int selectedIndex = -1;
        private int renderedSelection = -1;
        private int renderedDimmedSlots = -1;
        private long renderedGauges = -1;
        private int confirmedIndex = -1;
        private Integer lastBedrockClickTick;
        private RingPhase phase = RingPhase.SELECTING;
        private long phaseElapsedTicks;
        private PlayerMsgId selectionInstruction = PlayerMsgId.P_5854;

        private RingSession(
            @NotNull Vector normal,
            @NotNull Vector right,
            @NotNull Vector up,
            @NotNull List<SlotView> slots,
            @NotNull Player viewer,
            @NotNull SkillActionRingDisplay actionRingDisplay,
            @NotNull SkillService skillService,
            @NotNull PlayerSkillCaster caster,
            boolean useOutline,
            boolean bedrock,
            boolean javaPanel,
            int hotbarSlot,
            AttributeInstance blockBreakSpeedAttribute,
            Double originalBlockBreakSpeed
        ) {
            this.normal = normal.clone();
            this.right = right.clone();
            this.up = up.clone();
            this.slots = slots;
            this.iconItemStacks = new ArrayList<>(slots.size());
            this.spriteIds = packSprites(slots);
            this.bedrock = bedrock;
            this.javaPanel = javaPanel;
            this.viewer = viewer;
            this.actionRingDisplay = actionRingDisplay;
            this.skillService = skillService;
            this.caster = caster;
            this.outline = useOutline ? actionRingDisplay.outline(bedrock, javaPanel) : null;
            this.icons = new ArrayList<>(slots.size());
            this.hotbarSlot = hotbarSlot;
            this.blockBreakSpeedAttribute = blockBreakSpeedAttribute;
            this.originalBlockBreakSpeed = originalBlockBreakSpeed;
        }

        private static @NotNull RingSession create(
            @NotNull Player player,
            @NotNull List<SlotView> slots,
            @NotNull SkillActionRingDisplay actionRingDisplay,
            @NotNull SkillService skillService,
            @NotNull PlayerSkillCaster caster,
            boolean useOutline,
            boolean bedrock,
            boolean javaPanel,
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
            AttributeInstance blockBreakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
            Double originalBlockBreakSpeed = null;
            if (blockBreakSpeed != null) {
                originalBlockBreakSpeed = blockBreakSpeed.getBaseValue();
                blockBreakSpeed.setBaseValue(SELECTING_BLOCK_BREAK_SPEED);
            }
            RingSession session = new RingSession(
                normal,
                right,
                up,
                slots,
                player,
                actionRingDisplay,
                skillService,
                caster,
                useOutline,
                bedrock,
                javaPanel,
                player.getInventory().getHeldItemSlot(),
                blockBreakSpeed,
                originalBlockBreakSpeed
            );
            session.selectionInstruction = selectionInstruction;
            session.updateLayout(player);
            session.spawnEntities(player);
            return session;
        }

        private int hotbarSlot() {
            return hotbarSlot;
        }

        private static long packSprites(List<SlotView> slots) {
            long packed = 0;
            for (int index = 0; index < slots.size(); index++) {
                packed |= (long) slots.get(index).sprite() << (8 * index);
            }
            return packed;
        }

        private int spriteAt(int index) {
            return (int) ((spriteIds >>> (8 * index)) & 0xff);
        }

        /**
         * 現在の衝突回避レイアウトで表示 entity を生成します。
         *
         * @param player 表示を受け取るプレイヤー
         */
        private void spawnEntities(@NotNull Player player) {
            Location center = currentCenter();
            for (int index = 0; index < slots.size(); index++) {
                cooldownViews[index] = slots.get(index).cooldown(skillService, caster);
            }
            // 統合版の拡張は一体型モデルの内容を確認してから、不要な個別表示を抑止します。
            updateOutline(center, true);
            for (int index = 0; index < slots.size(); index++) {
                SlotView slot = slots.get(index);
                Location location = iconLocation(center, index);
                boolean separateIcon = slot.configured() && (!javaPanel || spriteAt(index) == 0);
                ItemStack item = separateIcon ? createIconItemStack(slot) : null;
                iconItemStacks.add(item);
                SkillActionRingDisplay.DisplayEntity icon = item != null ? actionRingDisplay.item(
                    location, item, scaledItemScale(slot, false),
                    outline != null ? outline.iconUuid(index) : null
                ) : null;
                if (icon != null) {
                    icon.spawn(player);
                }
                icons.add(icon);
            }
            if (bedrock && outline != null) {
                outline.syncBedrockIcons(player);
            }
            if (bedrock || outline == null) {
                for (int index = 0; index < SLOT_COUNT; index++) {
                    var dot = actionRingDisplay.text(fallbackRimLocation(center, index),
                        fallbackSlotMarker(index), scaledTextScale(RIM_TEXT_SCALE),
                        outline == null ? null : outline.fallbackUuid(index));
                    dot.spawn(player);
                    fallbackRim.add(dot);
                }
            }
            detailLabel = actionRingDisplay.text(detailLabelLocation(center), Component.empty(),
                scaledTextScale(DETAIL_TEXT_SCALE));
            detailLabel.spawn(player);
            if (!javaPanel) {
                timerLabel = actionRingDisplay.text(
                    timerLocation(center), legacyComponent(timerText()), scaledTextScale(LABEL_TEXT_SCALE),
                    outline != null ? outline.fallbackUuid(SLOT_COUNT) : null
                );
                timerLabel.spawn(player);
            }
            instructionLabel = actionRingDisplay.text(
                instructionLocation(center),
                PlayerMsgResource.getComponent(selectionInstruction.getId()),
                scaledTextScale(LABEL_TEXT_SCALE)
            );
            instructionLabel.spawn(player);
        }

        /**
         * スロット定義からアイコンを作成し、PLAYER_HEAD には設定済みのテクスチャを適用します。
         *
         * @param slot 表示するスロット
         * @return リングに表示するアイコンアイテム
         */
        private @NotNull ItemStack createIconItemStack(@NotNull SlotView slot) {
            ItemStack itemStack = new ItemStack(slot.material());
            SkillDefinition definition = slot.definition();
            HeadTextureItemStackSupport.apply(
                itemStack,
                definition == null ? null : definition.getIconTexture()
            );
            return itemStack;
        }

        /**
         * 視線、衝突および選択状態に合わせてリングを更新します。
         *
         * @param player 表示を受け取るプレイヤー
         * @return リング表示を継続する場合は {@code true}
         */
        private boolean tick(@NotNull Player player) {
            Location center = updateLayout(player);
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
                focusedIndex = resolveFocusedIndex(player);
                int nextSelectedIndex = focusedIndex >= 0 && slots.get(focusedIndex).selectable() ? focusedIndex : -1;
                if (nextSelectedIndex != selectedIndex) {
                    selectedIndex = nextSelectedIndex;
                    if (selectedIndex >= 0) {
                        GuiSound.RING_SWITCH.play(player);
                    }
                }
            }

            if (!bedrock) {
                updateMotionSnapping(layoutScale < renderedScale);
            }
            boolean layoutChanged = renderedCenter == null
                || !renderedCenter.equals(center)
                || !renderedNormal.equals(normal)
                || !renderedRight.equals(right)
                || !renderedUp.equals(up)
                || Double.compare(renderedScale, layoutScale) != 0
                || phase == RingPhase.WAITING_CAST && phaseElapsedTicks == 1;
            if (layoutChanged) {
                renderedCenter = center.clone();
                renderedNormal = normal.clone();
                renderedRight = right.clone();
                renderedUp = up.clone();
                renderedScale = layoutScale;
            }
            updateOutline(center, layoutChanged);
            for (int index = 0; index < fallbackRim.size(); index++) {
                var dot = fallbackRim.get(index);
                if (layoutChanged) {
                    dot.teleport(player, fallbackRimLocation(center, index));
                }
                actionRingDisplay.updateText(player, dot, fallbackSlotMarker(index), scaledTextScale(RIM_TEXT_SCALE));
            }
            for (int index = 0; index < slots.size(); index++) {
                if (phase == RingPhase.WAITING_CAST && index != confirmedIndex) {
                    continue;
                }
                SlotView slot = slots.get(index);
                boolean selected = index == selectedIndex && slot.selectable();
                SkillActionRingDisplay.DisplayEntity icon = icons.get(index);
                if (icon != null) {
                    if (layoutChanged) {
                        icon.teleport(player, iconLocation(center, index));
                    }
                    actionRingDisplay.updateItem(player, icon, iconItemStacks.get(index), selected, scaledItemScale(slot, selected));
                }
            }
            updateDetailLabel(center, layoutChanged);
            updateTimer(center, layoutChanged);
            updateInstruction(center, layoutChanged);
            return true;
        }

        private void updateMotionSnapping(boolean snap) {
            if (snappingMotion == snap) {
                return;
            }
            snappingMotion = snap;
            if (outline != null) {
                outline.setMotionSnapping(viewer, snap);
            }
            for (var icon : icons) {
                if (icon != null) {
                    icon.setMotionSnapping(viewer, snap);
                }
            }
            for (var dot : fallbackRim) {
                dot.setMotionSnapping(viewer, snap);
            }
            if (timerLabel != null) {
                timerLabel.setMotionSnapping(viewer, snap);
            }
            detailLabel.setMotionSnapping(viewer, snap);
            instructionLabel.setMotionSnapping(viewer, snap);
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
                SlotView slot = slots.get(index).refreshAvailability(skillService, caster);
                slots.set(index, slot);
                cooldownViews[index] = slot.cooldown(skillService, caster);
            }
        }

        /**
         * 現在の選択を確定し、残り時間ラベルを非表示にします。
         */
        private void confirmSelection() {
            confirmedIndex = selectedIndex;
            phase = RingPhase.WAITING_CAST;
            phaseElapsedTicks = 0L;
            ticksSinceCollisionCheck = COLLISION_REFRESH_INTERVAL_TICKS;
            for (int index = 0; index < slots.size(); index++) {
                if (index != confirmedIndex) {
                    SkillActionRingDisplay.DisplayEntity icon = icons.get(index);
                    if (icon != null) {
                        icon.destroy(viewer);
                    }
                }
            }
            if (timerLabel != null) {
                actionRingDisplay.updateText(viewer, timerLabel, Component.empty(), scaledTextScale(LABEL_TEXT_SCALE));
            }
        }

        private @Nullable SlotView selectedSlot() {
            if (confirmedIndex < 0 || confirmedIndex >= slots.size()) {
                return null;
            }
            return slots.get(confirmedIndex);
        }

        /**
         * 視点追従と衝突回避を反映した現在のリング中心を更新します。
         * 視点・姿勢の変更時は即時、静止中は4 tickごとに衝突を再確認します。
         *
         * @param player リングを表示しているプレイヤー
         * @return 反映後のリング中心
         */
        private @NotNull Location updateLayout(@NotNull Player player) {
            Location eye = player.getEyeLocation();
            updateOrientation(eye.getDirection());
            eye.setYaw(0);
            eye.setPitch(0);
            if (++ticksSinceCollisionCheck >= COLLISION_REFRESH_INTERVAL_TICKS
                || collisionEye == null
                || !collisionEye.equals(eye)
                || !collisionNormal.equals(normal)
                || !collisionRight.equals(right)
                || !collisionUp.equals(up)) {
                collisionScale = resolveLayoutScale(eye);
                if (collisionEye == null) {
                    layoutScale = collisionScale;
                }
                collisionEye = eye.clone();
                collisionNormal = normal.clone();
                collisionRight = right.clone();
                collisionUp = up.clone();
                ticksSinceCollisionCheck = 0;
            }
            // 手前への退避はすぐ反映し、障害物が外れたときだけ段階的に戻します。
            double recovery = collisionScale - layoutScale;
            layoutScale = recovery <= SCALE_RECOVERY_EPSILON
                ? collisionScale : layoutScale + recovery * SCALE_RECOVERY_BLEND;
            return currentCenter(eye);
        }

        /**
         * 現在のレイアウト倍率でリング中心を返します。
         *
         * @return リング中心
         */
        private @NotNull Location currentCenter() {
            Location eye = viewer.getEyeLocation();
            eye.setYaw(0);
            eye.setPitch(0);
            return currentCenter(eye);
        }

        /**
         * 指定した視点位置と現在のレイアウト倍率からリング中心を返します。
         *
         * @param eye 視点位置
         * @return リング中心
         */
        private @NotNull Location currentCenter(@NotNull Location eye) {
            return eye.clone().add(normal.clone().multiply(RING_DISTANCE * layoutScale));
        }

        /**
         * 選択に必要な視線移動を残しつつ、超過した視線回転だけリングの向きへ反映します。
         *
         * @param direction 現在の視線方向
         */
        private void updateOrientation(@NotNull Vector direction) {
            if (direction.lengthSquared() < ORIENTATION_EPSILON_SQUARED) {
                return;
            }
            Vector view = direction.clone().normalize();
            double dot = Math.clamp(normal.dot(view), -1.0D, 1.0D);
            double angle = Math.acos(dot);
            double excess = angle - VIEW_FOLLOW_DEAD_ZONE_RADIANS;
            if (excess <= VIEW_FOLLOW_EPSILON_RADIANS) {
                return;
            }

            Vector axis = normal.clone().crossProduct(view);
            if (axis.lengthSquared() < ORIENTATION_EPSILON_SQUARED) {
                axis = fallbackRotationAxis();
            } else {
                axis.normalize();
            }
            double rotation = Math.max(excess * VIEW_FOLLOW_BLEND, angle - VIEW_FOLLOW_MAX_LAG_RADIANS);
            normal = rotateAroundAxis(normal, axis, rotation).normalize();
            right = rotateAroundAxis(right, axis, rotation);
            right.subtract(normal.clone().multiply(right.dot(normal)));
            if (right.lengthSquared() < ORIENTATION_EPSILON_SQUARED) {
                right = fallbackRotationAxis();
            } else {
                right.normalize();
            }
            up = right.clone().crossProduct(normal).normalize();
        }

        /**
         * 反対向きなど、視線との外積から回転軸を作れない場合の直交軸を返します。
         *
         * @return 現在のリング法線に直交する単位ベクトル
         */
        private @NotNull Vector fallbackRotationAxis() {
            Vector axis = right.clone().subtract(normal.clone().multiply(right.dot(normal)));
            if (axis.lengthSquared() < ORIENTATION_EPSILON_SQUARED) {
                axis = normal.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
            }
            if (axis.lengthSquared() < ORIENTATION_EPSILON_SQUARED) {
                axis = normal.clone().crossProduct(new Vector(1.0D, 0.0D, 0.0D));
            }
            return axis.normalize();
        }

        /**
         * Rodrigues の回転公式でベクトルを指定軸の周囲に回転します。
         *
         * @param vector 回転するベクトル
         * @param axis 正規化済みの回転軸
         * @param radians 回転角（ラジアン）
         * @return 回転後のベクトル
         */
        private @NotNull Vector rotateAroundAxis(@NotNull Vector vector, @NotNull Vector axis, double radians) {
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            return vector.clone().multiply(cos)
                .add(axis.clone().crossProduct(vector).multiply(sin))
                .add(axis.clone().multiply(axis.dot(vector) * (1.0D - cos)));
        }

        /**
         * リング全体が衝突ブロックの手前に収まる最大倍率を返します。
         *
         * <p>中心、円周、アイコンおよび文字表示の代表的な外周を視点から ray trace し、
         * 非通過ブロックの collision shape に当たる場合だけ縮小します。</p>
         *
         * @param eye 視点位置
         * @return 0.0 から 1.0 のレイアウト倍率
         */
        private double resolveLayoutScale(@NotNull Location eye) {
            if (eye.getWorld() == null) {
                return 0.0D;
            }
            double scale = collisionFreeScale(eye, normal.clone().multiply(RING_DISTANCE), 1.0D);
            Vector outlineCenter = normal.clone().multiply(RING_DISTANCE + OUTLINE_DEPTH);
            for (int index = 0; index < COLLISION_PERIMETER_SAMPLES; index++) {
                double angle = ((Math.PI * 2.0D) / COLLISION_PERIMETER_SAMPLES) * index;
                Vector offset = outlineCenter.clone()
                    .add(up.clone().multiply(Math.cos(angle) * OUTLINE_RADIUS))
                    .add(right.clone().multiply(Math.sin(angle) * OUTLINE_RADIUS));
                scale = collisionFreeScale(eye, offset, scale);
            }
            Vector centerOffset = normal.clone().multiply(RING_DISTANCE);
            if (phase == RingPhase.WAITING_CAST) {
                scale = collisionFreeBoxScale(eye, centerOffset, ITEM_COLLISION_HALF_SIZE, ITEM_COLLISION_HALF_SIZE, ITEM_COLLISION_HALF_SIZE, scale);
            } else {
                for (int index = 0; index < slots.size(); index++) {
                    if (slots.get(index).configured()) {
                        Vector iconOffset = centerOffset.clone().add(slotOffset(index));
                        scale = collisionFreeBoxScale(eye, iconOffset, ITEM_COLLISION_HALF_SIZE, ITEM_COLLISION_HALF_SIZE, ITEM_COLLISION_HALF_SIZE, scale);
                    }
                }
                if (!javaPanel) {
                    Vector timerPosition = centerOffset.clone().subtract(up.clone().multiply(0.30D));
                    scale = collisionFreeBoxScale(eye, timerPosition, TEXT_COLLISION_HALF_WIDTH, TEXT_COLLISION_HALF_HEIGHT, 0.02D, scale);
                }
            }
            if (!bedrock || phase == RingPhase.SELECTING) {
                Vector labelPosition = centerOffset.clone().add(up.clone().multiply(detailLabelOffset()));
                double halfWidth = bedrock ? BEDROCK_STATUS_COLLISION_HALF_WIDTH : TEXT_COLLISION_HALF_WIDTH;
                scale = collisionFreeBoxScale(eye, labelPosition, halfWidth, TEXT_COLLISION_HALF_HEIGHT, 0.02D, scale);
            }
            Vector instructionPosition = centerOffset.clone().add(up.clone().multiply(instructionOffset()));
            return collisionFreeBoxScale(
                eye,
                instructionPosition,
                TEXT_COLLISION_HALF_WIDTH,
                TEXT_COLLISION_HALF_HEIGHT,
                0.02D,
                scale
            );
        }

        /**
         * 指定位置を中心とするリング座標系の箱が衝突しない最大倍率を返します。
         *
         * @param eye 視点位置
         * @param center 箱の中心となる未縮小の視点相対位置
         * @param horizontalHalfWidth right 軸方向の半幅
         * @param verticalHalfHeight up 軸方向の半幅
         * @param depthHalfWidth normal 軸方向の半幅
         * @param currentScale 現在までに判明している最大倍率
         * @return 箱を考慮した最大倍率
         */
        private double collisionFreeBoxScale(
            @NotNull Location eye,
            @NotNull Vector center,
            double horizontalHalfWidth,
            double verticalHalfHeight,
            double depthHalfWidth,
            double currentScale
        ) {
            double scale = collisionFreeScale(eye, center, currentScale);
            for (int horizontalSign : new int[]{-1, 1}) {
                for (int verticalSign : new int[]{-1, 1}) {
                    for (int depthSign : new int[]{-1, 1}) {
                        Vector corner = center.clone()
                            .add(right.clone().multiply(horizontalHalfWidth * horizontalSign))
                            .add(up.clone().multiply(verticalHalfHeight * verticalSign))
                            .add(normal.clone().multiply(depthHalfWidth * depthSign));
                        scale = collisionFreeScale(eye, corner, scale);
                    }
                }
            }
            return scale;
        }

        /**
         * 指定した未縮小の視点相対位置が衝突ブロックの手前に収まる最大倍率を返します。
         *
         * @param eye 視点位置
         * @param offset 未縮小の視点相対位置
         * @param currentScale 現在までに判明している最大倍率
         * @return この位置を反映した最大倍率
         */
        private double collisionFreeScale(@NotNull Location eye, @NotNull Vector offset, double currentScale) {
            double length = offset.length();
            if (currentScale <= 0.0D || length < ORIENTATION_EPSILON_SQUARED) {
                return Math.max(0.0D, currentScale);
            }
            RayTraceResult hit = eye.getWorld().rayTraceBlocks(
                eye,
                offset.clone().multiply(1.0D / length),
                length,
                FluidCollisionMode.NEVER,
                true
            );
            if (hit == null || hit.getHitBlock() == null
                || hit.getHitBlock().isPassable()
                || hit.getHitBlock().getCollisionShape().getBoundingBoxes().isEmpty()) {
                return currentScale;
            }
            double hitDistance = hit.getHitPosition().distance(eye.toVector());
            return Math.min(currentScale, Math.max(0.0D, (hitDistance - COLLISION_SAFETY_MARGIN) / length));
        }

        private int resolveFocusedIndex(@NotNull Player player) {
            Vector view = player.getEyeLocation().getDirection().normalize();
            Vector projected = view.subtract(normal.clone().multiply(view.dot(normal)));
            double deadZone = focusedIndex >= 0 ? SELECTION_RELEASE_ZONE_SQUARED : SELECTION_DEAD_ZONE_SQUARED;
            if (projected.lengthSquared() < deadZone) {
                return -1;
            }
            double angle = Math.atan2(projected.dot(right), projected.dot(up));
            double unit = (Math.PI * 2.0D) / slots.size();
            if (focusedIndex >= 0) {
                double offset = Math.IEEEremainder(angle - focusedIndex * unit, 2.0D * Math.PI);
                if (Math.abs(offset) <= unit / 2.0D + SELECTION_BOUNDARY_MARGIN) {
                    return focusedIndex;
                }
            }
            int index = (int) Math.round(angle / unit);
            int resolved = Math.floorMod(index, slots.size());
            return slots.get(resolved).configured() ? resolved : -1;
        }

        private @NotNull Vector slotOffset(int index) {
            double angle = ((Math.PI * 2.0D) / slots.size()) * index;
            return up.clone().multiply(Math.cos(angle) * RING_RADIUS)
                .add(right.clone().multiply(Math.sin(angle) * RING_RADIUS));
        }

        /**
         * 指定スロットのアイコン表示位置を返します。
         *
         * @param center 現在のリング中心
         * @param index スロット番号
         * @return アイコン表示位置
         */
        private @NotNull Location iconLocation(@NotNull Location center, int index) {
            if (phase == RingPhase.WAITING_CAST && index == confirmedIndex) {
                return center.clone();
            }
            return center.clone().add(slotOffset(index).multiply(layoutScale));
        }

        private Location detailLabelLocation(Location center) {
            return center.clone().add(up.clone().multiply(detailLabelOffset() * layoutScale));
        }

        private double detailLabelOffset() {
            return phase == RingPhase.SELECTING ? DETAIL_LABEL_OFFSET : CONFIRMED_LABEL_OFFSET;
        }

        /**
         * 残り時間ラベルの表示位置を返します。
         *
         * @param center 現在のリング中心
         * @return 残り時間ラベルの表示位置
         */
        private @NotNull Location timerLocation(@NotNull Location center) {
            return center.clone().subtract(up.clone().multiply(0.30D * layoutScale));
        }

        /**
         * 現在のフェーズに対応する案内ラベルの表示位置を返します。
         *
         * @param center 現在のリング中心
         * @return 案内ラベルの表示位置
         */
        private @NotNull Location instructionLocation(@NotNull Location center) {
            return center.clone().add(up.clone().multiply(instructionOffset() * layoutScale));
        }

        private double instructionOffset() {
            return phase == RingPhase.SELECTING ? 0.35D : 0.46D;
        }

        /**
         * 現在のレイアウト倍率をアイコンの基本倍率へ反映します。
         *
         * @return アイコンの表示倍率
         */
        private float scaledItemScale(SlotView slot, boolean selected) {
            double base = slot.availability() == SlotAvailability.COOLDOWN ? 0.52D : 0.65D;
            return (float) ((selected ? 0.73D : base) * layoutScale);
        }

        /**
         * 現在のレイアウト倍率を文字の基本倍率へ反映します。
         *
         * @param baseScale 衝突回避前の文字倍率
         * @return 文字の表示倍率
         */
        private float scaledTextScale(float baseScale) {
            return (float) (baseScale * layoutScale);
        }

        private Component fallbackSlotMarker(int index) {
            if (phase == RingPhase.WAITING_CAST && index != confirmedIndex) {
                return Component.empty();
            }
            SlotView slot = slots.get(index);
            if (selectedIndex == index) {
                return Component.text("◆", NamedTextColor.GOLD);
            }
            return Component.text(slot.configured() ? "◆" : "◇",
                slot.availability() == SlotAvailability.AVAILABLE ? NamedTextColor.AQUA : NamedTextColor.GRAY);
        }

        private void updateDetailLabel(Location center, boolean layoutChanged) {
            if (detailLabel == null) {
                return;
            }
            if (layoutChanged) {
                detailLabel.teleport(viewer, detailLabelLocation(center));
            }
            Component text = Component.empty();
            if (focusedIndex >= 0) {
                SlotView slot = slots.get(focusedIndex);
                String status = slot.statusText(cooldownViews[focusedIndex], javaPanel, bedrock);
                String detail = status;
                if (!bedrock) {
                    detail = status.isBlank() ? slot.name() : slot.name() + "\n" + status;
                }
                if (!detail.isBlank()) {
                    text = legacyComponent(slot.color(focusedIndex == selectedIndex) + detail);
                }
            }
            actionRingDisplay.updateText(viewer, detailLabel, text, scaledTextScale(DETAIL_TEXT_SCALE));
        }

        private void updateOutline(@NotNull Location center, boolean layoutChanged) {
            if (outline == null) {
                return;
            }
            int dimmedSlots = 0;
            long gauges = 0;
            if (phase == RingPhase.SELECTING) {
                for (int index = 0; index < slots.size(); index++) {
                    SlotView slot = slots.get(index);
                    if (slot.configured() && slot.availability() != SlotAvailability.AVAILABLE) {
                        dimmedSlots |= 1 << index;
                    }
                    CooldownView view = cooldownViews[index];
                    if (view != null) {
                        gauges |= (long) view.filled() << (4 * index);
                    }
                }
                gauges |= (long) timerFilled() << 32;
            } else {
                gauges = ((long) (confirmedIndex + 1) << 37) | ((long) spriteAt(confirmedIndex) << 41);
            }
            int selected = selectedIndex + 1;
            boolean panelChanged = (bedrock || javaPanel) && gauges != renderedGauges;
            if (layoutChanged || selected != renderedSelection || dimmedSlots != renderedDimmedSlots || panelChanged) {
                Location location = center.clone().add(normal.clone().multiply(OUTLINE_DEPTH * layoutScale));
                outline.update(viewer, location, normal, right, up, (float) (OUTLINE_SIZE * layoutScale),
                    selected, dimmedSlots, new ActionRingPanel(spriteIds, gauges));
                renderedSelection = selected;
                renderedDimmedSlots = dimmedSlots;
                renderedGauges = gauges;
            }
        }

        private Location fallbackRimLocation(Location center, int index) {
            double angle = 2 * Math.PI * index / SLOT_COUNT;
            return center.clone()
                .add(up.clone().multiply(Math.cos(angle) * OUTLINE_RADIUS * layoutScale))
                .add(right.clone().multiply(Math.sin(angle) * OUTLINE_RADIUS * layoutScale));
        }

        /**
         * 残り時間ラベルの位置と文字倍率を更新します。
         *
         * @param center 現在のリング中心
         * @param layoutChanged レイアウトが変化した場合は {@code true}
         */
        private void updateTimer(@NotNull Location center, boolean layoutChanged) {
            if (timerLabel == null || phase == RingPhase.WAITING_CAST) {
                return;
            }
            Location timerLocation = timerLocation(center);
            if (layoutChanged) {
                timerLabel.teleport(viewer, timerLocation);
            }
            actionRingDisplay.updateText(viewer, timerLabel, legacyComponent(timerText()), scaledTextScale(LABEL_TEXT_SCALE));
        }

        /**
         * 操作案内ラベルの位置と文字倍率を更新します。
         *
         * @param center 現在のリング中心
         * @param layoutChanged レイアウトが変化した場合は {@code true}
         */
        private void updateInstruction(@NotNull Location center, boolean layoutChanged) {
            if (instructionLabel == null) {
                return;
            }
            Component instruction;
            if (phase == RingPhase.SELECTING) {
                instruction = PlayerMsgResource.getComponent(selectionInstruction.getId());
            } else {
                instruction = PlayerMsgResource.getComponent(PlayerMsgId.P_5855.getId());
            }
            Location instructionLocation = instructionLocation(center);
            if (layoutChanged) {
                instructionLabel.teleport(viewer, instructionLocation);
            }
            actionRingDisplay.updateText(viewer, instructionLabel, instruction, scaledTextScale(LABEL_TEXT_SCALE));
        }

        private int timerFilled() {
            long remainingTicks = Math.max(0L, RING_DISPLAY_LIMIT_TICKS - phaseElapsedTicks);
            return (int) Math.round((double) remainingTicks / RING_DISPLAY_LIMIT_TICKS * TIMER_BAR_LENGTH);
        }

        private @NotNull String timerText() {
            int filled = timerFilled();
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
                if (icon != null) {
                    icon.destroy(viewer);
                }
            }
            icons.clear();
            if (detailLabel != null) {
                detailLabel.destroy(viewer);
                detailLabel = null;
            }
            for (var dot : fallbackRim) {
                dot.destroy(viewer);
            }
            fallbackRim.clear();
            if (outline != null) {
                outline.destroy(viewer);
            }
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

    }
}
