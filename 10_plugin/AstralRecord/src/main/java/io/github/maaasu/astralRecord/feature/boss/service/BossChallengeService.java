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
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates boss challenge acceptance, field entry, and completion.
 */
public final class BossChallengeService {
    private static final long FIELD_START_DELAY_TICKS = 40L;

    private final AstralRecord plugin;
    private final MobService mobService;
    private final WorldService worldService;
    private final PartyService partyService;
    private final PlayerMessageService messageService;
    private final BossFieldInstanceService fieldInstanceService;
    private final String hubWorldId;
    private final Map<UUID, BossChallengeInstance> challengesById = new ConcurrentHashMap<>();
    private final Map<String, UUID> challengeIdByPartyKey = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> challengeIdByBossMob = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    public BossChallengeService(
            @NotNull AstralRecord plugin,
            @NotNull MobService mobService,
            @NotNull WorldService worldService,
            @NotNull PartyService partyService,
            @NotNull PlayerMessageService messageService,
            @NotNull BossFieldInstanceService fieldInstanceService,
            @NotNull String hubWorldId
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.worldService = worldService;
        this.partyService = partyService;
        this.messageService = messageService;
        this.fieldInstanceService = fieldInstanceService;
        this.hubWorldId = hubWorldId;
    }

    /**
     * Starts the challenge watchdog.
     */
    public void start() {
        if (tickTask != null) {
            return;
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /**
     * Stops all active challenges and the watchdog.
     */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
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

        try {
            challenge.field(fieldInstanceService.createField(challenge, fieldData));
        } catch (IOException ex) {
            Logger.log(LogId.E_6500, ex, template.id(), config.fieldWorldId());
            messageService.send(player, PlayerMsgId.P_6509, config.fieldWorldId());
            endChallenge(challenge, BossChallengeEndReason.FIELD_PREPARE_FAILED);
            return;
        }

        notifyParticipants(challenge, PlayerMsgId.P_6508, template.displayName());
        teleportParticipantsToHub(challenge, hubData);
        Bukkit.getScheduler().runTaskLater(plugin, () -> startField(challenge.challengeId()), FIELD_START_DELAY_TICKS);
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
     * Handles defeat of an active boss mob.
     *
     * @param mobInstanceId defeated mob instance ID
     */
    public void handleBossDefeated(@NotNull UUID mobInstanceId) {
        UUID challengeId = challengeIdByBossMob.get(mobInstanceId);
        if (challengeId == null) {
            return;
        }
        BossChallengeInstance challenge = challengesById.get(challengeId);
        if (challenge != null) {
            endChallenge(challenge, BossChallengeEndReason.DEFEATED);
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
        for (Player participant : online) {
            worldService.teleportPlayerAsync(participant, playerSpawn.clone(), null);
        }

        Location bossSpawn = challenge.config().bossSpawnLocation().toLocation(field.world());
        MobInstance boss = mobService.spawn(challenge.bossTemplate().id(), bossSpawn);
        if (boss == null) {
            endChallenge(challenge, BossChallengeEndReason.BOSS_SPAWN_FAILED);
            return;
        }
        applyHealthScaling(challenge, boss);

        challenge.bossMobInstanceId(boss.instanceId());
        challengeIdByBossMob.put(boss.instanceId(), challenge.challengeId());
        challenge.markStarted();
        Logger.log(LogId.I_6501, challenge.challengeId(), challenge.bossTemplate().id(), field.worldName());
        notifyParticipants(challenge, PlayerMsgId.P_6510, challenge.bossTemplate().displayName(), challenge.config().timeLimitSeconds());
    }

    private void applyHealthScaling(@NotNull BossChallengeInstance challenge, @NotNull MobInstance boss) {
        if (!challenge.config().scaling().enabled()) {
            return;
        }
        int extraPlayers = Math.max(0, challenge.participantIds().size() - 1);
        double multiplier = 1.0D + extraPlayers * Math.max(0.0D, challenge.config().scaling().healthPerExtraPlayer()) / 100.0D;
        boss.currentHealth(boss.currentHealth() * multiplier);
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
        challenge.state(BossChallengeState.ENDED);
        Logger.log(LogId.I_6502, challenge.challengeId(), challenge.bossTemplate().id(), reason.name());

        UUID bossMobId = challenge.bossMobInstanceId();
        if (bossMobId != null) {
            challengeIdByBossMob.remove(bossMobId);
            if (reason != BossChallengeEndReason.DEFEATED) {
                mobService.destroy(bossMobId);
            }
        }

        if (reason.success()) {
            notifyParticipants(challenge, PlayerMsgId.P_6511, challenge.bossTemplate().displayName());
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
}
