package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * プレイヤーと Mob の頭上に、実体 TextDisplay としてステータス文字列を表示するサービスです。
 */
public class OverheadDisplayService {

    private static final long UPDATE_INTERVAL_TICKS = 5L;
    private static final double PLAYER_TEXT_OFFSET = -1.50D;
    private static final double MOB_TEXT_OFFSET = -1.50D;
    private static final String HIDDEN_NAME_TEAM = "ar_hidden_names";

    private final DisplayTextService displayService;
    private final StatusService statusService;
    private final MobService mobService;
    private final Map<UUID, DisplayTextService.ManagedTextDisplay> playerDisplays = new HashMap<>();
    private final Map<UUID, DisplayTextService.ManagedTextDisplay> mobDisplays = new HashMap<>();

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
            @NotNull MobService mobService
    ) {
        this.displayService = displayService;
        this.statusService = statusService;
        this.mobService = mobService;
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
                            DisplayAnchor.entity(entity, overheadOffset(entity, MOB_TEXT_OFFSET)),
                            DisplayTextOptions.overhead(mobText(instance))
                    )
            );
            display.setAnchor(DisplayAnchor.entity(entity, overheadOffset(entity, MOB_TEXT_OFFSET)));
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
        return String.format(
                Locale.ROOT,
                "&f%s\n&cHP %s/%s &9MP %s/%s &eEN %s/%s",
                player.getName(),
                number(snapshot.getCurrentHp()),
                number(snapshot.getMaxValue(StatusType.MAX_HEALTH)),
                number(snapshot.getCurrentMp()),
                number(snapshot.getMaxValue(StatusType.MAX_MANA)),
                number(snapshot.getCurrentEnergy()),
                number(snapshot.getMaxValue(StatusType.MAX_ENERGY))
        );
    }

    @NotNull
    private String mobText(@NotNull MobInstance instance) {
        double maxHealth = instance.template().statValue(StatusType.MAX_HEALTH.name(), 1.0D);
        return String.format(
                Locale.ROOT,
                "%s\n&cHP %s/%s",
                instance.template().displayName(),
                number(instance.currentHealth()),
                number(maxHealth)
        );
    }

    private String number(double value) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0D, value));
    }
}
