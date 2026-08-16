package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 拠点ワールド向けのレコード音楽を管理します。
 */
public final class BaseMusicService {
    private static final SoundCategory MUSIC_CATEGORY = SoundCategory.MUSIC;
    private static final float MUSIC_VOLUME = 0.35F;
    private static final float MUSIC_PITCH = 1.0F;

    private final AstralRecord plugin;
    private final WorldService worldService;
    private final PlayerSettingService playerSettingService;
    private final Deque<BaseMusicTrack> remainingTracks = new ArrayDeque<>();
    private BaseMusicTrack currentTrack;
    private BukkitTask nextTrackTask;

    /**
     * 拠点音楽サービスを初期化します。
     *
     * @param plugin プラグイン
     * @param worldService ワールドサービス
     * @param playerSettingService プレイヤー設定サービス
     */
    public BaseMusicService(
        @NotNull AstralRecord plugin,
        @NotNull WorldService worldService,
        @NotNull PlayerSettingService playerSettingService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.playerSettingService = playerSettingService;
    }

    /**
     * 指定プレイヤーの現在ワールドと音楽設定に合わせて再生状態を同期します。
     *
     * @param player 同期対象プレイヤー
     */
    public void refreshPlayer(@NotNull Player player) {
        if (!player.isOnline()) {
            stopTrack(player);
            stopPlaybackIfNoListeners();
            return;
        }
        if (worldService.resolveWorldType(player.getWorld()) != WorldType.BASE) {
            stopTrack(player);
            stopPlaybackIfNoListeners();
            return;
        }
        if (!playerSettingService.isBaseMusicReady(player.getUniqueId())) {
            stopTrack(player);
            stopPlaybackIfNoListeners();
            return;
        }
        if (!playerSettingService.isBaseMusicEnabled(player.getUniqueId())) {
            stopTrack(player);
            stopPlaybackIfNoListeners();
            return;
        }

        if (currentTrack == null) {
            playNextTrack();
            return;
        }

        playTrack(player, currentTrack);
        scheduleNextTrackIfNeeded();
    }

    /**
     * ワールド移動後のプレイヤーへ音楽を同期します。
     *
     * @param player ワールドを移動したプレイヤー
     */
    public void handleWorldChange(@NotNull Player player) {
        stopTrack(player);
        refreshPlayer(player);
    }

    /**
     * ログアウトしたプレイヤーの音楽を停止します。
     *
     * @param player ログアウトしたプレイヤー
     */
    public void handlePlayerQuit(@NotNull Player player) {
        stopTrack(player);
        plugin.getServer().getScheduler().runTask(plugin, this::stopPlaybackIfNoListeners);
    }

    /**
     * プラグイン停止時に音楽と予約 task を停止します。
     */
    public void stop() {
        cancelNextTrackTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            stopTrack(player);
        }
        currentTrack = null;
        remainingTracks.clear();
    }

    private void playNextTrack() {
        nextTrackTask = null;
        if (!hasEligibleListeners()) {
            resetPlaybackState();
            return;
        }

        currentTrack = selectNextTrack();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isEligibleListener(player)) {
                playTrack(player, currentTrack);
            }
        }
        scheduleNextTrackIfNeeded();
    }

    private void scheduleNextTrackIfNeeded() {
        if (nextTrackTask != null || currentTrack == null || !hasEligibleListeners()) {
            return;
        }
        nextTrackTask = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            this::playNextTrack,
            currentTrack.durationTicks()
        );
    }

    private @NotNull BaseMusicTrack selectNextTrack() {
        if (remainingTracks.isEmpty()) {
            refillTracks();
        }
        return remainingTracks.removeFirst();
    }

    private void refillTracks() {
        List<BaseMusicTrack> shuffled = new ArrayList<>(List.of(BaseMusicTrack.values()));
        Collections.shuffle(shuffled);
        if (currentTrack != null && shuffled.size() > 1 && shuffled.get(0) == currentTrack) {
            Collections.swap(shuffled, 0, 1);
        }
        remainingTracks.addAll(shuffled);
    }

    private void playTrack(@NotNull Player player, @NotNull BaseMusicTrack track) {
        stopTrack(player);
        player.playSound((Location) null, track.sound(), MUSIC_CATEGORY, MUSIC_VOLUME, MUSIC_PITCH);
    }

    private void stopTrack(@NotNull Player player) {
        for (BaseMusicTrack track : BaseMusicTrack.values()) {
            player.stopSound(track.sound(), MUSIC_CATEGORY);
        }
    }

    private boolean hasEligibleListeners() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isEligibleListener(player)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEligibleListener(@NotNull Player player) {
        return player.isOnline()
            && worldService.resolveWorldType(player.getWorld()) == WorldType.BASE
            && playerSettingService.isBaseMusicReady(player.getUniqueId())
            && playerSettingService.isBaseMusicEnabled(player.getUniqueId());
    }

    private void stopPlaybackIfNoListeners() {
        if (!hasEligibleListeners()) {
            resetPlaybackState();
        }
    }

    private void resetPlaybackState() {
        cancelNextTrackTask();
        currentTrack = null;
        remainingTracks.clear();
    }

    private void cancelNextTrackTask() {
        if (nextTrackTask != null) {
            nextTrackTask.cancel();
            nextTrackTask = null;
        }
    }

    private enum BaseMusicTrack {
        OTHERSIDE(Sound.MUSIC_DISC_OTHERSIDE, 195L),
        WAIT(Sound.MUSIC_DISC_WAIT, 238L),
        MELLOHI(Sound.MUSIC_DISC_MELLOHI, 96L),
        RELIC(Sound.MUSIC_DISC_RELIC, 218L),
        CHIRP(Sound.MUSIC_DISC_CHIRP, 185L);

        private final Sound sound;
        private final long durationTicks;

        BaseMusicTrack(@NotNull Sound sound, long durationSeconds) {
            this.sound = sound;
            this.durationTicks = durationSeconds * 20L;
        }

        private @NotNull Sound sound() {
            return sound;
        }

        private long durationTicks() {
            return durationTicks;
        }
    }
}
