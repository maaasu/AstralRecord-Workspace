package io.github.maaasu.astralRecord.feature.gathering.service;

import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition.GatheringSound;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringInstance;
import io.github.maaasu.astralRecord.feature.gathering.repository.GatheringDefinitionRepository;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GatheringService {
    private static final double TARGET_DISTANCE = 5.5D;
    private static final double TARGET_RADIUS_SQ = 0.85D * 0.85D;
    private static final String DROP_SOURCE = "gathering_drop";

    private final Plugin plugin;
    private final GatheringDefinitionRepository definitionRepository;
    private final MobDropService dropService;
    private final ItemService itemService;
    private MobDropPresentationService dropPresentationService;
    private QuestService questService;
    private final Map<String, GatheringDefinition> definitions = new LinkedHashMap<>();
    private final Map<UUID, GatheringInstance> instances = new LinkedHashMap<>();
    private final Map<UUID, MiningSession> sessions = new HashMap<>();
    private GatheringVisualizer visualizer;

    public GatheringService(
            @NotNull Plugin plugin,
            @NotNull GatheringDefinitionRepository definitionRepository,
            @NotNull MobDropService dropService,
            @NotNull ItemService itemService,
            @Nullable MobDropPresentationService dropPresentationService
    ) {
        this.plugin = plugin;
        this.definitionRepository = definitionRepository;
        this.dropService = dropService;
        this.itemService = itemService;
        this.dropPresentationService = dropPresentationService;
    }

    public void setDropPresentationService(@NotNull MobDropPresentationService dropPresentationService) {
        this.dropPresentationService = dropPresentationService;
    }

    public void setQuestService(@NotNull QuestService questService) {
        this.questService = questService;
    }

    public int loadAll() {
        clearInstances();
        definitions.clear();
        for (GatheringDefinition definition : definitionRepository.findAll()) {
            definitions.put(definition.id(), definition);
        }
        return definitions.size();
    }

    public void start() {
        if (visualizer == null) {
            visualizer = new GatheringVisualizer(plugin, this);
        }
        visualizer.start();
    }

    public void stop() {
        sessions.clear();
        if (visualizer != null) {
            visualizer.stop();
            visualizer = null;
        }
        instances.clear();
    }

    public @Nullable GatheringInstance spawn(@NotNull String gatheringId, @NotNull Location location) {
        GatheringDefinition definition = definitions.get(stripPrefix(gatheringId));
        if (definition == null || location.getWorld() == null) {
            return null;
        }
        GatheringInstance instance = new GatheringInstance(UUID.randomUUID(), definition, blockCenter(location));
        instances.put(instance.instanceId(), instance);
        return instance;
    }

    public void destroy(@NotNull UUID instanceId) {
        GatheringInstance removed = instances.remove(instanceId);
        if (removed != null && visualizer != null) {
            visualizer.remove(instanceId);
        }
    }

    /**
     * 現在出現している採集オブジェクトと採集中セッションをすべて破棄します。
     * 定義やスポナーを再読み込みする前に呼び出すことで、旧 tracking の採集オブジェクトが
     * reload 後のスポナー上限判定に残らないようにします。
     */
    public void clearInstances() {
        sessions.clear();
        if (visualizer != null) {
            for (UUID instanceId : List.copyOf(instances.keySet())) {
                visualizer.remove(instanceId);
            }
        }
        instances.clear();
    }

    public boolean hasDefinition(@NotNull String gatheringId) {
        return definitions.containsKey(stripPrefix(gatheringId));
    }

    public @NotNull Collection<String> getLoadedGatheringIds() {
        return List.copyOf(definitions.keySet());
    }

    public @NotNull Collection<GatheringInstance> getInstances() {
        return List.copyOf(instances.values());
    }

    public @Nullable GatheringInstance getInstance(@NotNull UUID instanceId) {
        return instances.get(instanceId);
    }

    public boolean isMining(@NotNull Player player, @NotNull UUID instanceId) {
        MiningSession session = sessions.get(player.getUniqueId());
        return session != null && session.instanceId.equals(instanceId);
    }

    public boolean startMining(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return false;
        }

        GatheringInstance target = findTargeted(player);
        if (target == null || !canUseCurrentTool(player, target.definition())) {
            return false;
        }

        sessions.remove(player.getUniqueId());
        if (target.activePlayerId() != null && !target.activePlayerId().equals(player.getUniqueId())) {
            target.resetHealth();
        }

        target.activePlayerId(player.getUniqueId());
        applyMiningDamage(player, target);
        if (!instances.containsKey(target.instanceId())) {
            return true;
        }

        MiningSession session = new MiningSession(player.getUniqueId(), target.instanceId(), currentToolSignature(player));
        sessions.put(player.getUniqueId(), session);
        return true;
    }

    public @Nullable GatheringInstance findTargeted(@NotNull Player player) {
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection().normalize();
        return instances.values().stream()
                .filter(instance -> instance.location().getWorld() == player.getWorld())
                .filter(instance -> isTargeted(origin, direction, instance.location().clone().add(0.0D, 0.55D, 0.0D)))
                .min(Comparator.comparingDouble(instance -> instance.location().distanceSquared(eye)))
                .orElse(null);
    }

    private void applyMiningDamage(@NotNull Player player, @NotNull GatheringInstance instance) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        int miningDamage = astPlayer == null ? 1 : resolveMiningDamage(astPlayer);
        instance.damage(miningDamage);
        player.swingMainHand();
        if (instance.currentHealth() > 0) {
            playSound(instance.location(), instance.definition().sounds().hit());
            return;
        }

        playSound(instance.location(), instance.definition().sounds().breakSound());
        AstPlayer recipient = astPlayer;
        if (recipient != null && dropPresentationService != null) {
            MobDropResult result = dropService.roll(instance.definition().drops(), recipient);
            dropPresentationService.presentAndGrant(
                    recipient,
                    instance.location(),
                    ColorCodeUtil.toLegacyText(instance.definition().name(), instance.definition().id()),
                    result,
                    DROP_SOURCE
            );
            if (questService != null) {
                questService.recordGathering(recipient, instance.definition().id());
            }
        }
        UUID instanceId = instance.instanceId();
        stopSessionByPlayer(player.getUniqueId(), false);
        destroy(instanceId);
    }

    /**
     * プレイヤーの採集速度を参照し、1回の採集判定で与える破壊ダメージへ変換します。
     * 範囲ステータスの場合は参照時に抽選し、最低破壊ダメージは1とします。
     *
     * @param player 採集を行うプレイヤー
     * @return 1以上の採集オブジェクト破壊ダメージ
     */
    int resolveMiningDamage(@NotNull AstPlayer player) {
        return resolveMiningDamage(player.getStatusSnapshot().getValue(StatusType.MINING_SPEED));
    }

    /**
     * 採集速度の確定値を整数の破壊ダメージへ変換します。
     *
     * @param value 採集速度。未計算の場合はnull
     * @return 1以上の採集オブジェクト破壊ダメージ
     */
    static int resolveMiningDamage(@Nullable StatusValue value) {
        if (value == null) {
            return 1;
        }
        return Math.max(1, (int) Math.round(value.rollValue()));
    }

    private void playSound(@NotNull Location location, @Nullable GatheringSound sound) {
        if (sound == null || location.getWorld() == null) {
            return;
        }
        location.getWorld().playSound(location, sound.soundKey(), SoundCategory.BLOCKS, sound.volume(), sound.pitch());
    }

    private void stopSessionByPlayer(@NotNull UUID playerId, boolean resetHealth) {
        MiningSession session = sessions.remove(playerId);
        if (session != null) {
            stopSession(session, resetHealth);
        }
    }

    private void stopSession(@NotNull MiningSession session, boolean resetHealth) {
        sessions.remove(session.playerId);
        GatheringInstance instance = instances.get(session.instanceId);
        if (instance != null && session.playerId.equals(instance.activePlayerId())) {
            if (resetHealth) {
                instance.resetHealth();
            } else {
                instance.activePlayerId(null);
            }
        }
    }

    private boolean canUseCurrentTool(@NotNull Player player, @NotNull GatheringDefinition definition) {
        List<String> required = definition.requiredToolTags();
        if (required.isEmpty()) {
            return true;
        }
        Set<String> currentTags = currentToolTags(player.getInventory().getItemInMainHand());
        return required.stream().anyMatch(currentTags::contains);
    }

    private @NotNull Set<String> currentToolTags(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return Set.of();
        }
        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        ItemModel model = itemId == null ? null : itemService.findLoadedById(itemId);
        String tag = model == null || model.getEquipment() == null ? null : model.getEquipment().getTag();
        return tag == null || tag.isBlank()
            ? Set.of()
            : Set.of(tag.trim().toUpperCase(Locale.ROOT));
    }

    private @NotNull String currentToolSignature(@NotNull Player player) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return "AIR";
        }
        String astralId = ItemStackFactory.getAstralItemId(itemStack);
        String equipmentInstanceId = ItemStackFactory.getEquipmentInstanceId(itemStack);
        String iconName = ItemStackFactory.getIconName(itemStack);
        return itemStack.getType().name()
                + "|" + (astralId == null ? "" : astralId)
                + "|" + (equipmentInstanceId == null ? "" : equipmentInstanceId)
                + "|" + (iconName == null ? "" : iconName);
    }

    private boolean isTargeted(@NotNull Vector origin, @NotNull Vector direction, @NotNull Location target) {
        Vector toTarget = target.toVector().subtract(origin);
        double projection = toTarget.dot(direction);
        if (projection < 0.0D || projection > TARGET_DISTANCE) {
            return false;
        }
        Vector closest = origin.clone().add(direction.clone().multiply(projection));
        return closest.distanceSquared(target.toVector()) <= TARGET_RADIUS_SQ;
    }

    private @NotNull Location blockCenter(@NotNull Location location) {
        return new Location(
                location.getWorld(),
                location.getBlockX() + 0.5D,
                location.getBlockY(),
                location.getBlockZ() + 0.5D
        );
    }

    private @NotNull String stripPrefix(@NotNull String raw) {
        int index = raw.indexOf(':');
        return (index < 0 ? raw : raw.substring(index + 1)).trim();
    }

    private static final class MiningSession {
        private final UUID playerId;
        private final UUID instanceId;
        private final String toolSignature;
        private MiningSession(@NotNull UUID playerId, @NotNull UUID instanceId, @NotNull String toolSignature) {
            this.playerId = playerId;
            this.instanceId = instanceId;
            this.toolSignature = toolSignature;
        }

    }
}
