package io.github.maaasu.astralRecord.feature.player.death;

import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * プレイヤー死亡中の一時状態、表示、復帰を管理します。
 */
public final class PlayerDeathService {

    private static final long DEATH_DURATION_MILLIS = 10_000L;
    private static final long TICK_INTERVAL_TICKS = 20L;
    private static final Title.Times COUNTDOWN_TITLE_TIMES =
        Title.Times.times(Duration.ZERO, Duration.ofMillis(1200L), Duration.ofMillis(100L));

    private final Plugin plugin;
    private final AccountService accountService;
    private final StatusService statusService;
    private final MobService mobService;
    private final WorldService worldService;
    private final DisplayTextService displayTextService;
    private final String joinSpawnWorldId;
    private final Map<UUID, DeathState> deaths = new ConcurrentHashMap<>();
    private Consumer<UUID> deathStartedListener = ignored -> { };
    private BukkitTask task;

    /**
     * サービスを構築します。
     *
     * @param plugin プラグイン本体
     * @param accountService 経験値更新サービス
     * @param statusService ステータス更新サービス
     * @param mobService Mob 管理サービス
     * @param worldService ワールド管理サービス
     * @param displayTextService TextDisplay 管理サービス
     * @param joinSpawnWorldId サーバー参加時スポーン先 WorldMasterData ID
     */
    public PlayerDeathService(
        @NotNull Plugin plugin,
        @NotNull AccountService accountService,
        @NotNull StatusService statusService,
        @NotNull MobService mobService,
        @NotNull WorldService worldService,
        @NotNull DisplayTextService displayTextService,
        @NotNull String joinSpawnWorldId
    ) {
        this.plugin = plugin;
        this.accountService = accountService;
        this.statusService = statusService;
        this.mobService = mobService;
        this.worldService = worldService;
        this.displayTextService = displayTextService;
        this.joinSpawnWorldId = joinSpawnWorldId;
    }

    /**
     * custom combat の死亡状態開始を受け取る listener を設定します。
     *
     * @param listener 死亡したプレイヤー UUID を受け取る listener
     */
    public void setDeathStartedListener(@NotNull Consumer<UUID> listener) {
        this.deathStartedListener = listener;
    }

    /**
     * 死亡状態の定期更新を開始します。
     */
    public void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, TICK_INTERVAL_TICKS);
    }

    /**
     * 死亡状態の定期更新を停止し、表示を破棄します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (DeathState state : deaths.values()) {
            destroyVisuals(state);
        }
        deaths.clear();
    }

    /**
     * プレイヤーを死亡状態にします。既に死亡中の場合は何もしません。
     *
     * @param astPlayer 死亡したプレイヤー
     * @param deathLocation 死亡地点
     * @return 新しく死亡状態へ遷移した場合は {@code true}
     */
    public boolean startDeath(@NotNull AstPlayer astPlayer, @NotNull Location deathLocation) {
        return startDeath(astPlayer, deathLocation, DEATH_DURATION_MILLIS, true, null);
    }

    /**
     * 復帰時間と復帰後処理を指定してプレイヤーを死亡状態にします。
     *
     * @param astPlayer 死亡したプレイヤー
     * @param deathLocation 死亡地点
     * @param durationMillis 死亡状態を維持するミリ秒
     * @param applyExperiencePenalty 現在レベル経験値の死亡ペナルティを適用する場合は {@code true}
     * @param recoveryAction 全回復後に実行する復帰処理。{@code null} の場合は通常スポーンへ戻す
     * @return 新しく死亡状態へ遷移した場合は {@code true}
     */
    public boolean startDeath(
        @NotNull AstPlayer astPlayer,
        @NotNull Location deathLocation,
        long durationMillis,
        boolean applyExperiencePenalty,
        @Nullable Runnable recoveryAction
    ) {
        Player player = astPlayer.getBukkit();
        UUID playerId = player.getUniqueId();
        if (deaths.containsKey(playerId)) {
            return false;
        }

        Location lockLocation = deathLocation.clone();
        long expiresAtMillis = System.currentTimeMillis() + Math.max(1_000L, durationMillis);
        DeathState state = new DeathState(playerId, lockLocation, expiresAtMillis, recoveryAction);
        deaths.put(playerId, state);
        deathStartedListener.accept(playerId);
        statusService.clearShieldRuntimeState(playerId);

        if (applyExperiencePenalty) {
            accountService.loseCurrentLevelExperiencePercentCached(astPlayer.getAccount(), 10, astPlayer.getUser().getUuid())
                .ifPresent(astPlayer::setAccount);
        }
        statusService.consumeHp(astPlayer, statusService.getStatus(astPlayer).getCurrentHp());
        removeFromMobCombat(playerId);
        attachOnlinePlayer(player, state);
        return true;
    }

    /**
     * プレイヤーが死亡中かを返します。
     *
     * @param playerId プレイヤー UUID
     * @return 死亡状態が記録されている場合は {@code true}
     */
    public boolean isDead(@NotNull UUID playerId) {
        return deaths.containsKey(playerId);
    }

    /**
     * オンラインの指定プレイヤーは死亡状態を直ちに解除して全リソースを回復します。
     * オフラインの場合は次回ログイン時の通常復帰へ切り替えます。
     * オンライン時は通常スポーンへの転送や登録済み復帰処理を実行しません。
     *
     * @param playerId 死亡状態を解除するプレイヤー UUID
     * @return 死亡状態を解除した場合は {@code true}
     */
    public boolean recoverNow(@NotNull UUID playerId) {
        DeathState state = deaths.get(playerId);
        if (state == null) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            state.deferDefaultRecovery();
            destroyVisuals(state);
            return true;
        }
        completeDeath(player, state, false);
        return true;
    }

    /**
     * 死亡中プレイヤーの移動固定地点を返します。
     *
     * @param playerId プレイヤー UUID
     * @return 固定地点。死亡中でなければ {@code null}
     */
    @Nullable
    public Location lockLocation(@NotNull UUID playerId) {
        DeathState state = deaths.get(playerId);
        return state == null ? null : state.deathLocation().clone();
    }

    /**
     * 死亡中プレイヤーが再ログインしたときの表示と位置を復元します。
     *
     * @param player 再ログインしたプレイヤー
     */
    public void handleJoin(@NotNull Player player) {
        hideDeadPlayersFrom(player);
        DeathState state = deaths.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            DeathState current = deaths.get(player.getUniqueId());
            if (current == null || !player.isOnline()) {
                return;
            }
            if (current.isExpired(System.currentTimeMillis())) {
                finishDeath(player, current);
                return;
            }
            attachOnlinePlayer(player, current);
        }, 2L);
    }

    /**
     * プレイヤー切断時に可視性と表示更新だけを切り離します。
     *
     * @param player 切断したプレイヤー
     */
    public void handleQuit(@NotNull Player player) {
        player.resetTitle();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (DeathState state : deaths.values()) {
            Player player = Bukkit.getPlayer(state.playerId());
            if (player == null || !player.isOnline()) {
                if (state.isExpired(now)) {
                    destroyVisuals(state);
                }
                continue;
            }
            if (state.isExpired(now)) {
                finishDeath(player, state);
                continue;
            }
            showCountdownTitle(player, state.remainingSeconds(now));
        }
    }

    private void attachOnlinePlayer(@NotNull Player player, @NotNull DeathState state) {
        if (player.getWorld() != state.deathLocation().getWorld()) {
            PlayerTeleportService.teleport(player, state.deathLocation());
        } else if (player.getLocation().distanceSquared(state.deathLocation()) > 0.01D) {
            PlayerTeleportService.teleport(player, state.deathLocation());
        }
        player.setInvulnerable(true);
        hideFromOtherPlayers(player);
        spawnVisuals(player, state);
        showCountdownTitle(player, state.remainingSeconds(System.currentTimeMillis()));
    }

    private void finishDeath(@NotNull Player player, @NotNull DeathState state) {
        completeDeath(player, state, true);
    }

    private void completeDeath(@NotNull Player player, @NotNull DeathState state, boolean runRecovery) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        if (!deaths.remove(player.getUniqueId(), state)) {
            return;
        }
        destroyVisuals(state);
        player.setInvulnerable(false);
        statusService.restoreAll(astPlayer, HealthRecoveryContext.self("死亡復帰"));
        showToOtherPlayers(player);
        player.resetTitle();
        if (!runRecovery) {
            return;
        }
        Runnable recoveryAction = state.recoveryAction();
        if (recoveryAction != null) {
            recoveryAction.run();
        } else {
            teleportToJoinSpawn(player);
        }
    }

    private void teleportToJoinSpawn(@NotNull Player player) {
        var worldData = worldService.getById(joinSpawnWorldId);
        if (worldData == null) {
            return;
        }
        worldService.teleportToSpawnAsync(player, worldData);
    }

    private void removeFromMobCombat(@NotNull UUID playerId) {
        for (var instance : mobService.getInstances()) {
            instance.threatTable().remove(playerId);
            if (playerId.equals(instance.lastAttackerUuid())) {
                instance.lastAttackerUuid(null);
            }
            if (playerId.equals(instance.targetId())) {
                instance.targetId(null);
            }
        }
    }

    private void hideFromOtherPlayers(@NotNull Player deadPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(deadPlayer.getUniqueId())) {
                viewer.hidePlayer(plugin, deadPlayer);
            }
        }
    }

    private void hideDeadPlayersFrom(@NotNull Player viewer) {
        for (UUID deadId : deaths.keySet()) {
            Player deadPlayer = Bukkit.getPlayer(deadId);
            if (deadPlayer != null && deadPlayer.isOnline() && !viewer.getUniqueId().equals(deadId)) {
                viewer.hidePlayer(plugin, deadPlayer);
            }
        }
    }

    private void showToOtherPlayers(@NotNull Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                viewer.showPlayer(plugin, player);
            }
        }
    }

    private void spawnVisuals(@NotNull Player player, @NotNull DeathState state) {
        if (state.headDisplay() != null && state.headDisplay().isValid()) {
            return;
        }
        World world = state.deathLocation().getWorld();
        if (world == null) {
            return;
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
            head.setItemMeta(skullMeta);
        }
        Location itemLocation = state.deathLocation().clone().add(0.0D, 0.65D, 0.0D);
        ItemDisplay itemDisplay = world.spawn(itemLocation, ItemDisplay.class, display -> {
            display.setItemStack(head);
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        });
        state.headDisplay(itemDisplay);
        state.textDisplay(displayTextService.create(
            DisplayAnchor.fixed(state.deathLocation().clone().add(0.0D, 1.35D, 0.0D)),
            DisplayTextOptions.defaults(PlayerMsgResource.format(PlayerMsgId.P_5082.getId(), player.getName()))
                .withShadowed(true)
                .withLineWidth(220)
                .withViewRange(48.0F)
        ));
    }

    private void destroyVisuals(@NotNull DeathState state) {
        Entity head = state.headDisplay();
        if (head != null && head.isValid()) {
            head.remove();
        }
        state.headDisplay(null);
        DisplayTextService.ManagedTextDisplay text = state.textDisplay();
        if (text != null) {
            text.destroy();
        }
        state.textDisplay(null);
    }

    private void showCountdownTitle(@NotNull Player player, long remainingSeconds) {
        player.showTitle(Title.title(
            PlayerMsgResource.getComponent(PlayerMsgId.P_5080.getId()),
            PlayerMsgResource.formatComponent(PlayerMsgId.P_5081.getId(), remainingSeconds),
            COUNTDOWN_TITLE_TIMES
        ));
    }

    private static final class DeathState {
        private final UUID playerId;
        private final Location deathLocation;
        private long expiresAtMillis;
        private @Nullable Runnable recoveryAction;
        private @Nullable ItemDisplay headDisplay;
        private @Nullable DisplayTextService.ManagedTextDisplay textDisplay;

        private DeathState(
            @NotNull UUID playerId,
            @NotNull Location deathLocation,
            long expiresAtMillis,
            @Nullable Runnable recoveryAction
        ) {
            this.playerId = playerId;
            this.deathLocation = deathLocation;
            this.expiresAtMillis = expiresAtMillis;
            this.recoveryAction = recoveryAction;
        }

        private UUID playerId() {
            return playerId;
        }

        private Location deathLocation() {
            return deathLocation;
        }

        private boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }

        private long remainingSeconds(long nowMillis) {
            return Math.max(0L, (expiresAtMillis - nowMillis + 999L) / 1000L);
        }

        private @Nullable Runnable recoveryAction() {
            return recoveryAction;
        }

        private void deferDefaultRecovery() {
            expiresAtMillis = 0L;
            recoveryAction = null;
        }

        private @Nullable ItemDisplay headDisplay() {
            return headDisplay;
        }

        private void headDisplay(@Nullable ItemDisplay headDisplay) {
            this.headDisplay = headDisplay;
        }

        private @Nullable DisplayTextService.ManagedTextDisplay textDisplay() {
            return textDisplay;
        }

        private void textDisplay(@Nullable DisplayTextService.ManagedTextDisplay textDisplay) {
            this.textDisplay = textDisplay;
        }
    }
}
