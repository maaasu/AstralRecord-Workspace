package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeEndReason;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeState;
import io.github.maaasu.astralRecord.feature.boss.model.BossFieldInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossLocation;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
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

/**
 * Coordinates boss challenge acceptance, field entry, and completion.
 */
public final class BossChallengeService {
    private static final long FIELD_START_DELAY_TICKS = 40L;
    private static final long DEFEATED_RESULT_WAIT_TICKS = 15L * 20L;
    private static final long ENTRY_VISUAL_PERIOD_TICKS = 10L;
    private static final int ENTRY_RING_POINTS = 10;
    private static final double ENTRY_PROMPT_Y_OFFSET = 2.35D;
    private static final double ENTRY_VIEWER_DISTANCE_SQUARED = 64.0D * 64.0D;

    private final AstralRecord plugin;
    private final MobService mobService;
    private final WorldService worldService;
    private final PartyService partyService;
    private final PlayerMessageService messageService;
    private final BossFieldInstanceService fieldInstanceService;
    private final ParticleDisplayService particleDisplayService;
    private final DisplayTextService displayTextService;
    private final String hubWorldId;
    private final Map<UUID, BossChallengeInstance> challengesById = new ConcurrentHashMap<>();
    private final Map<String, UUID> challengeIdByPartyKey = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> challengeIdByBossMob = new ConcurrentHashMap<>();
    private final Map<String, EntryPromptDisplay> entryPromptDisplays = new HashMap<>();
    private BukkitTask tickTask;
    private BukkitTask entryVisualTask;
    private long entryVisualFrame;

    public BossChallengeService(
            @NotNull AstralRecord plugin,
            @NotNull MobService mobService,
            @NotNull WorldService worldService,
            @NotNull PartyService partyService,
            @NotNull PlayerMessageService messageService,
            @NotNull BossFieldInstanceService fieldInstanceService,
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull DisplayTextService displayTextService,
            @NotNull String hubWorldId
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.worldService = worldService;
        this.partyService = partyService;
        this.messageService = messageService;
        this.fieldInstanceService = fieldInstanceService;
        this.particleDisplayService = particleDisplayService;
        this.displayTextService = displayTextService;
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
        for (BossChallengeInstance challenge : List.copyOf(challengesById.values())) {
            endChallenge(challenge, BossChallengeEndReason.PLUGIN_SHUTDOWN);
        }
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
        for (String bossId : mobService.getLoadedMobIdsByCategory(List.of(MobCategory.BOSS))) {
            MobTemplate template = mobService.findTemplate(bossId);
            if (template == null || template.challenge() == null) {
                continue;
            }
            if (isInsideEntry(player, template.challenge())) {
                acceptChallenge(player, bossId);
                return true;
            }
        }
        if (notifyMissing) {
            messageService.send(player, PlayerMsgId.P_6500);
        }
        return false;
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
        challengesById.put(challenge.challengeId(), challenge);
        challengeIdByPartyKey.put(partyKey, challenge.challengeId());
        Logger.log(LogId.I_6500, challenge.challengeId(), template.id(), partyKey);

        notifyParticipants(challenge, PlayerMsgId.P_6508, template.displayName());
        teleportParticipantsToHubAsync(challenge, hubData).whenComplete((results, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> finishHubTransfer(challenge.challengeId(), fieldData, results, throwable)));
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
     * Returns active challenge descriptions for admin commands.
     *
     * @return description lines
     */
    public @NotNull List<String> describeActive() {
        List<String> lines = new ArrayList<>();
        for (BossChallengeInstance challenge : challengesById.values()) {
            long elapsed = challenge.startedAtMs() <= 0L ? 0L : (System.currentTimeMillis() - challenge.startedAtMs()) / 1000L;
            lines.add(String.format(
                    Locale.ROOT,
                    "%s | %s | %s | members=%d | elapsed=%ds",
                    challenge.challengeId(),
                    challenge.bossTemplate().id(),
                    challenge.state(),
                    challenge.participantIds().size(),
                    elapsed
            ));
        }
        return lines;
    }

    /**
     * Stops a challenge by challenge ID prefix or party key.
     *
     * @param key challenge ID prefix or party key
     * @return true when stopped
     */
    public boolean stopChallenge(@NotNull String key) {
        UUID mappedChallengeId = challengeIdByPartyKey.get(key);
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
        if (throwable != null || results == null || results.isEmpty() || results.stream().anyMatch(result -> !Boolean.TRUE.equals(result))) {
            notifyParticipants(challenge, PlayerMsgId.P_6521, challenge.bossTemplate().displayName());
            endChallenge(challenge, BossChallengeEndReason.TRANSFER_FAILED);
            return;
        }
        beginFieldPreparation(challenge, fieldData);
    }

    private void beginFieldPreparation(@NotNull BossChallengeInstance challenge, @NotNull WorldMasterData fieldData) {
        fieldInstanceService.createFieldAsync(challenge, fieldData).whenComplete((field, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> finishFieldPreparation(challenge.challengeId(), field, throwable)));
    }

    private void finishFieldPreparation(
            @NotNull UUID challengeId,
            @Nullable BossFieldInstance field,
            @Nullable Throwable throwable
    ) {
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge == null || challenge.state() != BossChallengeState.PREPARING) {
            if (field != null) {
                fieldInstanceService.destroyField(field);
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

        List<Player> online = onlinePlayers(challenge.participantIds());
        if (online.isEmpty()) {
            endChallenge(challenge, BossChallengeEndReason.NO_PARTICIPANTS);
            return;
        }

        Location playerSpawn = challenge.config().playerSpawnLocation().toLocation(field.world());
        List<CompletableFuture<Boolean>> transferResults = new ArrayList<>();
        for (Player participant : online) {
            transferResults.add(worldService.teleportPlayerAsync(participant, playerSpawn.clone(), null));
        }

        CompletableFuture.allOf(transferResults.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(
                        plugin,
                        () -> finishFieldStartAfterTransfers(challengeId, transferResults, throwable)
                ));
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

        Location bossSpawn = challenge.config().bossSpawnLocation().toLocation(field.world());
        MobInstance boss = mobService.spawn(challenge.bossTemplate().id(), bossSpawn);
        if (boss == null) {
            endChallenge(challenge, BossChallengeEndReason.BOSS_SPAWN_FAILED);
            return;
        }
        applyParticipantScaling(challenge, boss);

        challenge.bossMobInstanceId(boss.instanceId());
        challengeIdByBossMob.put(boss.instanceId(), challenge.challengeId());
        challenge.markStarted();
        Logger.log(LogId.I_6501, challenge.challengeId(), challenge.bossTemplate().id(), field.worldName());
        notifyParticipants(challenge, PlayerMsgId.P_6510, challenge.bossTemplate().displayName(), challenge.config().timeLimitSeconds());
    }

    private void applyParticipantScaling(@NotNull BossChallengeInstance challenge, @NotNull MobInstance boss) {
        if (!challenge.config().scaling().enabled()) {
            return;
        }
        int extraPlayers = Math.max(0, challenge.participantIds().size() - 1);
        double healthMultiplier = 1.0D + extraPlayers * Math.max(0.0D, challenge.config().scaling().healthPerExtraPlayer()) / 100.0D;
        double attackMultiplier = 1.0D + extraPlayers * Math.max(0.0D, challenge.config().scaling().attackPerExtraPlayer()) / 100.0D;
        boss.currentHealth(boss.currentHealth() * healthMultiplier);
        boss.outgoingDamageMultiplier(attackMultiplier);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (BossChallengeInstance challenge : List.copyOf(challengesById.values())) {
            if (challenge.state() != BossChallengeState.IN_PROGRESS) {
                continue;
            }
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

    private void endChallenge(@NotNull BossChallengeInstance challenge, @NotNull BossChallengeEndReason reason) {
        if (challenge.state() == BossChallengeState.ENDED) {
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
        completeChallenge(challenge, reason);
    }

    private void beginDefeatedResultWait(@NotNull BossChallengeInstance challenge, @NotNull Location deathLocation) {
        if (challenge.state() == BossChallengeState.ENDED || challenge.state() == BossChallengeState.RESULT_WAITING) {
            return;
        }
        challenge.state(BossChallengeState.RESULT_WAITING);
        challenge.resultWaitEndsAtMs(System.currentTimeMillis() + DEFEATED_RESULT_WAIT_TICKS * 50L);
        Logger.log(LogId.I_6502, challenge.challengeId(), challenge.bossTemplate().id(), BossChallengeEndReason.DEFEATED.name());

        UUID bossMobId = challenge.bossMobInstanceId();
        if (bossMobId != null) {
            challengeIdByBossMob.remove(bossMobId);
        }

        notifyParticipants(challenge, PlayerMsgId.P_6511, challenge.bossTemplate().displayName());
        showDamageResult(challenge, deathLocation);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> completeChallenge(challenge, BossChallengeEndReason.DEFEATED),
                DEFEATED_RESULT_WAIT_TICKS
        );
        challenge.resultWaitTask(task);
    }

    private void completeChallenge(@NotNull BossChallengeInstance challenge, @NotNull BossChallengeEndReason reason) {
        if (challenge.state() == BossChallengeState.ENDED) {
            return;
        }
        challenge.state(BossChallengeState.ENDED);
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
        } else {
            notifyParticipants(challenge, PlayerMsgId.P_6512, challenge.bossTemplate().displayName(), reason.name());
        }

        WorldMasterData hubData = worldService.getById(hubWorldId);
        if (hubData != null) {
            teleportParticipantsToHub(challenge, hubData);
        }

        BossFieldInstance field = challenge.field();
        if (field != null) {
            fieldInstanceService.destroyField(field);
        }
        challengeIdByPartyKey.remove(challenge.partyKey());
        challengesById.remove(challenge.challengeId());
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
        StringBuilder text = new StringBuilder("&6&lBOSS CLEAR &f")
                .append(challenge.bossTemplate().displayName())
                .append("\n&7Returning to hub in &e")
                .append(remainingSeconds)
                .append("s")
                .append("\n&d&lDamage Ranking &7Total &f")
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
                    .append(String.format(Locale.ROOT, "%.1f%%", rate));
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

    private @NotNull CompletableFuture<List<Boolean>> teleportParticipantsToHubAsync(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData hubData
    ) {
        List<Player> players = onlinePlayers(challenge.participantIds());
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

    private void teleportParticipantsToHub(@NotNull BossChallengeInstance challenge, @NotNull WorldMasterData hubData) {
        for (Player player : onlinePlayers(challenge.participantIds())) {
            worldService.teleportToSpawn(player, hubData);
        }
    }

    private void notifyParticipants(@NotNull BossChallengeInstance challenge, @NotNull PlayerMsgId msgId, Object... args) {
        for (Player player : onlinePlayers(challenge.participantIds())) {
            messageService.send(player, msgId, args);
        }
    }

    private record DamageLine(@NotNull UUID playerId, double damage) {
    }

    private record EntryPromptDisplay(
            @NotNull DisplayTextService.ManagedTextDisplay display,
            @NotNull String text
    ) {
    }
}
