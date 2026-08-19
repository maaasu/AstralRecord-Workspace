package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeEndReason;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeState;
import io.github.maaasu.astralRecord.feature.boss.model.BossFieldInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossLocation;
import io.github.maaasu.astralRecord.feature.boss.view.BossChallengeCancelController;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeDeathPolicy;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeStartCountdown;
import io.github.maaasu.astralRecord.shared.challenge.InstanceCreationQueue;
import io.github.maaasu.astralRecord.shared.challenge.InstanceCreationQueueConfig;
import io.github.maaasu.astralRecord.shared.challenge.InstanceQueueTitleRenderer;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.title.Title;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * Coordinates boss challenge acceptance, field entry, and completion.
 */
public final class BossChallengeService {
    /** ボス入口円内の候補と、プレイヤーから入口中心までの距離です。 */
    public record BossEntryHit(@NotNull String bossId, double hitDistance) {
    }

    private static final long FIELD_START_DELAY_TICKS = 40L;
    private static final long DEFEATED_RESULT_WAIT_TICKS = 15L * 20L;
    private static final long ENTRY_VISUAL_PERIOD_TICKS = 10L;
    private static final int ENTRY_RING_POINTS = 10;
    private static final double ENTRY_PROMPT_Y_OFFSET = 2.35D;
    private static final double ENTRY_VIEWER_DISTANCE_SQUARED = 64.0D * 64.0D;
    private static final long END_RETRY_DELAY_TICKS = 5L * 20L;
    private static final Title.Times COUNTDOWN_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(100L),
            Duration.ofMillis(900L),
            Duration.ofMillis(100L)
    );

    private final AstralRecord plugin;
    private final MobService mobService;
    private final WorldService worldService;
    private final PartyService partyService;
    private final PlayerMessageService messageService;
    private final BossFieldInstanceService fieldInstanceService;
    private final ParticleDisplayService particleDisplayService;
    private final DisplayTextService displayTextService;
    private final PlayerDeathService playerDeathService;
    private final InstanceCreationQueue creationQueue;
    private final String hubWorldId;
    private final Map<UUID, BossChallengeInstance> challengesById = new ConcurrentHashMap<>();
    private final Map<String, UUID> challengeIdByPartyKey = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> challengeIdByBossMob = new ConcurrentHashMap<>();
    private final Map<UUID, BossChallengeCancelController> cancelControllersByChallengeId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> challengeIdByCancelInteraction = new ConcurrentHashMap<>();
    private final Map<String, EntryPromptDisplay> entryPromptDisplays = new HashMap<>();
    private BukkitTask tickTask;
    private BukkitTask entryVisualTask;
    private long entryVisualFrame;
    private boolean startupCleanupStarted;

    public BossChallengeService(
            @NotNull AstralRecord plugin,
            @NotNull MobService mobService,
            @NotNull WorldService worldService,
            @NotNull PartyService partyService,
            @NotNull PlayerMessageService messageService,
            @NotNull BossFieldInstanceService fieldInstanceService,
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull DisplayTextService displayTextService,
            @NotNull PlayerDeathService playerDeathService,
            @NotNull String hubWorldId
    ) {
        this(
                plugin,
                mobService,
                worldService,
                partyService,
                messageService,
                fieldInstanceService,
                particleDisplayService,
                displayTextService,
                playerDeathService,
                hubWorldId,
                new InstanceCreationQueue(InstanceCreationQueueConfig.DEFAULT_BOSS)
        );
    }

    /**
     * Boss 挑戦サービスを作成枠キュー付きで構成します。
     *
     * @param plugin Plugin 本体
     * @param mobService Mob サービス
     * @param worldService World サービス
     * @param partyService Party サービス
     * @param messageService プレイヤーメッセージサービス
     * @param fieldInstanceService Boss フィールド生成サービス
     * @param particleDisplayService パーティクル表示サービス
     * @param displayTextService TextDisplay サービス
     * @param playerDeathService 死亡・復帰サービス
     * @param hubWorldId 待機HubのWorld ID
     * @param creationQueue インスタンス作成枠キュー
     */
    public BossChallengeService(
            @NotNull AstralRecord plugin,
            @NotNull MobService mobService,
            @NotNull WorldService worldService,
            @NotNull PartyService partyService,
            @NotNull PlayerMessageService messageService,
            @NotNull BossFieldInstanceService fieldInstanceService,
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull DisplayTextService displayTextService,
            @NotNull PlayerDeathService playerDeathService,
            @NotNull String hubWorldId,
            @NotNull InstanceCreationQueue creationQueue
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.worldService = worldService;
        this.partyService = partyService;
        this.messageService = messageService;
        this.fieldInstanceService = fieldInstanceService;
        this.particleDisplayService = particleDisplayService;
        this.displayTextService = displayTextService;
        this.playerDeathService = playerDeathService;
        this.creationQueue = creationQueue;
        this.hubWorldId = hubWorldId;
    }

    /**
     * ボス挑戦の監視と入口演出の定期処理を開始します。
     */
    public void start() {
        if (tickTask == null) {
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }
        if (entryVisualTask == null) {
            entryVisualFrame = 0L;
            entryVisualTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickEntryVisuals, 0L, ENTRY_VISUAL_PERIOD_TICKS);
        }
        if (!startupCleanupStarted) {
            startupCleanupStarted = true;
            fieldInstanceService.cleanupStaleFieldsAsync();
        }
    }

    /**
     * 入口演出を停止し、進行中のボス挑戦をすべて終了します。
     */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (entryVisualTask != null) {
            entryVisualTask.cancel();
            entryVisualTask = null;
        }
        clearEntryPromptDisplays();
        for (InstanceCreationQueue.Ticket ticket : creationQueue.waitingTickets()) {
            clearQueueTitles(ticket.participantIds());
        }
        creationQueue.clear();
        fieldInstanceService.cancelPendingCreations();
        for (BossChallengeInstance challenge : List.copyOf(challengesById.values())) {
            forceShutdownChallenge(challenge);
        }
        challengesById.clear();
        challengeIdByPartyKey.clear();
        challengeIdByBossMob.clear();
        cancelControllersByChallengeId.clear();
        challengeIdByCancelInteraction.clear();
    }

    /**
     * Accepts the nearest boss challenge whose entry circle contains the player.
     *
     * @param player player standing in an entry circle
     * @return true when a challenge was accepted or an error was sent
     */
    public boolean acceptNearestChallenge(@NotNull Player player) {
        return acceptNearestChallenge(player, true);
    }

    /**
     * Accepts the nearest boss challenge whose entry circle contains the player.
     *
     * @param player player standing in an entry circle
     * @param notifyMissing sends a message when no entry circle contains the player
     * @return true when a challenge was accepted or an error was sent
     */
    public boolean acceptNearestChallenge(@NotNull Player player, boolean notifyMissing) {
        BossEntryHit hit = findNearestChallengeEntry(player);
        if (hit != null) {
            acceptChallenge(player, hit.bossId());
            return true;
        }
        if (notifyMissing) {
            messageService.send(player, PlayerMsgId.P_6500);
        }
        return false;
    }

    /**
     * プレイヤーを含むボス入口円のうち、入口中心が最も近い候補を返します。
     * 候補探索だけを行い、挑戦開始などの副作用は発生させません。
     *
     * @param player 判定対象プレイヤー
     * @return 最寄り入口。入口円外なら null
     */
    public @Nullable BossEntryHit findNearestChallengeEntry(@NotNull Player player) {
        BossEntryHit nearest = null;
        for (String bossId : mobService.getLoadedMobIdsByCategory(List.of(MobCategory.BOSS))) {
            MobTemplate template = mobService.findTemplate(bossId);
            if (template == null || template.challenge() == null) {
                continue;
            }
            BossChallengeConfig config = template.challenge();
            World entryWorld = resolveLocationWorld(config.entryLocation());
            if (entryWorld == null || !entryWorld.getUID().equals(player.getWorld().getUID())) {
                continue;
            }
            Location entry = config.entryLocation().toLocation(entryWorld);
            double distanceSquared = player.getLocation().distanceSquared(entry);
            if (distanceSquared > config.entryRadius() * config.entryRadius()) {
                continue;
            }
            BossEntryHit current = new BossEntryHit(bossId, Math.sqrt(distanceSquared));
            if (nearest == null
                || current.hitDistance() < nearest.hitDistance()
                || (Double.compare(current.hitDistance(), nearest.hitDistance()) == 0
                && current.bossId().compareTo(nearest.bossId()) < 0)) {
                nearest = current;
            }
        }
        return nearest;
    }

    /**
     * Accepts a specific boss challenge for the player's party.
     *
     * @param player initiator
     * @param bossId boss mob master ID
     */
    public void acceptChallenge(@NotNull Player player, @NotNull String bossId) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !AccountModeGuard.isGameplayPlayer(astPlayer)) {
            messageService.send(player, PlayerMsgId.P_5065);
            return;
        }

        MobTemplate template = mobService.findTemplate(bossId);
        if (template == null || template.category() != MobCategory.BOSS || template.challenge() == null) {
            messageService.send(player, PlayerMsgId.P_6501, bossId);
            return;
        }

        BossChallengeConfig config = template.challenge();
        if (!isInsideEntry(player, config)) {
            messageService.send(player, PlayerMsgId.P_6502);
            return;
        }

        Party party = partyService.findParty(player.getUniqueId());
        if (party != null && !party.isLeader(player.getUniqueId())) {
            messageService.send(player, PlayerMsgId.P_6503);
            return;
        }

        List<UUID> participants = onlineParticipants(player, party);
        if (participants.size() < config.partyMin() || participants.size() > config.partyMax()) {
            messageService.send(player, PlayerMsgId.P_6504, config.partyMin(), config.partyMax(), participants.size());
            return;
        }

        String partyKey = party == null ? "solo:" + player.getUniqueId() : "party:" + party.getPartyId();
        if (challengeIdByPartyKey.containsKey(partyKey)) {
            messageService.send(player, PlayerMsgId.P_6505);
            return;
        }

        WorldMasterData hubData = worldService.getById(hubWorldId);
        WorldMasterData fieldData = worldService.getById(config.fieldWorldId());
        if (hubData == null) {
            messageService.send(player, PlayerMsgId.P_6506, hubWorldId);
            return;
        }
        if (worldService.resolveOrLoadWorld(hubData) == null) {
            messageService.send(player, PlayerMsgId.P_6523, hubWorldId);
            return;
        }
        if (fieldData == null) {
            messageService.send(player, PlayerMsgId.P_6507, config.fieldWorldId());
            return;
        }
        if (fieldData.worldType() != WorldType.BOSS_FIELD || !fieldData.instanceEnabled()) {
            Logger.log(LogId.W_6502, fieldData.id(), fieldData.worldType().name(), fieldData.instanceEnabled());
            messageService.send(player, PlayerMsgId.P_6522, config.fieldWorldId());
            return;
        }

        BossChallengeInstance challenge = new BossChallengeInstance(
                UUID.randomUUID(),
                partyKey,
                player.getUniqueId(),
                template,
                config,
                participants
        );
        challenge.reservedCreationSlot(astPlayer.hasPermissionLevel(UserPermission.DONOR.getValue()));
        challengesById.put(challenge.challengeId(), challenge);
        challengeIdByPartyKey.put(partyKey, challenge.challengeId());
        Logger.log(LogId.I_6500, challenge.challengeId(), template.id(), partyKey);

        notifyParticipants(challenge, PlayerMsgId.P_6508, template.displayName());
        teleportParticipantsToHubAsync(challenge, hubData).whenComplete((results, throwable) ->
                runSync(() -> finishHubTransfer(challenge.challengeId(), fieldData, results, throwable)));
    }

    /**
     * Returns whether the mob instance belongs to an active boss challenge.
     *
     * @param mobInstanceId Mob instance ID
     * @return true when tracked as a challenge boss
     */
    public boolean isBossMob(@NotNull UUID mobInstanceId) {
        return challengeIdByBossMob.containsKey(mobInstanceId);
    }

    /**
     * Records effective damage dealt to a boss by a challenge participant.
     *
     * @param mobInstanceId boss mob instance ID
     * @param playerId attacker player UUID
     * @param amount effective damage amount
     */
    public void recordBossDamage(@NotNull UUID mobInstanceId, @NotNull UUID playerId, double amount) {
        UUID challengeId = challengeIdByBossMob.get(mobInstanceId);
        if (challengeId == null || amount <= 0.0D) {
            return;
        }
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge == null || challenge.state() != BossChallengeState.IN_PROGRESS) {
            return;
        }
        if (!challenge.participantIds().contains(playerId)) {
            return;
        }
        challenge.addDamage(playerId, amount);
    }

    /**
     * 討伐時点で報酬を受け取れる固定参加者を返します。
     * 復帰待ち中の死亡参加者も、オンラインかつ同一フィールドにいる場合は対象に含めます。
     *
     * @param mobInstanceId 討伐されたボス Mob インスタンス ID
     * @return 報酬対象のゲームプレイヤー一覧
     */
    public @NotNull List<AstPlayer> resolveRewardRecipients(@NotNull UUID mobInstanceId) {
        UUID challengeId = challengeIdByBossMob.get(mobInstanceId);
        BossChallengeInstance challenge = challengeId == null ? null : challengesById.get(challengeId);
        if (challenge == null || challenge.state() != BossChallengeState.IN_PROGRESS || challenge.field() == null) {
            return List.of();
        }
        UUID fieldWorldId = challenge.field().world().getUID();
        List<AstPlayer> recipients = new ArrayList<>();
        for (UUID participantId : challenge.participantIds()) {
            Player player = Bukkit.getPlayer(participantId);
            if (player == null || !player.isOnline() || !player.getWorld().getUID().equals(fieldWorldId)) {
                continue;
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                recipients.add(astPlayer);
            }
        }
        return List.copyOf(recipients);
    }

    /**
     * ボスフィールド参加者の死亡を記録し、上限未満なら指定秒後のフィールド復帰を開始します。
     *
     * @param astPlayer 死亡した参加者
     * @param deathLocation 死亡地点
     * @return ボス挑戦の死亡として処理した場合は {@code true}
     */
    public boolean handleParticipantDeath(@NotNull AstPlayer astPlayer, @NotNull Location deathLocation) {
        UUID playerId = astPlayer.getBukkit().getUniqueId();
        BossChallengeInstance challenge = findInProgressChallengeByParticipant(playerId);
        if (challenge == null || challenge.field() == null
                || deathLocation.getWorld() == null
                || !deathLocation.getWorld().getUID().equals(challenge.field().world().getUID())) {
            return false;
        }
        if (playerDeathService.isDead(playerId)) {
            return true;
        }

        int deathCount = challenge.recordDeath(playerId);
        boolean started = playerDeathService.startDeath(
                astPlayer,
                deathLocation,
                challenge.config().reviveDelaySeconds() * 1_000L,
                false,
                () -> reviveParticipant(challenge.challengeId(), playerId)
        );
        if (!started) {
            return true;
        }
        if (ChallengeDeathPolicy.isExceeded(deathCount, challenge.config().deathLimit())) {
            notifyParticipants(challenge, PlayerMsgId.P_6525, deathCount, challenge.config().deathLimit());
            endChallenge(challenge, BossChallengeEndReason.DEATH_LIMIT);
        } else {
            messageService.send(
                    astPlayer,
                    PlayerMsgId.P_6524,
                    challenge.config().reviveDelaySeconds(),
                    deathCount,
                    challenge.config().deathLimit()
            );
        }
        return true;
    }

    /**
     * Handles defeat of an active boss mob.
     *
     * @param mobInstanceId defeated mob instance ID
     * @param deathLocation boss death location
     */
    public void handleBossDefeated(@NotNull UUID mobInstanceId, @NotNull Location deathLocation) {
        UUID challengeId = challengeIdByBossMob.get(mobInstanceId);
        if (challengeId == null) {
            return;
        }
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge != null) {
            beginDefeatedResultWait(challenge, deathLocation);
        }
    }

    /**
     * Handles player quit. A challenge is ended by the watchdog when no participant remains.
     *
     * @param playerId quit player
     */
    public void handleQuit(@NotNull UUID playerId) {
        tick();
    }

    /**
     * プレイヤーが参加している進行中の挑戦情報をサイドバー用に返します。
     *
     * @param playerId プレイヤー UUID
     * @return 挑戦中なら表示情報、それ以外は null
     */
    public @Nullable BossChallengeSidebarInfo findSidebarInfo(@NotNull UUID playerId) {
        for (BossChallengeInstance challenge : challengesById.values()) {
            if ((challenge.state() != BossChallengeState.PREPARING
                    && challenge.state() != BossChallengeState.IN_PROGRESS)
                    || !displayParticipantIds(challenge).contains(playerId)) {
                continue;
            }
            long elapsed = challenge.startedAtMs() <= 0L
                    ? 0L
                    : Math.max(0L, (System.currentTimeMillis() - challenge.startedAtMs()) / 1000L);
            return new BossChallengeSidebarInfo(
                    challenge.bossTemplate().displayName(),
                    challenge.bossTemplate().level(),
                    challenge.deathCount(),
                    challenge.config().deathLimit(),
                    elapsed,
                    challenge.config().timeLimitSeconds(),
                    displayParticipantIds(challenge).stream().map(this::playerName).toList()
            );
        }
        return null;
    }

    /**
     * プレイヤーが終了処理を含むボス挑戦へ参加中かを返します。
     *
     * @param playerId 判定対象プレイヤーの UUID
     * @return 挑戦の準備中、進行中、結果待ち、または終了処理中であれば {@code true}
     */
    public boolean isPlayerInActiveChallenge(@NotNull UUID playerId) {
        for (BossChallengeInstance challenge : challengesById.values()) {
            if (challenge.state() == BossChallengeState.ENDED
                    || !displayParticipantIds(challenge).contains(playerId)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * プレイヤーが中止操作を開ける挑戦 ID を返します。
     *
     * @param playerId プレイヤー UUID
     * @return 中止可能な挑戦 ID。存在しない場合は null
     */
    public @Nullable UUID findCancelableChallengeId(@NotNull UUID playerId) {
        for (BossChallengeInstance challenge : challengesById.values()) {
            if (isCancelable(challenge) && displayParticipantIds(challenge).contains(playerId)) {
                return challenge.challengeId();
            }
        }
        return null;
    }

    /**
     * 指定された挑戦のパーティーリーダーか判定します。
     *
     * @param playerId 判定対象プレイヤー UUID
     * @param challengeId 挑戦 ID
     * @return 挑戦中のパーティーリーダーなら true
     */
    public boolean isChallengeLeader(@NotNull UUID playerId, @NotNull UUID challengeId) {
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge == null || !isCancelable(challenge)) {
            return false;
        }
        if (challenge.partyKey().startsWith("solo:")) {
            return challenge.initiatorId().equals(playerId);
        }
        Party party = partyService.findParty(playerId);
        return party != null
                && party.isLeader(playerId)
                && challenge.partyKey().equals("party:" + party.getPartyId());
    }

    /**
     * パーティーリーダーによるボス挑戦中止を実行します。
     *
     * @param playerId 操作プレイヤー UUID
     * @param challengeId GUI から操作する挑戦 ID。null の場合はプレイヤーの挑戦を検索する
     * @return 中止結果
     */
    public @NotNull PlayerCancelResult stopChallengeForLeader(
            @NotNull UUID playerId,
            @Nullable UUID challengeId
    ) {
        UUID resolvedId = challengeId == null ? findCancelableChallengeId(playerId) : challengeId;
        if (resolvedId == null) {
            return PlayerCancelResult.NO_CHALLENGE;
        }
        if (!isChallengeLeader(playerId, resolvedId)) {
            return PlayerCancelResult.NOT_LEADER;
        }
        BossChallengeInstance challenge = challengesById.get(resolvedId);
        if (challenge == null) {
            return PlayerCancelResult.NO_CHALLENGE;
        }
        endChallenge(challenge, BossChallengeEndReason.ADMIN_STOP);
        return PlayerCancelResult.STOPPED;
    }

    /**
     * 中止操作エンティティに紐づく挑戦 ID を返します。
     *
     * @param entity 判定対象エンティティ
     * @return 挑戦 ID。対象外なら null
     */
    public @Nullable UUID resolveCancelInteraction(@NotNull org.bukkit.entity.Entity entity) {
        return challengeIdByCancelInteraction.get(entity.getUniqueId());
    }

    /**
     * 指定プレイヤーの近くにある中止操作装置の挑戦 ID を返します。
     *
     * @param player 判定対象プレイヤー
     * @return 挑戦 ID。対象外なら null
     */
    public @Nullable UUID findNearbyCancelController(@NotNull Player player) {
        for (BossChallengeCancelController controller : cancelControllersByChallengeId.values()) {
            if (controller.isNear(player)) {
                return controller.challengeId();
            }
        }
        return null;
    }

    /**
     * Returns active challenge descriptions for admin commands.
     *
     * @return description lines
     */
    public @NotNull List<String> describeActive() {
        List<String> lines = new ArrayList<>();
        for (BossChallengeInstance challenge : challengesById.values()) {
            long elapsed = challenge.startedAtMs() <= 0L ? 0L : (System.currentTimeMillis() - challenge.startedAtMs()) / 1000L;
            long remaining = challenge.startedAtMs() <= 0L
                    ? challenge.config().timeLimitSeconds()
                    : Math.max(0L, challenge.config().timeLimitSeconds() - elapsed);
            String worldName = challenge.field() == null ? "-" : challenge.field().worldName();
            lines.add(String.format(
                    Locale.ROOT,
                    "%s | パーティー=%s | ボス=%s | 状態=%s | 参加者=%d | ワールド=%s | 経過=%d秒 | 残り=%d秒",
                    challenge.challengeId(),
                    challenge.partyKey(),
                    challenge.bossTemplate().id(),
                    stateDisplayName(challenge.state()),
                    displayParticipantIds(challenge).size(),
                    worldName,
                    elapsed,
                    remaining
            ));
        }
        return lines;
    }

    private @NotNull String stateDisplayName(@NotNull BossChallengeState state) {
        return switch (state) {
            case PREPARING -> "準備中";
            case IN_PROGRESS -> "戦闘中";
            case RESULT_WAITING -> "結果表示中";
            case ENDING -> "終了処理中";
            case ENDED -> "終了済み";
        };
    }

    /**
     * Stops a challenge by challenge ID prefix or party key.
     *
     * @param key challenge ID prefix or party key
     * @return true when stopped
     */
    public boolean stopChallenge(@NotNull String key) {
        UUID mappedChallengeId = challengeIdByPartyKey.get(key);
        if (mappedChallengeId == null && !key.startsWith("party:") && !key.startsWith("solo:")) {
            mappedChallengeId = challengeIdByPartyKey.get("party:" + key);
        }
        BossChallengeInstance challenge = mappedChallengeId == null ? null : challengesById.get(mappedChallengeId);
        if (challenge == null) {
            for (BossChallengeInstance candidate : challengesById.values()) {
                if (candidate.challengeId().toString().startsWith(key)) {
                    challenge = candidate;
                    break;
                }
            }
        }
        if (challenge == null) {
            return false;
        }
        endChallenge(challenge, BossChallengeEndReason.ADMIN_STOP);
        return true;
    }

    private void finishHubTransfer(
            @NotNull UUID challengeId,
            @NotNull WorldMasterData fieldData,
            @Nullable List<Boolean> results,
            @Nullable Throwable throwable
    ) {
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge == null || challenge.state() != BossChallengeState.PREPARING) {
            return;
        }
        List<Player> readyParticipants = eligibleParticipantsForEntry(challenge);
        int readyCount = readyParticipants.size();
        if (readyCount < challenge.config().partyMin()) {
            challenge.confirmParticipants(
                    readyParticipants.stream().map(Player::getUniqueId).toList()
            );
            notifyExpectedParticipants(
                    challenge,
                    PlayerMsgId.P_6504,
                    challenge.config().partyMin(),
                    challenge.config().partyMax(),
                    readyCount
            );
            endChallenge(challenge, BossChallengeEndReason.PARTICIPANT_REQUIREMENT_NOT_MET);
            return;
        }
        InstanceCreationQueue.Ticket ticket = creationQueue.enqueue(
                challenge.challengeId(),
                readyParticipants.stream().map(Player::getUniqueId).toList(),
                challenge.reservedCreationSlot(),
                challenge.bossTemplate().displayName(),
                ignored -> beginQueuedFieldPreparation(challenge, fieldData)
        );
        challenge.creationQueueTicketId(ticket.id());
        renderQueueStatus(challenge, ticket);
    }

    private void beginQueuedFieldPreparation(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData fieldData
    ) {
        if (challenge.state() != BossChallengeState.PREPARING) {
            return;
        }
        List<Player> participants = eligibleParticipantsForEntry(challenge);
        if (participants.size() < challenge.config().partyMin()) {
            endChallenge(challenge, BossChallengeEndReason.PARTICIPANT_REQUIREMENT_NOT_MET);
            return;
        }
        clearQueueTitles(participants.stream().map(Player::getUniqueId).toList());
        beginFieldPreparation(challenge, fieldData);
    }

    private void beginFieldPreparation(@NotNull BossChallengeInstance challenge, @NotNull WorldMasterData fieldData) {
        fieldInstanceService.createFieldAsync(challenge, fieldData).whenComplete((field, throwable) ->
                runSync(() -> finishFieldPreparation(challenge.challengeId(), field, throwable)));
    }

    private void finishFieldPreparation(
            @NotNull UUID challengeId,
            @Nullable BossFieldInstance field,
            @Nullable Throwable throwable
    ) {
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge == null || challenge.state() != BossChallengeState.PREPARING) {
            if (field != null) {
                fieldInstanceService.destroyFieldAsync(field);
            }
            return;
        }
        if (throwable != null || field == null) {
            Logger.log(LogId.E_6500, throwable, challenge.bossTemplate().id(), challenge.config().fieldWorldId());
            notifyParticipants(challenge, PlayerMsgId.P_6509, challenge.config().fieldWorldId());
            endChallenge(challenge, BossChallengeEndReason.FIELD_PREPARE_FAILED);
            return;
        }

        challenge.field(field);
        Bukkit.getScheduler().runTaskLater(plugin, () -> startField(challenge.challengeId()), FIELD_START_DELAY_TICKS);
    }

    private void startField(@NotNull UUID challengeId) {
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge == null || challenge.state() != BossChallengeState.PREPARING) {
            return;
        }
        BossFieldInstance field = challenge.field();
        if (field == null) {
            endChallenge(challenge, BossChallengeEndReason.FIELD_PREPARE_FAILED);
            return;
        }

        List<Player> entrants = eligibleParticipantsForEntry(challenge);
        challenge.confirmParticipants(entrants.stream().map(Player::getUniqueId).toList());
        if (entrants.size() < challenge.config().partyMin()) {
            notifyExpectedParticipants(
                    challenge,
                    PlayerMsgId.P_6504,
                    challenge.config().partyMin(),
                    challenge.config().partyMax(),
                    entrants.size()
            );
            endChallenge(challenge, BossChallengeEndReason.PARTICIPANT_REQUIREMENT_NOT_MET);
            return;
        }

        Location playerSpawn = challenge.config().playerSpawnLocation().toLocation(field.world());
        List<CompletableFuture<Boolean>> transferResults = new ArrayList<>();
        for (Player participant : entrants) {
            transferResults.add(worldService.teleportPlayerAsync(participant, playerSpawn.clone(), null));
        }

        CompletableFuture.allOf(transferResults.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) ->
                        runSync(() -> finishFieldStartAfterTransfers(challengeId, transferResults, throwable)));
    }

    private void finishFieldStartAfterTransfers(
            @NotNull UUID challengeId,
            @NotNull List<CompletableFuture<Boolean>> transferResults,
            @Nullable Throwable throwable
    ) {
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge == null || challenge.state() != BossChallengeState.PREPARING) {
            return;
        }
        BossFieldInstance field = challenge.field();
        if (field == null) {
            endChallenge(challenge, BossChallengeEndReason.FIELD_PREPARE_FAILED);
            return;
        }
        if (throwable != null || transferResults.stream().anyMatch(future -> !Boolean.TRUE.equals(future.getNow(false)))) {
            notifyParticipants(challenge, PlayerMsgId.P_6521, challenge.bossTemplate().displayName());
            endChallenge(challenge, BossChallengeEndReason.TRANSFER_FAILED);
            return;
        }

        beginStartCountdown(challenge);
    }

    /** フィールド転送後に10秒の開始カウントダウンを開始します。 */
    private void beginStartCountdown(@NotNull BossChallengeInstance challenge) {
        ChallengeStartCountdown countdown = new ChallengeStartCountdown();
        BukkitTask[] taskRef = new BukkitTask[1];
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (challenge.state() != BossChallengeState.PREPARING || challenge.field() == null) {
                    taskRef[0].cancel();
                    challenge.startCountdownTask(null);
                    return;
                }
                List<Player> participantsInField = participantsInField(challenge);
                if (participantsInField.size() < challenge.config().partyMin()) {
                    taskRef[0].cancel();
                    challenge.startCountdownTask(null);
                    endChallenge(challenge, BossChallengeEndReason.PARTICIPANT_REQUIREMENT_NOT_MET);
                    return;
                }
                ChallengeStartCountdown.Tick tick = countdown.advance();
                if (tick.phase() == ChallengeStartCountdown.Phase.COUNTDOWN) {
                    showCountdown(participantsInField, challenge.bossTemplate().displayName(), tick.remainingSeconds());
                    return;
                }
                taskRef[0].cancel();
                challenge.startCountdownTask(null);
                showChallengeStart(participantsInField, challenge.bossTemplate().displayName());
                startBossCombat(challenge);
            } catch (RuntimeException failure) {
                if (taskRef[0] != null) taskRef[0].cancel();
                challenge.startCountdownTask(null);
                Logger.log(LogId.E_6500, failure,
                        challenge.bossTemplate().id(), challenge.config().fieldWorldId());
                endChallenge(challenge, BossChallengeEndReason.BOSS_SPAWN_FAILED);
            }
        }, 0L, 20L);
        challenge.startCountdownTask(taskRef[0]);
    }

    /** カウントダウン完了後にのみボスと中止装置を生成します。 */
    private void startBossCombat(@NotNull BossChallengeInstance challenge) {
        BossFieldInstance field = challenge.field();
        if (challenge.state() != BossChallengeState.PREPARING || field == null) {
            return;
        }
        Location bossSpawn = challenge.config().bossSpawnLocation().toLocation(field.world());
        MobInstance boss = null;
        try {
            boss = mobService.spawn(challenge.bossTemplate().id(), bossSpawn);
            if (boss == null) {
                endChallenge(challenge, BossChallengeEndReason.BOSS_SPAWN_FAILED);
                return;
            }
            applyParticipantScaling(challenge, boss);

            challenge.bossMobInstanceId(boss.instanceId());
            challengeIdByBossMob.put(boss.instanceId(), challenge.challengeId());
            BossChallengeCancelController controller = BossChallengeCancelController.spawn(
                    challenge.challengeId(),
                    bossSpawn,
                    displayTextService
            );
            cancelControllersByChallengeId.put(challenge.challengeId(), controller);
            challengeIdByCancelInteraction.put(controller.interaction().getUniqueId(), challenge.challengeId());
            challenge.markStarted();
            challenge.bossBar(createBossBar(boss));
            updateBossBar(challenge);
            Logger.log(LogId.I_6501, challenge.challengeId(), challenge.bossTemplate().id(), field.worldName());
            notifyParticipants(challenge, PlayerMsgId.P_6510, challenge.bossTemplate().displayName(), challenge.config().timeLimitSeconds());
        } catch (RuntimeException ex) {
            Logger.log(LogId.E_6500, ex, challenge.bossTemplate().id(), challenge.config().fieldWorldId());
            if (boss != null && challenge.bossMobInstanceId() == null) {
                mobService.destroy(boss.instanceId());
            }
            endChallenge(challenge, BossChallengeEndReason.BOSS_SPAWN_FAILED);
        } finally {
            fieldInstanceService.releaseStartupChunkTickets(challenge.challengeId());
        }
    }

    private @NotNull List<Player> participantsInField(@NotNull BossChallengeInstance challenge) {
        if (challenge.field() == null) {
            return List.of();
        }
        UUID worldId = challenge.field().world().getUID();
        return onlinePlayers(challenge.participantIds()).stream()
                .filter(player -> player.getWorld().getUID().equals(worldId))
                .toList();
    }

    private void showCountdown(@NotNull List<Player> players, @NotNull String name, int seconds) {
        for (Player player : players) {
            player.showTitle(Title.title(
                    PlayerMsgResource.formatComponent(PlayerMsgId.P_6529.getId(), seconds),
                    PlayerMsgResource.formatComponent(PlayerMsgId.P_6530.getId(), name),
                    COUNTDOWN_TITLE_TIMES
            ));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.8F, 1.2F);
        }
    }

    private void showChallengeStart(@NotNull List<Player> players, @NotNull String name) {
        for (Player player : players) {
            player.showTitle(Title.title(
                    PlayerMsgResource.formatComponent(PlayerMsgId.P_6531.getId(), name),
                    PlayerMsgResource.getComponent(PlayerMsgId.P_6532.getId()),
                    COUNTDOWN_TITLE_TIMES
            ));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.9F, 1.0F);
        }
    }

    static void applyParticipantScaling(@NotNull BossChallengeInstance challenge, @NotNull MobInstance boss) {
        if (!challenge.config().scaling().enabled()) {
            return;
        }
        int extraPlayers = Math.max(0, challenge.participantIds().size() - 1);
        double healthMultiplier = 1.0D + extraPlayers * Math.max(0.0D, challenge.config().scaling().healthPerExtraPlayer()) / 100.0D;
        double attackMultiplier = 1.0D + extraPlayers * Math.max(0.0D, challenge.config().scaling().attackPerExtraPlayer()) / 100.0D;
        boss.maxHealth(boss.maxHealth() * healthMultiplier);
        boss.currentHealth(boss.maxHealth());
        boss.outgoingDamageMultiplier(attackMultiplier);
    }

    private void tick() {
        refreshCreationQueue();
        long now = System.currentTimeMillis();
        for (BossChallengeInstance challenge : List.copyOf(challengesById.values())) {
            if (challenge.state() != BossChallengeState.IN_PROGRESS) {
                continue;
            }
            updateBossBar(challenge);
            long elapsedSeconds = (now - challenge.startedAtMs()) / 1000L;
            if (elapsedSeconds >= challenge.config().timeLimitSeconds()) {
                endChallenge(challenge, BossChallengeEndReason.TIME_LIMIT);
                continue;
            }
            if (!hasParticipantInField(challenge)) {
                endChallenge(challenge, BossChallengeEndReason.NO_PARTICIPANTS);
            }
        }
    }

    /** 待機中BossのHub滞在を確認し、順番表示を更新します。 */
    private void refreshCreationQueue() {
        for (InstanceCreationQueue.Ticket ticket : creationQueue.waitingTickets()) {
            BossChallengeInstance challenge = challengesById.get(ticket.id());
            if (challenge == null || challenge.state() != BossChallengeState.PREPARING) {
                creationQueue.cancelWaiting(ticket.id());
                continue;
            }
            if (!isQueuedParticipantPresent(challenge, ticket)) {
                creationQueue.cancelWaiting(ticket.id());
                endChallenge(challenge, BossChallengeEndReason.PARTICIPANT_REQUIREMENT_NOT_MET);
                continue;
            }
            renderQueueStatus(challenge, ticket);
        }
    }

    private boolean isQueuedParticipantPresent(
            @NotNull BossChallengeInstance challenge,
            @NotNull InstanceCreationQueue.Ticket ticket
    ) {
        WorldMasterData hubData = worldService.getById(hubWorldId);
        World hubWorld = hubData == null ? null : worldService.resolveLoadedWorld(hubData);
        if (hubWorld == null) {
            return false;
        }
        for (UUID participantId : ticket.participantIds()) {
            Player player = Bukkit.getPlayer(participantId);
            if (player == null || !player.isOnline()
                    || !AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(player))
                    || !stillBelongsToAcceptedParty(challenge, participantId)
                    || !player.getWorld().getUID().equals(hubWorld.getUID())) {
                return false;
            }
        }
        return !ticket.participantIds().isEmpty();
    }

    private void renderQueueStatus(
            @NotNull BossChallengeInstance challenge,
            @NotNull InstanceCreationQueue.Ticket ticket
    ) {
        InstanceCreationQueue.QueuePosition position = creationQueue.position(ticket.id());
        if (position == null) {
            return;
        }
        for (UUID participantId : ticket.participantIds()) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && player.isOnline()) {
                InstanceQueueTitleRenderer.show(
                        player,
                        PlayerMsgId.P_6533,
                        PlayerMsgId.P_6534,
                        challenge.bossTemplate().displayName(),
                        position
                );
            }
        }
    }

    private void clearQueueTitles(@NotNull Collection<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.clearTitle();
            }
        }
    }

    private void endChallenge(@NotNull BossChallengeInstance challenge, @NotNull BossChallengeEndReason reason) {
        if (challenge.state() == BossChallengeState.ENDING || challenge.state() == BossChallengeState.ENDED) {
            return;
        }
        if (reason == BossChallengeEndReason.DEFEATED) {
            BossFieldInstance field = challenge.field();
            Location resultLocation = field == null
                    ? Bukkit.getWorlds().get(0).getSpawnLocation()
                    : challenge.config().bossSpawnLocation().toLocation(field.world());
            beginDefeatedResultWait(challenge, resultLocation);
            return;
        }
        beginChallengeCompletion(challenge, reason);
    }

    private void beginDefeatedResultWait(@NotNull BossChallengeInstance challenge, @NotNull Location deathLocation) {
        if (challenge.state() != BossChallengeState.IN_PROGRESS) {
            return;
        }
        challenge.state(BossChallengeState.RESULT_WAITING);
        challenge.resultWaitEndsAtMs(System.currentTimeMillis() + DEFEATED_RESULT_WAIT_TICKS * 50L);
        Logger.log(LogId.I_6502, challenge.challengeId(), challenge.bossTemplate().id(), BossChallengeEndReason.DEFEATED.name());

        UUID bossMobId = challenge.bossMobInstanceId();
        if (bossMobId != null) {
            challengeIdByBossMob.remove(bossMobId);
        }
        destroyBossBar(challenge);

        notifyParticipants(challenge, PlayerMsgId.P_6511, challenge.bossTemplate().displayName());
        showDamageResult(challenge, deathLocation);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> beginChallengeCompletion(challenge, BossChallengeEndReason.DEFEATED),
                DEFEATED_RESULT_WAIT_TICKS
        );
        challenge.resultWaitTask(task);
    }

    private void beginChallengeCompletion(
            @NotNull BossChallengeInstance challenge,
            @NotNull BossChallengeEndReason reason
    ) {
        if (challenge.state() == BossChallengeState.ENDING || challenge.state() == BossChallengeState.ENDED) {
            return;
        }
        fieldInstanceService.cancelPendingCreation(challenge.challengeId());
        UUID queueTicketId = challenge.creationQueueTicketId();
        if (queueTicketId != null) {
            creationQueue.cancelWaiting(queueTicketId);
            clearQueueTitles(challenge.expectedParticipantIds());
        }
        challenge.state(BossChallengeState.ENDING);
        cancelStartCountdown(challenge);
        destroyCancelController(challenge.challengeId());
        destroyBossBar(challenge);
        if (!challenge.participantsConfirmed()) {
            challenge.confirmParticipants(
                    eligibleParticipantsForEntry(challenge).stream().map(Player::getUniqueId).toList()
            );
        }
        if (!reason.success()) {
            Logger.log(LogId.I_6502, challenge.challengeId(), challenge.bossTemplate().id(), reason.name());
        }

        BukkitTask resultWaitTask = challenge.resultWaitTask();
        if (resultWaitTask != null) {
            resultWaitTask.cancel();
            challenge.resultWaitTask(null);
        }
        destroyDamageResult(challenge);

        UUID bossMobId = challenge.bossMobInstanceId();
        if (bossMobId != null) {
            challengeIdByBossMob.remove(bossMobId);
            if (reason != BossChallengeEndReason.DEFEATED) {
                mobService.destroy(bossMobId);
            }
        }

        if (reason.success()) {
            // Success was already announced before the 15 second result wait.
        } else if (reason == BossChallengeEndReason.TIME_LIMIT) {
            notifyParticipants(challenge, PlayerMsgId.P_6513, challenge.bossTemplate().displayName());
        } else if (reason == BossChallengeEndReason.NO_PARTICIPANTS) {
            notifyParticipants(challenge, PlayerMsgId.P_6514, challenge.bossTemplate().displayName());
        } else if (reason == BossChallengeEndReason.DEATH_LIMIT
                || reason == BossChallengeEndReason.PARTICIPANT_REQUIREMENT_NOT_MET) {
            // The specific reason was already announced when the condition was detected.
        } else {
            notifyParticipants(challenge, PlayerMsgId.P_6512, challenge.bossTemplate().displayName(), reason.displayName());
        }

        for (UUID participantId : exitParticipantIds(challenge)) {
            playerDeathService.recoverNow(participantId);
        }
        exitParticipantsAndCleanup(challenge);
    }

    private void exitParticipantsAndCleanup(@NotNull BossChallengeInstance challenge) {
        teleportParticipantsOutAsync(challenge).whenComplete((success, throwable) ->
                runSync(() -> {
                    if (!isEnding(challenge)) {
                        return;
                    }
                    if (throwable != null || !Boolean.TRUE.equals(success)) {
                        Bukkit.getScheduler().runTaskLater(
                                plugin,
                                () -> exitParticipantsAndCleanup(challenge),
                                END_RETRY_DELAY_TICKS
                        );
                        return;
                    }
                    cleanupFieldAndFinish(challenge);
                }));
    }

    private void cleanupFieldAndFinish(@NotNull BossChallengeInstance challenge) {
        BossFieldInstance field = challenge.field();
        if (field == null) {
            finishChallengeRemoval(challenge);
            return;
        }
        fieldInstanceService.destroyFieldAsync(field).whenComplete((success, throwable) ->
                runSync(() -> {
                    if (!isEnding(challenge)) {
                        return;
                    }
                    if (throwable != null || !Boolean.TRUE.equals(success)) {
                        Bukkit.getScheduler().runTaskLater(
                                plugin,
                                () -> exitParticipantsAndCleanup(challenge),
                                END_RETRY_DELAY_TICKS
                        );
                        return;
                    }
                    finishChallengeRemoval(challenge);
                }));
    }

    private void finishChallengeRemoval(@NotNull BossChallengeInstance challenge) {
        destroyBossBar(challenge);
        UUID queueTicketId = challenge.creationQueueTicketId();
        if (queueTicketId != null) {
            creationQueue.release(queueTicketId);
            challenge.creationQueueTicketId(null);
        }
        challenge.state(BossChallengeState.ENDED);
        challengeIdByPartyKey.remove(challenge.partyKey());
        challengesById.remove(challenge.challengeId());
    }

    private boolean isCancelable(@NotNull BossChallengeInstance challenge) {
        return challenge.state() == BossChallengeState.PREPARING
                || challenge.state() == BossChallengeState.IN_PROGRESS;
    }

    private void destroyCancelController(@NotNull UUID challengeId) {
        BossChallengeCancelController controller = cancelControllersByChallengeId.remove(challengeId);
        if (controller == null) {
            return;
        }
        challengeIdByCancelInteraction.remove(controller.interaction().getUniqueId());
        controller.destroy();
    }

    private void cancelStartCountdown(@NotNull BossChallengeInstance challenge) {
        BukkitTask task = challenge.startCountdownTask();
        if (task != null) {
            task.cancel();
            challenge.startCountdownTask(null);
        }
    }

    /**
     * 生成直後のボス HP を使って BossBar を作成します。
     *
     * @param boss 表示対象のボス Mob
     * @return 表示状態で作成した BossBar
     */
    private @NotNull BossBar createBossBar(@NotNull MobInstance boss) {
        BossBar bossBar = Bukkit.createBossBar(
                formatBossBarTitle(boss.template().displayName(), boss.currentHealth(), boss.maxHealth()),
                BarColor.RED,
                BarStyle.SOLID
        );
        bossBar.setVisible(true);
        return bossBar;
    }

    /**
     * 挑戦中のボス HP と表示対象参加者を BossBar へ同期します。
     *
     * @param challenge 同期対象のボス挑戦
     */
    private void updateBossBar(@NotNull BossChallengeInstance challenge) {
        BossBar bossBar = challenge.bossBar();
        UUID bossMobId = challenge.bossMobInstanceId();
        MobInstance boss = bossMobId == null ? null : mobService.getInstance(bossMobId);
        if (bossBar == null) {
            return;
        }
        if (boss == null) {
            destroyBossBar(challenge);
            return;
        }

        bossBar.setTitle(formatBossBarTitle(boss.template().displayName(), boss.currentHealth(), boss.maxHealth()));
        bossBar.setProgress(bossBarProgress(boss.currentHealth(), boss.maxHealth()));

        BossFieldInstance field = challenge.field();
        Set<UUID> visibleParticipantIds = new HashSet<>();
        if (field != null) {
            UUID fieldWorldId = field.world().getUID();
            for (UUID participantId : challenge.participantIds()) {
                Player player = Bukkit.getPlayer(participantId);
                if (player != null && player.isOnline() && player.getWorld().getUID().equals(fieldWorldId)) {
                    visibleParticipantIds.add(participantId);
                }
            }
        }

        for (Player player : List.copyOf(bossBar.getPlayers())) {
            if (!visibleParticipantIds.contains(player.getUniqueId())) {
                bossBar.removePlayer(player);
            }
        }
        for (UUID participantId : visibleParticipantIds) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && !bossBar.getPlayers().contains(player)) {
                bossBar.addPlayer(player);
            }
        }
    }

    /**
     * 挑戦に紐付く BossBar を全参加者から除去して破棄します。
     *
     * @param challenge 破棄対象のボス挑戦
     */
    private void destroyBossBar(@NotNull BossChallengeInstance challenge) {
        BossBar bossBar = challenge.bossBar();
        if (bossBar == null) {
            return;
        }
        bossBar.removeAll();
        bossBar.setVisible(false);
        challenge.bossBar(null);
    }

    /**
     * ボス HP の BossBar タイトルを作成します。
     *
     * @param displayName ボス表示名
     * @param currentHealth 現在 HP
     * @param maxHealth 最大 HP
     * @return カラーコードと現在 HP／最大 HP を含む表示文字列
     */
    static @NotNull String formatBossBarTitle(
            @NotNull String displayName,
            double currentHealth,
            double maxHealth
    ) {
        return ColorCodeUtil.toLegacyText(displayName, "ボス")
                + " §7| §cHP: §f"
                + formatHealth(currentHealth)
                + "§7/§f"
                + formatHealth(maxHealth);
    }

    /**
     * 現在 HP と最大 HP から BossBar の進捗率を計算します。
     *
     * @param currentHealth 現在 HP
     * @param maxHealth 最大 HP
     * @return 0.0 以上1.0以下の進捗率
     */
    static double bossBarProgress(double currentHealth, double maxHealth) {
        if (!Double.isFinite(currentHealth) || !Double.isFinite(maxHealth) || maxHealth <= 0.0D) {
            return 0.0D;
        }
        return Math.clamp(currentHealth / maxHealth, 0.0D, 1.0D);
    }

    /**
     * BossBar タイトル用の HP 数値を整形します。
     *
     * @param health 整形対象 HP
     * @return 小数点以下を四捨五入した非負 HP 文字列
     */
    private static @NotNull String formatHealth(double health) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0D, Double.isFinite(health) ? health : 0.0D));
    }

    private boolean isEnding(@NotNull BossChallengeInstance challenge) {
        return challenge.state() == BossChallengeState.ENDING
                && challengesById.get(challenge.challengeId()) == challenge;
    }

    private void showDamageResult(@NotNull BossChallengeInstance challenge, @NotNull Location deathLocation) {
        Location displayLocation = deathLocation.clone().add(0.0D, 2.4D, 0.0D);
        DisplayTextService.ManagedTextDisplay display = displayTextService.create(
                DisplayAnchor.fixed(displayLocation),
                DisplayTextOptions.defaults(formatDamageResult(challenge))
                        .withShadowed(true)
                        .withLineWidth(360)
                        .withViewRange(64.0F)
        );
        display.setDynamicText(() -> formatDamageResult(challenge));
        challenge.resultDisplay(display);
    }

    private void destroyDamageResult(@NotNull BossChallengeInstance challenge) {
        DisplayTextService.ManagedTextDisplay display = challenge.resultDisplay();
        if (display != null) {
            display.destroy();
            challenge.resultDisplay(null);
        }
    }

    private @NotNull String formatDamageResult(@NotNull BossChallengeInstance challenge) {
        Map<UUID, Double> damage = challenge.damageSnapshot();
        double total = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        long remainingSeconds = Math.max(0L, (challenge.resultWaitEndsAtMs() - System.currentTimeMillis() + 999L) / 1000L);
        StringBuilder text = new StringBuilder("&6&lボス討伐成功 &f")
                .append(challenge.bossTemplate().displayName())
                .append("\n&7挑戦地点へ戻るまで &e")
                .append(remainingSeconds)
                .append("秒")
                .append("\n&d&lダメージ順位 &7合計 &f")
                .append(formatDamage(total));

        List<DamageLine> lines = challenge.participantIds().stream()
                .map(playerId -> new DamageLine(playerId, damage.getOrDefault(playerId, 0.0D)))
                .sorted(Comparator.comparingDouble(DamageLine::damage).reversed())
                .toList();
        for (int index = 0; index < lines.size(); index++) {
            DamageLine line = lines.get(index);
            double rate = total <= 0.0D ? 0.0D : line.damage() * 100.0D / total;
            text.append('\n')
                    .append("&e")
                    .append(index + 1)
                    .append(". &f")
                    .append(playerName(line.playerId()))
                    .append(" &a")
                    .append(formatDamage(line.damage()))
                    .append(" &7")
                    .append(String.format(Locale.ROOT, "%.1f%%", rate))
                    .append(" &c死亡回数 ")
                    .append(challenge.playerDeathCount(line.playerId()));
        }
        return text.toString();
    }

    private @NotNull String formatDamage(double damage) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0D, damage));
    }

    private @NotNull String playerName(@NotNull UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            return player.getName();
        }
        return playerId.toString().substring(0, 8);
    }

    private boolean isInsideEntry(@NotNull Player player, @NotNull BossChallengeConfig config) {
        World entryWorld = resolveLocationWorld(config.entryLocation());
        if (entryWorld == null || !entryWorld.getUID().equals(player.getWorld().getUID())) {
            return false;
        }
        Location entry = config.entryLocation().toLocation(entryWorld);
        return player.getLocation().distanceSquared(entry) <= config.entryRadius() * config.entryRadius();
    }

    private @Nullable World resolveLocationWorld(@NotNull BossLocation location) {
        if (location.worldId() == null || location.worldId().isBlank()) {
            return null;
        }
        WorldMasterData data = worldService.getById(location.worldId());
        if (data != null) {
            return worldService.resolveLoadedWorld(data);
        }
        return Bukkit.getWorld(location.worldId());
    }

    private @NotNull List<UUID> onlineParticipants(@NotNull Player initiator, @Nullable Party party) {
        if (party == null) {
            return List.of(initiator.getUniqueId());
        }
        List<UUID> result = new ArrayList<>();
        for (UUID memberId : party.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                result.add(memberId);
            }
        }
        return result;
    }

    private @NotNull List<Player> eligibleParticipantsForEntry(@NotNull BossChallengeInstance challenge) {
        WorldMasterData hubData = worldService.getById(hubWorldId);
        World hubWorld = hubData == null ? null : worldService.resolveLoadedWorld(hubData);
        if (hubWorld == null) {
            return List.of();
        }
        List<Player> entrants = new ArrayList<>();
        for (UUID playerId : challenge.expectedParticipantIds()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || !stillBelongsToAcceptedParty(challenge, playerId)) {
                continue;
            }
            boolean inHub = player.getWorld().getUID().equals(hubWorld.getUID());
            boolean inField = challenge.field() != null
                    && player.getWorld().getUID().equals(challenge.field().world().getUID());
            if (!inHub && !inField) {
                continue;
            }
            entrants.add(player);
        }
        return entrants;
    }

    private boolean stillBelongsToAcceptedParty(
            @NotNull BossChallengeInstance challenge,
            @NotNull UUID playerId
    ) {
        if (challenge.partyKey().startsWith("solo:")) {
            return challenge.initiatorId().equals(playerId);
        }
        Party currentParty = partyService.findParty(playerId);
        return currentParty != null && challenge.partyKey().equals("party:" + currentParty.getPartyId());
    }

    private @NotNull List<Player> onlinePlayers(@NotNull Collection<UUID> playerIds) {
        List<Player> result = new ArrayList<>();
        for (UUID playerId : playerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                result.add(player);
            }
        }
        return result;
    }

    private boolean hasParticipantInField(@NotNull BossChallengeInstance challenge) {
        BossFieldInstance field = challenge.field();
        if (field == null) {
            return false;
        }
        for (Player player : onlinePlayers(challenge.participantIds())) {
            if (player.getWorld().getUID().equals(field.world().getUID())) {
                return true;
            }
        }
        return false;
    }

    private @Nullable BossChallengeInstance findInProgressChallengeByParticipant(@NotNull UUID playerId) {
        for (BossChallengeInstance challenge : challengesById.values()) {
            if (challenge.state() == BossChallengeState.IN_PROGRESS && challenge.participantIds().contains(playerId)) {
                return challenge;
            }
        }
        return null;
    }

    private void reviveParticipant(@NotNull UUID challengeId, @NotNull UUID playerId) {
        BossChallengeInstance challenge = challengesById.get(challengeId);
        Player player = Bukkit.getPlayer(playerId);
        if (challenge == null || challenge.state() != BossChallengeState.IN_PROGRESS
                || challenge.field() == null || player == null || !player.isOnline()) {
            return;
        }
        Location spawn = challenge.config().playerSpawnLocation().toLocation(challenge.field().world());
        worldService.teleportPlayerAsync(player, spawn, null).whenComplete((success, throwable) ->
                runSync(() -> {
                    if (challenge.state() == BossChallengeState.IN_PROGRESS
                            && (throwable != null || !Boolean.TRUE.equals(success))) {
                        endChallenge(challenge, BossChallengeEndReason.TRANSFER_FAILED);
                    }
                }));
    }

    private void tickEntryVisuals() {
        double baseAngle = entryVisualFrame * 0.28D;
        Set<String> activePromptIds = new HashSet<>();
        for (String bossId : mobService.getLoadedMobIdsByCategory(List.of(MobCategory.BOSS))) {
            MobTemplate template = mobService.findTemplate(bossId);
            if (template == null || template.challenge() == null) {
                continue;
            }

            World entryWorld = resolveLocationWorld(template.challenge().entryLocation());
            if (entryWorld == null) {
                continue;
            }
            Location entry = template.challenge().entryLocation().toLocation(entryWorld);
            if (!renderEntryAnimation(entry, template.challenge().entryRadius(), baseAngle)) {
                continue;
            }
            updateEntryPrompt(template, entry);
            activePromptIds.add(template.id());
        }
        removeInactiveEntryPrompts(activePromptIds);
        entryVisualFrame++;
    }

    private boolean renderEntryAnimation(@NotNull Location entry, double radius, double baseAngle) {
        World world = entry.getWorld();
        if (world == null || !hasNearbyViewer(entry, world)) {
            return false;
        }

        double visualRadius = Math.max(0.75D, radius);
        List<Location> ringLocations = new ArrayList<>(ENTRY_RING_POINTS);
        for (int i = 0; i < ENTRY_RING_POINTS; i++) {
            double angle = baseAngle + ((Math.PI * 2.0D * i) / ENTRY_RING_POINTS);
            double x = Math.cos(angle) * visualRadius;
            double z = Math.sin(angle) * visualRadius;
            double y = 0.18D + (Math.sin((baseAngle * 1.2D) + (i * 0.5D)) * 0.12D);
            ringLocations.add(entry.clone().add(x, y, z));
        }
        particleDisplayService.spawnForNearbyViewers(
                entry,
                ringLocations,
                SharedParticleDefinitions.BOSS_ENTRY_RING_DUST
        );
        particleDisplayService.spawnForNearbyViewers(
                entry.clone().add(0.0D, 0.95D, 0.0D),
                SharedParticleDefinitions.BOSS_ENTRY_SOUL_FIRE
        );
        return true;
    }

    private boolean hasNearbyViewer(@NotNull Location center, @NotNull World world) {
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= ENTRY_VIEWER_DISTANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    private void updateEntryPrompt(@NotNull MobTemplate template, @NotNull Location entry) {
        String text = "&c&lボス挑戦 &f" + template.displayName() + "\n&eスニーク&fで挑戦";
        Location promptLocation = entry.clone().add(0.0D, ENTRY_PROMPT_Y_OFFSET, 0.0D);
        EntryPromptDisplay current = entryPromptDisplays.get(template.id());
        try {
            if (current == null) {
                entryPromptDisplays.put(template.id(), createEntryPromptDisplay(promptLocation, text));
            } else {
                current.display().setAnchor(DisplayAnchor.fixed(promptLocation));
                if (!current.text().equals(text)) {
                    current.display().setText(text);
                    entryPromptDisplays.put(template.id(), new EntryPromptDisplay(current.display(), text));
                }
            }
        } catch (IllegalStateException ignored) {
            entryPromptDisplays.put(template.id(), createEntryPromptDisplay(promptLocation, text));
        }
    }

    private @NotNull EntryPromptDisplay createEntryPromptDisplay(@NotNull Location location, @NotNull String text) {
        return new EntryPromptDisplay(
                displayTextService.create(
                        DisplayAnchor.fixed(location),
                        DisplayTextOptions.defaults(text)
                                .withLineWidth(300)
                                .withViewRange(48.0F)
                                .withShadowed(true)
                ),
                text
        );
    }

    private void removeInactiveEntryPrompts(@NotNull Set<String> activePromptIds) {
        for (String id : List.copyOf(entryPromptDisplays.keySet())) {
            if (!activePromptIds.contains(id)) {
                removeEntryPrompt(id);
            }
        }
    }

    private void clearEntryPromptDisplays() {
        for (String id : List.copyOf(entryPromptDisplays.keySet())) {
            removeEntryPrompt(id);
        }
    }

    private void removeEntryPrompt(@NotNull String id) {
        EntryPromptDisplay display = entryPromptDisplays.remove(id);
        if (display == null) {
            return;
        }
        try {
            display.display().destroy();
        } catch (IllegalStateException ignored) {
            // DisplayTextService 側ですでに破棄済みの場合は同期だけ済ませます。
        }
    }

    private @NotNull CompletableFuture<Boolean> teleportParticipantsOutAsync(
            @NotNull BossChallengeInstance challenge
    ) {
        List<Player> players = onlinePlayers(exitParticipantIds(challenge));
        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        List<CompletableFuture<Boolean>> transfers = players.stream()
                .map(player -> teleportParticipantOutAsync(challenge, player))
                .toList();
        return CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .handle((ignored, throwable) -> throwable == null
                        && transfers.stream().allMatch(future -> Boolean.TRUE.equals(future.getNow(false))));
    }

    private @NotNull CompletableFuture<Boolean> teleportParticipantOutAsync(
            @NotNull BossChallengeInstance challenge,
            @NotNull Player player
    ) {
        World entryWorld = resolveLocationWorld(challenge.config().entryLocation());
        Location entryLocation = entryWorld == null
                ? null
                : challenge.config().entryLocation().toLocation(entryWorld);
        CompletableFuture<Boolean> entryTransfer = entryLocation == null
                ? CompletableFuture.completedFuture(false)
                : worldService.teleportPlayerAsync(player, entryLocation, null);
        return entryTransfer.handle((success, throwable) -> throwable == null && Boolean.TRUE.equals(success))
                .thenCompose(success -> {
                    if (success) {
                        return CompletableFuture.completedFuture(true);
                    }
                    BossFieldInstance field = challenge.field();
                    WorldMasterData hubData = worldService.getById(hubWorldId);
                    World hubWorld = hubData == null ? null : worldService.resolveLoadedWorld(hubData);
                    World fallbackWorld = Bukkit.getWorlds().stream()
                            .filter(world -> field == null || !world.getUID().equals(field.world().getUID()))
                            .filter(world -> hubWorld == null || !world.getUID().equals(hubWorld.getUID()))
                            .findFirst()
                            .orElse(null);
                    if (fallbackWorld == null) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return worldService.teleportPlayerAsync(player, fallbackWorld.getSpawnLocation(), null)
                            .handle((fallbackSuccess, fallbackThrowable) ->
                                    fallbackThrowable == null && Boolean.TRUE.equals(fallbackSuccess));
                });
    }

    private void forceShutdownChallenge(@NotNull BossChallengeInstance challenge) {
        challenge.state(BossChallengeState.ENDING);
        cancelStartCountdown(challenge);
        destroyCancelController(challenge.challengeId());
        if (!challenge.participantsConfirmed()) {
            challenge.confirmParticipants(
                    eligibleParticipantsForEntry(challenge).stream().map(Player::getUniqueId).toList()
            );
        }
        BukkitTask resultWaitTask = challenge.resultWaitTask();
        if (resultWaitTask != null) {
            resultWaitTask.cancel();
            challenge.resultWaitTask(null);
        }
        destroyDamageResult(challenge);
        UUID bossMobId = challenge.bossMobInstanceId();
        if (bossMobId != null) {
            challengeIdByBossMob.remove(bossMobId);
            mobService.destroy(bossMobId);
        }
        for (UUID participantId : exitParticipantIds(challenge)) {
            playerDeathService.recoverNow(participantId);
        }
        teleportParticipantsOutAsync(challenge);
        if (challenge.field() != null) {
            fieldInstanceService.destroyField(challenge.field());
        }
        challenge.state(BossChallengeState.ENDED);
    }

    private @NotNull CompletableFuture<List<Boolean>> teleportParticipantsToHubAsync(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData hubData
    ) {
        List<Player> players = onlinePlayers(challenge.expectedParticipantIds());
        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<Boolean>> transfers = new ArrayList<>(players.size());
        for (Player player : players) {
            transfers.add(worldService.teleportToSpawnAsync(player, hubData));
        }
        return CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> transfers.stream()
                        .map(future -> Boolean.TRUE.equals(future.getNow(false)))
                        .toList());
    }

    private void notifyParticipants(@NotNull BossChallengeInstance challenge, @NotNull PlayerMsgId msgId, Object... args) {
        for (Player player : onlinePlayers(displayParticipantIds(challenge))) {
            messageService.send(player, msgId, args);
        }
    }

    private void notifyExpectedParticipants(
            @NotNull BossChallengeInstance challenge,
            @NotNull PlayerMsgId msgId,
            Object... args
    ) {
        for (Player player : onlinePlayers(challenge.expectedParticipantIds())) {
            messageService.send(player, msgId, args);
        }
    }

    private @NotNull List<UUID> displayParticipantIds(@NotNull BossChallengeInstance challenge) {
        return challenge.participantsConfirmed()
                ? challenge.participantIds()
                : challenge.expectedParticipantIds();
    }

    private @NotNull List<UUID> exitParticipantIds(@NotNull BossChallengeInstance challenge) {
        return displayParticipantIds(challenge);
    }

    private void runSync(@NotNull Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (RuntimeException ignored) {
            // Plugin disable と競合した非同期完了は stop() 側の同期回収に委ねます。
        }
    }

    private record DamageLine(@NotNull UUID playerId, double damage) {
    }

    private record EntryPromptDisplay(
            @NotNull DisplayTextService.ManagedTextDisplay display,
            @NotNull String text
    ) {
    }

    /** プレイヤーによる挑戦中止の結果です。 */
    public enum PlayerCancelResult {
        STOPPED,
        NO_CHALLENGE,
        NOT_LEADER
    }
}
