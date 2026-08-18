package io.github.maaasu.astralRecord.feature.gathering.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition.GatheringSound;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringInstance;
import io.github.maaasu.astralRecord.feature.gathering.repository.GatheringDefinitionRepository;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentDurabilityService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassExperienceResult;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class GatheringService {
    private static final double TARGET_DISTANCE = 5.5D;
    private static final double TARGET_RADIUS = 0.85D;
    private static final long AUTO_CLICK_INTERVAL_TICKS = 8L;
    private static final String DROP_SOURCE = "gathering_drop";

    /**
     * 視線上で命中した採集オブジェクトとhitbox入口距離です。
     *
     * @param instance 命中した採集インスタンス
     * @param hitDistance プレイヤー視点からhitbox入口までの有限な非負距離
     */
    public record GatheringHit(@NotNull GatheringInstance instance, double hitDistance) {
        /**
         * 命中結果を生成し、距離契約を検証します。
         *
         * @throws NullPointerException 採集インスタンスがnullの場合
         * @throws IllegalArgumentException 距離が非有限または負数の場合
         */
        public GatheringHit {
            Objects.requireNonNull(instance, "instance");
            if (!Double.isFinite(hitDistance) || hitDistance < 0.0D) {
                throw new IllegalArgumentException("hitDistance must be finite and zero or greater");
            }
        }
    }

    private final Plugin plugin;
    private final GatheringDefinitionRepository definitionRepository;
    private final MobDropService dropService;
    private final ItemService itemService;
    private EquipmentDurabilityService equipmentDurabilityService;
    private MobDropPresentationService dropPresentationService;
    private AccountService accountService;
    private PlayerClassService playerClassService;
    private SkillTreeService skillTreeService;
    private ParticleDisplayService particleDisplayService;
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

    /**
     * 採集完了時のメインハンド耐久値消費サービスを設定します。
     *
     * @param equipmentDurabilityService 装備耐久値サービス
     */
    public void setEquipmentDurabilityService(@NotNull EquipmentDurabilityService equipmentDurabilityService) {
        this.equipmentDurabilityService = equipmentDurabilityService;
    }

    /**
     * 採集報酬のアカウント・クラス経験値反映サービスを設定します。
     *
     * @param accountService アカウント経験値サービス
     * @param playerClassService クラス経験値サービス
     * @param skillTreeService レベルアップ後の派生状態更新サービス
     * @param particleDisplayService レベルアップ演出サービス
     */
    public void setProgressionServices(
        @NotNull AccountService accountService,
        @NotNull PlayerClassService playerClassService,
        @NotNull SkillTreeService skillTreeService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.accountService = accountService;
        this.playerClassService = playerClassService;
        this.skillTreeService = skillTreeService;
        this.particleDisplayService = particleDisplayService;
    }

    public void setQuestService(@NotNull QuestService questService) {
        this.questService = questService;
    }

    public int loadAll() {
        List<GatheringDefinition> snapshot = loadDefinitionSnapshot();
        replaceDefinitionSnapshot(snapshot);
        activateDefinitionSnapshot();
        return definitions.size();
    }

    /**
     * 採集定義を読み込み、公開前のスナップショットを作成します。
     *
     * @return 採集定義スナップショット
     */
    public @NotNull List<GatheringDefinition> loadDefinitionSnapshot() {
        return List.copyOf(definitionRepository.findAll());
    }

    /**
     * 準備済み採集定義を実行時キャッシュへ反映します。
     *
     * @param snapshot 採集定義スナップショット
     */
    public void replaceDefinitionSnapshot(@NotNull List<GatheringDefinition> snapshot) {
        definitions.clear();
        for (GatheringDefinition definition : snapshot) {
            definitions.put(definition.id(), definition);
        }
    }

    /**
     * 公開済み採集定義へ切り替えるため、旧定義に属する実体を破棄します。
     * Bukkit Entity を操作するためメインスレッドから呼び出してください。
     */
    public void activateDefinitionSnapshot() {
        clearInstances();
    }

    public void start() {
        if (visualizer == null) {
            visualizer = new GatheringVisualizer(plugin, this);
        }
        visualizer.start();
    }

    public void stop() {
        for (MiningSession session : List.copyOf(sessions.values())) {
            session.cancel();
        }
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
        for (MiningSession session : List.copyOf(sessions.values())) {
            session.cancel();
        }
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

    /**
     * プレイヤーが視線を合わせている採集オブジェクトの採集を開始します。
     * 最初の左クリックで即時に1回採集し、その後は一定間隔で自動採集を継続します。
     * 同じ採集セッション中の追加クリックはクールタイムを迂回するダメージとして扱いません。
     *
     * @param player 採集を開始するプレイヤー
     * @return 採集入力を受理した場合は {@code true}
     */
    public boolean startMining(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        MiningSession existing = sessions.get(playerId);
        GatheringInstance target = findTargeted(player);
        if (target == null || !canUseCurrentTool(player, target.definition())) {
            if (existing != null) {
                stopSession(existing, true);
            }
            return false;
        }

        String toolSignature = currentToolSignature(player);
        if (existing != null
                && existing.instanceId.equals(target.instanceId())
                && existing.toolSignature.equals(toolSignature)) {
            return true;
        }
        if (existing != null) {
            stopSession(existing, true);
        }
        if (target.activePlayerId() != null && !target.activePlayerId().equals(player.getUniqueId())) {
            stopSessionByPlayer(target.activePlayerId(), true);
        }

        target.activePlayerId(playerId);
        applyMiningDamage(player, target);
        if (!instances.containsKey(target.instanceId())) {
            return true;
        }

        MiningSession session = new MiningSession(playerId, target.instanceId(), toolSignature);
        sessions.put(playerId, session);
        session.task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                () -> continueMining(session),
                AUTO_CLICK_INTERVAL_TICKS,
                AUTO_CLICK_INTERVAL_TICKS
        );
        return true;
    }

    /**
     * プレイヤー視線上で最も入口距離が近い採集オブジェクトを返します。
     *
     * @param player 判定対象プレイヤー
     * @return 命中した採集インスタンス。見つからない場合はnull
     */
    public @Nullable GatheringInstance findTargeted(@NotNull Player player) {
        GatheringHit hit = findTargetedHit(player);
        return hit == null ? null : hit.instance();
    }

    /**
     * プレイヤー視線上で最も入口距離が近い採集オブジェクトを返します。
     * 候補解決だけを行い、採集sessionや耐久値を変更しません。
     *
     * @param player 判定対象プレイヤー
     * @return 命中した採集インスタンスと入口距離。見つからない場合はnull
     */
    public @Nullable GatheringHit findTargetedHit(@NotNull Player player) {
        Location eye = player.getEyeLocation();
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
                eye.toVector(),
                eye.getDirection(),
                TARGET_DISTANCE
        );
        if (ray == null) {
            return null;
        }

        GatheringHit nearest = null;
        for (GatheringInstance instance : instances.values()) {
            if (instance.location().getWorld() != player.getWorld()) {
                continue;
            }
            Location center = instance.location().clone().add(0.0D, 0.55D, 0.0D);
            Double hitDistance = ray.sphereEntryDistance(center.toVector(), TARGET_RADIUS);
            if (hitDistance == null || (nearest != null
                    && (hitDistance > nearest.hitDistance()
                    || (Double.compare(hitDistance, nearest.hitDistance()) == 0
                    && instance.instanceId().compareTo(nearest.instance().instanceId()) >= 0)))) {
                continue;
            }
            nearest = new GatheringHit(instance, hitDistance);
        }
        return nearest;
    }

    private void continueMining(@NotNull MiningSession session) {
        Player player = plugin.getServer().getPlayer(session.playerId);
        GatheringInstance instance = instances.get(session.instanceId);
        if (player == null || !player.isOnline() || instance == null) {
            stopSession(session, false);
            return;
        }
        if (!session.playerId.equals(instance.activePlayerId())) {
            stopSession(session, false);
            return;
        }
        if (!session.toolSignature.equals(currentToolSignature(player))) {
            stopSession(session, true);
            return;
        }
        if (findTargeted(player) != instance) {
            stopSession(session, true);
            return;
        }
        if (!canUseCurrentTool(player, instance.definition())) {
            stopSession(session, true);
            return;
        }
        applyMiningDamage(player, instance);
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
            applyExperienceAndSkillPoints(recipient, result);
            if (equipmentDurabilityService != null) {
                equipmentDurabilityService.consumeOnGathering(recipient);
            }
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
        session.cancel();
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
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (equipmentDurabilityService != null
                && astPlayer != null
                && !equipmentDurabilityService.canUseMainHandTool(astPlayer)) {
            return false;
        }
        List<String> required = definition.requiredToolTags();
        if (required.isEmpty()) {
            return true;
        }
        Set<String> currentTags = currentToolTags(player.getInventory().getItemInMainHand());
        return required.stream().anyMatch(currentTags::contains);
    }

    private void applyExperienceAndSkillPoints(@NotNull AstPlayer recipient, @NotNull MobDropResult result) {
        if (result.exp() <= 0 || accountService == null || playerClassService == null) {
            return;
        }
        try {
            AccountExperienceResult progress = accountService.grantExperienceCached(
                recipient.getAccount(),
                result.exp(),
                recipient.getUser().getUuid()
            );
            ClassExperienceResult classProgress = playerClassService.grantClassExperience(recipient, result.exp());
            applyExperienceAndSkillPointsResult(recipient, progress, classProgress);
        } catch (RuntimeException ex) {
            Logger.error(LogId.E_5159, ex, recipient.getAccount().getUuid(), result.exp());
        }
    }

    private void applyExperienceAndSkillPointsResult(
        @NotNull AstPlayer recipient,
        @NotNull AccountExperienceResult progress,
        @NotNull ClassExperienceResult classProgress
    ) {
        recipient.setAccount(progress.updatedAccount());
        if (!recipient.getBukkit().isOnline()) {
            return;
        }

        if (progress.leveledUp()) {
            PlayerMessageService.getInstance().send(
                recipient,
                PlayerMsgId.P_5835,
                progress.updatedAccount().getLevel(),
                progress.grantedExperience(),
                progress.levelUps()
            );
            playPlayerLevelUp(recipient.getBukkit());
        }
        if (classProgress.getLeveledUp()) {
            PlayerMessageService.getInstance().send(
                recipient,
                PlayerMsgId.P_5847,
                recipient.getClassLevel(),
                classProgress.getGrantedExperience(),
                classProgress.getClassPointGains()
            );
            playClassLevelUp(recipient.getBukkit());
        }
        if (skillTreeService != null && (progress.leveledUp() || classProgress.getLeveledUp())) {
            skillTreeService.refreshProgressDerivedState(recipient);
        }
    }

    private void playPlayerLevelUp(@NotNull Player player) {
        Location location = player.getLocation().add(0.0D, 1.0D, 0.0D);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.9F, 0.85F);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7F, 1.05F);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.45F, 1.35F);
        if (particleDisplayService != null) {
            particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.PLAYER_LEVEL_UP_TOTEM);
            particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.PLAYER_LEVEL_UP_END_ROD);
        }
    }

    private void playClassLevelUp(@NotNull Player player) {
        Location location = player.getLocation().add(0.0D, 0.9D, 0.0D);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.45F, 1.45F);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.4F, 1.75F);
        if (particleDisplayService != null) {
            particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.CLASS_LEVEL_UP_DUST);
            particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.CLASS_LEVEL_UP_ENCHANT);
        }
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
        private BukkitTask task;

        private MiningSession(@NotNull UUID playerId, @NotNull UUID instanceId, @NotNull String toolSignature) {
            this.playerId = playerId;
            this.instanceId = instanceId;
            this.toolSignature = toolSignature;
        }

        private void cancel() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }
}
