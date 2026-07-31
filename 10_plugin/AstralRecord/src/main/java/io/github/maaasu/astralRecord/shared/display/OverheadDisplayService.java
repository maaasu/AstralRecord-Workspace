package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤーと Mob の頭上に、実体 TextDisplay としてステータス文字列を表示するサービスです。
 */
public class OverheadDisplayService {

    private static final long UPDATE_INTERVAL_TICKS = 5L;
    private static final double PLAYER_TEXT_OFFSET = -1.65D;
    private static final double MOB_PASSENGER_TEXT_OFFSET = 0.15D;
    private static final int BAR_LENGTH = 12;
    private static final String HIDDEN_NAME_TEAM = "ar_hidden_names";

    private final DisplayTextService displayService;
    private final StatusService statusService;
    private final MobService mobService;
    private final PlayerClassService playerClassService;
    private final Map<UUID, DisplayTextService.ManagedTextDisplay> playerDisplays = new HashMap<>();
    private final Map<UUID, DisplayTextService.ManagedTextDisplay> mobDisplays = new HashMap<>();
    private final Set<UUID> suspendedPlayerDisplays = ConcurrentHashMap.newKeySet();

    private BukkitTask task;

    /**
     * サービスを構築します。
     *
     * @param displayService TextDisplay 管理サービス
     * @param statusService  ステータス参照サービス
     * @param mobService     Mob 管理サービス
     */
    public OverheadDisplayService(
            @NotNull DisplayTextService displayService,
            @NotNull StatusService statusService,
            @NotNull MobService mobService,
            @NotNull PlayerClassService playerClassService
    ) {
        this.displayService = displayService;
        this.statusService = statusService;
        this.mobService = mobService;
        this.playerClassService = playerClassService;
    }

    /**
     * 頭上表示の定期更新を開始します。
     *
     * @param plugin scheduler を起動するプラグイン
     */
    public void start(@NotNull Plugin plugin) {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, (Runnable) this::tick, 1L, UPDATE_INTERVAL_TICKS);
    }

    /**
     * 頭上表示の定期更新を停止し、表示中の TextDisplay を破棄します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        restoreVanillaPlayerNames();
        destroyAll(playerDisplays);
        destroyAll(mobDisplays);
    }

    public void suspendPlayerDisplay(@NotNull UUID playerId) {
        suspendedPlayerDisplays.add(playerId);
        DisplayTextService.ManagedTextDisplay display = playerDisplays.remove(playerId);
        if (display != null) {
            display.destroy();
        }
    }

    public void resumePlayerDisplay(@NotNull UUID playerId) {
        suspendedPlayerDisplays.remove(playerId);
    }

    @NotNull
    public Set<UUID> getSuspendedPlayerDisplays() {
        return Collections.unmodifiableSet(suspendedPlayerDisplays);
    }

    private void tick() {
        updatePlayerDisplays();
        updateMobDisplays();
    }

    private void updatePlayerDisplays() {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        Set<UUID> activeSubjects = new HashSet<>();
        hideVanillaPlayerNames(onlinePlayers);

        for (Player subject : onlinePlayers) {
            UUID subjectId = subject.getUniqueId();
            activeSubjects.add(subjectId);
            subject.setCustomNameVisible(false);

            if (suspendedPlayerDisplays.contains(subjectId)) {
                DisplayTextService.ManagedTextDisplay suspendedDisplay = playerDisplays.remove(subjectId);
                if (suspendedDisplay != null) {
                    suspendedDisplay.destroy();
                }
                continue;
            }

            DisplayTextService.ManagedTextDisplay display = playerDisplays.computeIfAbsent(
                    subjectId,
                    ignored -> displayService.create(
                            DisplayAnchor.entity(subject, overheadOffset(subject, PLAYER_TEXT_OFFSET)),
                            DisplayTextOptions.overhead(playerText(subject))
                    )
            );
            display.setAnchor(DisplayAnchor.entity(subject, overheadOffset(subject, PLAYER_TEXT_OFFSET)));
            display.setText(playerText(subject));
        }

        removeStaleDisplays(playerDisplays, activeSubjects);
    }

    private void updateMobDisplays() {
        Set<UUID> activeSubjects = new HashSet<>();

        for (MobInstance instance : mobService.getInstances()) {
            Entity entity = resolveMobEntity(instance);
            if (entity == null) {
                continue;
            }
            UUID instanceId = instance.instanceId();
            activeSubjects.add(instanceId);
            entity.setCustomNameVisible(false);

            DisplayTextService.ManagedTextDisplay display = mobDisplays.computeIfAbsent(
                    instanceId,
                    ignored -> displayService.create(
                            mobDisplayAnchor(instance, entity),
                            DisplayTextOptions.overhead(mobText(instance))
                    )
            );
            display.setAnchor(mobDisplayAnchor(instance, entity));
            display.setText(mobText(instance));
        }

        removeStaleDisplays(mobDisplays, activeSubjects);
    }

    private void removeStaleDisplays(
            @NotNull Map<UUID, DisplayTextService.ManagedTextDisplay> displays,
            @NotNull Set<UUID> activeSubjects
    ) {
        Set<UUID> stale = new HashSet<>(displays.keySet());
        stale.removeAll(activeSubjects);
        for (UUID subjectId : stale) {
            DisplayTextService.ManagedTextDisplay display = displays.remove(subjectId);
            if (display != null) {
                display.destroy();
            }
        }
    }

    private void destroyAll(@NotNull Map<UUID, DisplayTextService.ManagedTextDisplay> displays) {
        for (DisplayTextService.ManagedTextDisplay display : displays.values()) {
            display.destroy();
        }
        displays.clear();
    }

    private @NotNull Vector overheadOffset(@NotNull Entity entity, double offset) {
        return new Vector(0.0D, entity.getHeight() + offset, 0.0D);
    }

    private @NotNull DisplayAnchor mobDisplayAnchor(@NotNull MobInstance instance, @NotNull Entity entity) {
        if (instance.template().blockMaterial() == null) {
            return DisplayAnchor.entity(entity, mobPassengerOverheadOffset());
        }
        return DisplayAnchor.fixed(instance.currentLocation().add(0.0D, 1.35D, 0.0D));
    }

    /**
     * Mob passenger の乗車位置へ加える頭上余白を返します。
     * passenger の基準位置には Mob 種別ごとの高さが既に反映されるため、実体高による再補正は行いません。
     *
     * @return passenger 基準の頭上余白
     */
    static @NotNull Vector mobPassengerOverheadOffset() {
        return new Vector(0.0D, MOB_PASSENGER_TEXT_OFFSET, 0.0D);
    }

    private Entity resolveMobEntity(@NotNull MobInstance instance) {
        UUID entityId = instance.bukkitEntityId();
        if (entityId == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(entityId);
        return entity != null && entity.isValid() && !entity.isDead() ? entity : null;
    }

    private void hideVanillaPlayerNames(@NotNull Collection<? extends Player> onlinePlayers) {
        Set<String> activeNames = new HashSet<>();
        for (Player player : onlinePlayers) {
            activeNames.add(player.getName());
        }

        for (Player viewer : onlinePlayers) {
            Scoreboard scoreboard = viewer.getScoreboard();
            if (scoreboard == Bukkit.getScoreboardManager().getMainScoreboard()) {
                scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
                viewer.setScoreboard(scoreboard);
            }

            Team team = scoreboard.getTeam(HIDDEN_NAME_TEAM);
            if (team == null) {
                team = scoreboard.registerNewTeam(HIDDEN_NAME_TEAM);
            }
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

            for (String entry : new HashSet<>(team.getEntries())) {
                if (!activeNames.contains(entry)) {
                    team.removeEntry(entry);
                }
            }
            for (String playerName : activeNames) {
                if (!team.getEntries().contains(playerName)) {
                    team.addEntry(playerName);
                }
            }
        }
    }

    private void restoreVanillaPlayerNames() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Team team = player.getScoreboard().getTeam(HIDDEN_NAME_TEAM);
            if (team != null) {
                team.unregister();
            }
        }
    }

    @NotNull
    private String playerText(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return "&f" + player.getName();
        }

        StatusSnapshot snapshot = statusService.getStatus(astPlayer);
        String className = playerClassService.getDisplayName(astPlayer.getClassId());
        ShieldRechargeState recharge = statusService.getShieldRechargeState(astPlayer);
        String shield = recharge == null
                ? shieldIconLine(snapshot.getCurrentShield(), statusService.getShieldDisplayCapacity(astPlayer))
                : "\n" + rechargeBar(recharge, System.currentTimeMillis());
        return String.format(
                Locale.ROOT,
                "&7[%s&7] &f%s\n%s\n%s%s",
                className,
                player.getName(),
                bar("HP", snapshot.getCurrentHp(), snapshot.getMaxValue(StatusType.MAX_HEALTH), "&c"),
                bar("MP", snapshot.getCurrentMp(), snapshot.getMaxValue(StatusType.MAX_MANA), "&9"),
                shield
        );
    }

    @NotNull
    private String mobText(@NotNull MobInstance instance) {
        double maxHealth = instance.maxHealth();
        ShieldRechargeState recharge = instance.shieldRechargeState();
        String shield = recharge != null
                ? "\n" + rechargeBar(recharge, System.currentTimeMillis())
                : instance.template().shield().active()
                    ? "\n" + bar("SH", instance.currentShield(), instance.shieldDisplayCapacity(), "&b")
                    : "";
        String cast = instance.isSkillCasting()
                ? "\n" + castBar(instance)
                : "";
        return String.format(
                Locale.ROOT,
                "&7Lv.%d %s\n%s%s%s",
                instance.template().level(),
                instance.template().displayName(),
                bar("HP", instance.currentHealth(), maxHealth, "&c"),
                shield,
                cast
        );
    }

    private @NotNull String castBar(@NotNull MobInstance instance) {
        long duration = Math.max(1L, instance.castingDurationTicks());
        double elapsed = duration - Math.max(0L, instance.castingRemainingTicks());
        double ratio = Math.clamp(elapsed / duration, 0.0D, 1.0D);
        int filled = (int) Math.round(ratio * BAR_LENGTH);
        StringBuilder builder = new StringBuilder();
        builder.append("&eCAST [").append("&e");
        builder.repeat("|", Math.max(0, filled));
        builder.append("&8");
        builder.repeat("|", Math.max(0, BAR_LENGTH - filled));
        builder.append("&e] &f").append(instance.castingSkillName() == null ? "" : instance.castingSkillName());
        return builder.toString();
    }

    private @NotNull String bar(@NotNull String label, double current, double max, @NotNull String color) {
        double ratio = max <= 0.0D ? 0.0D : Math.clamp(current / max, 0.0D, 1.0D);
        int filled = (int) Math.round(ratio * BAR_LENGTH);
        StringBuilder builder = new StringBuilder();
        builder.append(color).append(label).append(" [").append(color);
        builder.repeat("|", Math.max(0, filled));
        builder.append("&8");
        builder.repeat("|", Math.max(0, BAR_LENGTH - filled));
        builder.append(color).append("] &f").append(number(current)).append("&7/&f").append(number(max));
        return builder.toString();
    }

    private @NotNull String rechargeBar(@NotNull ShieldRechargeState state, long nowMs) {
        double ratio = state.progress(nowMs);
        int filled = (int) Math.round(ratio * BAR_LENGTH);
        StringBuilder builder = new StringBuilder();
        builder.append("&6RC [&6");
        builder.repeat("|", Math.max(0, filled));
        builder.append("&8");
        builder.repeat("|", Math.max(0, BAR_LENGTH - filled));
        builder.append("&6] &f")
                .append(String.format(Locale.ROOT, "%.1fs", state.remainingMs(nowMs) / 1000.0D));
        return builder.toString();
    }

    private @NotNull String shieldIconLine(double current, double max) {
        if (max <= 0.0D) {
            return "";
        }
        return String.format(Locale.ROOT, "\n&b◆ &7x &f%s", number(current));
    }

    private String number(double value) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0D, value));
    }
}
