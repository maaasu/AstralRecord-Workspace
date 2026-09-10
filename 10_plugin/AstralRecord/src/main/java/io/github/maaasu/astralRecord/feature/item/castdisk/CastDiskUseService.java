package io.github.maaasu.astralRecord.feature.item.castdisk;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentRequirementService;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** スキルキャストディスクとドッジキャストディスクの個体状態、使用、耐久を扱います。 */
public final class CastDiskUseService {
    public static final long DODGE_DELAY_TICKS = 5L;
    public static final long DODGE_COOLDOWN_MS = 3_000L;

    private final CastDiskTaskScheduler taskScheduler;
    private final Function<UUID, AstPlayer> playerLookup;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final ItemReferenceResolver referenceResolver;
    private final SkillActionRingService actionRingService;
    private final DodgeService dodgeService;
    private final CastDiskGui gui;
    private final Map<UUID, CastDiskTaskScheduler.CastDiskTask> pendingDodgeTasks = new ConcurrentHashMap<>();
    private final DodgeCastDiskReservation dodgeReservation = new DodgeCastDiskReservation();
    private final DodgeCastDiskCooldown dodgeCooldown = new DodgeCastDiskCooldown();

    public CastDiskUseService(
        @NotNull AstralRecord plugin,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull SkillActionRingService actionRingService,
        @NotNull DodgeService dodgeService,
        @NotNull CastDiskGui gui
    ) {
        this(
            (action, delayTicks) -> {
                BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, action, delayTicks);
                return task::cancel;
            },
            playerId -> {
                Player bukkit = Bukkit.getPlayer(playerId);
                return bukkit == null ? null
                    : io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(bukkit);
            },
            inventoryService,
            itemService,
            actionRingService,
            dodgeService,
            gui
        );
    }

    CastDiskUseService(
        @NotNull CastDiskTaskScheduler taskScheduler,
        @NotNull Function<UUID, AstPlayer> playerLookup,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull SkillActionRingService actionRingService,
        @NotNull DodgeService dodgeService,
        @NotNull CastDiskGui gui
    ) {
        this.taskScheduler = taskScheduler;
        this.playerLookup = playerLookup;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.referenceResolver = new ItemReferenceResolver(itemService);
        this.actionRingService = actionRingService;
        this.dodgeService = dodgeService;
        this.gui = gui;
    }

    /** 現在主手がいずれかのキャストディスクか判定します。 */
    public boolean isCurrentCastDisk(@NotNull AstPlayer player) {
        return findCurrentDisk(player) != null;
    }

    /** 現在主手のディスクに応じた右クリック処理を実行します。 */
    public void handleRightClick(@NotNull AstPlayer player) {
        CurrentDisk disk = findCurrentDisk(player);
        if (disk == null) return;
        if (isSkillDisk(disk.model())) {
            openConfiguration(player, disk);
            return;
        }
        beginDodgeCast(player, disk);
    }

    /** 現在主手のスキルキャストディスクで左クリック発動を試行します。 */
    public void handleLeftClick(@NotNull AstPlayer player) {
        CurrentDisk disk = findCurrentDisk(player);
        if (disk == null || !isSkillDisk(disk.model())) return;
        CastDiskSettings settings = CastDiskSettings.read(disk.metadataJson());
        if (!settings.isComplete()) {
            openConfiguration(player, disk);
            return;
        }
        if (!hasUsableWeapon(player, settings.weaponHotbarSlot())) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7170);
            GuiSound.DENY.play(player.getBukkit());
            return;
        }
        if (!hasRemainingDurability(disk)) {
            GuiSound.DENY.play(player.getBukkit());
            return;
        }
        actionRingService.castActionSlotWithHotbarWeapon(
            player,
            settings.actionSlotIndex(),
            settings.weaponHotbarSlot(),
            result -> {
                if (result.success()) {
                    consumeDurability(player, disk);
                }
            }
        );
    }

    /** スキルキャストディスク設定 GUI のクリックを保存して再描画します。 */
    public void handleConfigurationClick(@NotNull Player player, @NotNull CastDiskInventoryHolder holder, int rawSlot) {
        var astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(player);
        if (astPlayer == null || rawSlot < 0 || rawSlot >= CastDiskGui.SIZE) return;
        CurrentDisk disk = findCurrentDisk(astPlayer);
        if (disk == null || !isSkillDisk(disk.model()) || !disk.reference().equipmentInstanceId().equals(holder.equipmentInstanceId())) {
            player.closeInventory();
            GuiSound.DENY.play(player);
            return;
        }
        CastDiskSettings current = CastDiskSettings.read(disk.metadataJson());
        CastDiskSettings updated;
        if (rawSlot >= CastDiskGui.ACTION_SLOT_START
            && rawSlot < CastDiskGui.ACTION_SLOT_START + CastDiskSettings.ACTION_SLOT_COUNT) {
            updated = new CastDiskSettings(rawSlot - CastDiskGui.ACTION_SLOT_START, current.weaponHotbarSlot());
        } else if (rawSlot >= CastDiskGui.WEAPON_SLOT_START
            && rawSlot < CastDiskGui.WEAPON_SLOT_START + CastDiskSettings.WEAPON_HOTBAR_SLOT_COUNT) {
            updated = new CastDiskSettings(current.actionSlotIndex(), rawSlot - CastDiskGui.WEAPON_SLOT_START);
        } else {
            return;
        }
        if (!inventoryService.updateHotbarEquipmentMetadata(
            astPlayer, EquipmentSlot.HAND, holder.equipmentInstanceId(), disk.metadataJson(),
            CastDiskSettings.write(disk.metadataJson(), updated)
        )) {
            GuiSound.DENY.play(player);
            return;
        }
        inventoryService.refreshManagedInventoryUi(astPlayer);
        GuiSound.SELECT.play(player);
        GuiOpenSupport.open(player, gui.create(player, holder.equipmentInstanceId(), updated));
    }

    public void clear(@NotNull UUID playerId) {
        dodgeReservation.clear(playerId);
        CastDiskTaskScheduler.CastDiskTask pending = pendingDodgeTasks.remove(playerId);
        if (pending != null) pending.cancel();
        dodgeCooldown.clear(playerId);
    }

    private void openConfiguration(@NotNull AstPlayer player, @NotNull CurrentDisk disk) {
        GuiOpenSupport.open(player.getBukkit(), gui.create(player.getBukkit(), disk.reference().equipmentInstanceId(), CastDiskSettings.read(disk.metadataJson())));
        GuiSound.OPEN.play(player.getBukkit());
    }

    private void beginDodgeCast(@NotNull AstPlayer player, @NotNull CurrentDisk disk) {
        UUID playerId = player.getBukkit().getUniqueId();
        long now = System.currentTimeMillis();
        if (dodgeCooldown.isActive(playerId, now) || !hasRemainingDurability(disk)) return;
        if (!dodgeService.canBeginCastDiskDodge(player)) return;
        Player bukkit = player.getBukkit();
        long generation = dodgeReservation.replace(playerId);
        DodgeRequest request = new DodgeRequest(
            generation,
            player.getAccount().getUuid(),
            disk.reference().equipmentInstanceId(),
            bukkit.getLocation()
        );
        CastDiskTaskScheduler.CastDiskTask previous = pendingDodgeTasks.remove(playerId);
        if (previous != null) previous.cancel();
        CastDiskTaskScheduler.CastDiskTask task = taskScheduler.schedule(
            () -> executeScheduledDodgeCast(playerId, request), DODGE_DELAY_TICKS);
        pendingDodgeTasks.put(playerId, task);
    }

    /**
     * 右クリックから5tick後に、予約時と同じディスク個体で通常ドッジを実行します。
     *
     * @param playerId 予約したプレイヤー UUID
     * @param request 右クリック時に固定したディスク個体と始点座標
     */
    private void executeScheduledDodgeCast(@NotNull UUID playerId, @NotNull DodgeRequest request) {
        if (!dodgeReservation.consumeIfCurrent(playerId, request.generation())) return;
        pendingDodgeTasks.remove(playerId);
        AstPlayer player = playerLookup.apply(playerId);
        if (player == null || !request.accountId().equals(player.getAccount().getUuid())) return;
        long now = System.currentTimeMillis();
        if (dodgeCooldown.isActive(playerId, now)) return;
        CurrentDisk disk = findCurrentDisk(player);
        if (disk == null || !request.equipmentInstanceId().equals(disk.reference().equipmentInstanceId())
            || !isDodgeDisk(disk.model()) || !hasRemainingDurability(disk)) return;
        if (dodgeService.tryTriggerCastDiskDodge(player, request.startedAtLocation())
            && consumeDurability(player, disk)) {
            dodgeCooldown.start(playerId, now);
        }
    }

    private boolean hasUsableWeapon(@NotNull AstPlayer player, int hotbarSlot) {
        ItemStack itemStack = player.getBukkit().getInventory().getItem(hotbarSlot);
        ItemReference reference = referenceResolver.resolve(itemStack);
        ItemModel model = referenceResolver.resolveItemModel(reference);
        if (model == null || model.getEquipment() == null || model.getEquipment().getSlot() != ItemEquipmentSlot.WEAPON
            || !EquipmentRequirementService.check(player, model.getEquipment()).allowed()) {
            return false;
        }
        EquipmentInstance instance = referenceResolver.resolveEquipmentInstance(reference);
        return instance == null || instance.getDurabilityMax() <= 0 || instance.getDurabilityValue() > 0;
    }

    private boolean consumeDurability(@NotNull AstPlayer player, @NotNull CurrentDisk disk) {
        EquipmentInstance current = referenceResolver.resolveEquipmentInstance(disk.reference());
        if (current == null || current.getDurabilityValue() <= 0) return false;
        int consume = disk.model().getEquipment().getDurability() == null ? 1
            : Math.max(1, disk.model().getEquipment().getDurability().getConsume());
        EquipmentInstance updated = itemService.updateEquipmentDurability(
            current.getEquipmentInstanceId(), Math.max(0, current.getDurabilityValue() - consume),
            player.getAccount().getUuid().toString()
        );
        if (updated == null) return false;
        inventoryService.refreshEquipmentInstanceDisplay(player, updated);
        return true;
    }

    private boolean hasRemainingDurability(@NotNull CurrentDisk disk) {
        EquipmentInstance instance = referenceResolver.resolveEquipmentInstance(disk.reference());
        return instance != null && instance.getDurabilityValue() > 0;
    }

    private @Nullable CurrentDisk findCurrentDisk(@NotNull AstPlayer player) {
        InventoryEntryModel entry = inventoryService.getHotbarEntryInHand(player, EquipmentSlot.HAND);
        ItemReference reference = inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND);
        return currentDisk(reference, entry == null ? null : entry.getMetadataJson());
    }

    private @Nullable CurrentDisk currentDisk(@Nullable ItemReference reference, @Nullable String metadataJson) {
        if (reference == null || !reference.hasEquipmentInstanceId()) return null;
        ItemModel model = referenceResolver.resolveItemModel(reference);
        if (model == null || model.getEquipment() == null || model.getEquipment().getSlot() != ItemEquipmentSlot.TOOL) return null;
        return isSkillDisk(model) || isDodgeDisk(model) ? new CurrentDisk(reference, model, metadataJson) : null;
    }

    private boolean isSkillDisk(@NotNull ItemModel model) {
        return MasterTagIds.Equipment.SKILL_CAST_DISK.equalsIgnoreCase(model.getEquipment().getTag());
    }

    private boolean isDodgeDisk(@NotNull ItemModel model) {
        return MasterTagIds.Equipment.DODGE_CAST_DISK.equalsIgnoreCase(model.getEquipment().getTag());
    }

    private record CurrentDisk(@NotNull ItemReference reference, @NotNull ItemModel model, @Nullable String metadataJson) { }
    private record DodgeRequest(
        long generation,
        @NotNull UUID accountId,
        @NotNull String equipmentInstanceId,
        @NotNull Location startedAtLocation
    ) { }
}
