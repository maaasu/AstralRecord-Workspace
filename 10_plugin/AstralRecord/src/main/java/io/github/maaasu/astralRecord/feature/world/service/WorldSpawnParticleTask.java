package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 各ワールドの既定スポーン地点に END_ROD の周回パーティクルとスニーク導線 TextDisplay を表示するタスクです。
 */
public class WorldSpawnParticleTask {

    private static final long PERIOD_TICKS = 5L;
    private static final int RING_POINTS = 6;
    private static final double PROMPT_Y_OFFSET = 2.25D;
    private static final double VIEWER_DISTANCE_SQUARED = 64.0D * 64.0D;
    private static final String BASE_WORLD_PROMPT = "&eスニーク&fでオーバーワールドへ移動";
    private static final String OVERWORLD_PROMPT = "&eスニーク&fで拠点に帰還";

    private final AstralRecord plugin;
    private final WorldService worldService;
    private final ParticleDisplayService particleDisplayService;
    private final DisplayTextService displayTextService;
    private final Map<String, SpawnPromptDisplay> promptDisplays = new HashMap<>();
    private BukkitTask task;
    private long frame;

    /**
     * スポーン地点演出タスクを生成します。
     *
     * @param plugin scheduler を起動するプラグイン
     * @param worldService ワールド情報の解決に使うサービス
     * @param particleDisplayService パーティクル表示サービス
     * @param displayTextService TextDisplay 表示サービス
     */
    public WorldSpawnParticleTask(
        @NotNull AstralRecord plugin,
        @NotNull WorldService worldService,
        @NotNull ParticleDisplayService particleDisplayService,
        @NotNull DisplayTextService displayTextService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.particleDisplayService = particleDisplayService;
        this.displayTextService = displayTextService;
    }

    /**
     * スポーン地点演出タスクを開始します。
     */
    public void start() {
        if (task != null) {
            return;
        }
        frame = 0L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, PERIOD_TICKS);
    }

    /**
     * スポーン地点演出タスクを停止します。
     */
    public void stop() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
        clearPromptDisplays();
    }

    private void tick() {
        double baseAngle = frame * 0.22D;
        Set<String> activePromptIds = new HashSet<>();
        for (var worldData : worldService.getAll()) {
            if (!worldData.showSpawnParticle()) {
                continue;
            }
            Location spawn = worldService.resolveSpawnLocation(worldData);
            if (spawn == null) {
                continue;
            }
            if (renderSpawnAnimation(spawn, baseAngle) && updateSpawnPrompt(worldData, spawn)) {
                activePromptIds.add(worldData.id());
            }
        }
        removeInactivePrompts(activePromptIds);
        frame++;
    }

    private boolean renderSpawnAnimation(@NotNull Location spawn, double baseAngle) {
        World world = spawn.getWorld();
        if (world == null || !hasNearbyViewer(spawn, world)) {
            return false;
        }

        double pulse = 1.5D + (Math.sin(baseAngle * 0.65D) * 0.12D);
        List<Location> ringLocations = new ArrayList<>(RING_POINTS);
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = baseAngle + ((Math.PI * 2.0D * i) / RING_POINTS);
            double x = Math.cos(angle) * pulse;
            double z = Math.sin(angle) * pulse;
            double y = 1.15D + (Math.sin((baseAngle * 1.4D) + (i * 0.45D)) * 0.25D);

            ringLocations.add(spawn.clone().add(x, y, z));
        }
        particleDisplayService.spawnForNearbyViewers(
            spawn,
            ringLocations,
            SharedParticleDefinitions.WORLD_SPAWN_RING_END_ROD
        );
        return true;
    }

    private boolean hasNearbyViewer(@NotNull Location center, @NotNull World world) {
        for (var player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= VIEWER_DISTANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    private boolean updateSpawnPrompt(@NotNull WorldMasterData worldData, @NotNull Location spawn) {
        String text = promptText(worldData);
        if (text == null) {
            return false;
        }

        Location promptLocation = spawn.clone().add(0.0D, PROMPT_Y_OFFSET, 0.0D);
        SpawnPromptDisplay current = promptDisplays.get(worldData.id());
        try {
            if (current == null) {
                promptDisplays.put(worldData.id(), createPromptDisplay(promptLocation, text));
            } else {
                current.display().setAnchor(DisplayAnchor.fixed(promptLocation));
                if (!current.text().equals(text)) {
                    current.display().setText(text);
                    promptDisplays.put(worldData.id(), new SpawnPromptDisplay(current.display(), text));
                }
            }
            return true;
        } catch (IllegalStateException ignored) {
            promptDisplays.put(worldData.id(), createPromptDisplay(promptLocation, text));
            return true;
        }
    }

    private @Nullable String promptText(@NotNull WorldMasterData worldData) {
        if (worldData.worldType() == WorldType.BASE) {
            return BASE_WORLD_PROMPT;
        }
        if (worldData.worldType() == WorldType.OVERWORLD) {
            return OVERWORLD_PROMPT;
        }
        return null;
    }

    private @NotNull SpawnPromptDisplay createPromptDisplay(@NotNull Location location, @NotNull String text) {
        return new SpawnPromptDisplay(
            displayTextService.create(
                DisplayAnchor.fixed(location),
                DisplayTextOptions.defaults(text)
                    .withLineWidth(260)
                    .withViewRange(48.0F)
                    .withShadowed(true)
            ),
            text
        );
    }

    private void removeInactivePrompts(@NotNull Set<String> activePromptIds) {
        for (String id : List.copyOf(promptDisplays.keySet())) {
            if (!activePromptIds.contains(id)) {
                removePrompt(id);
            }
        }
    }

    private void clearPromptDisplays() {
        for (String id : List.copyOf(promptDisplays.keySet())) {
            removePrompt(id);
        }
    }

    private void removePrompt(@NotNull String id) {
        SpawnPromptDisplay display = promptDisplays.remove(id);
        if (display == null) {
            return;
        }
        try {
            display.display().destroy();
        } catch (IllegalStateException ignored) {
            // DisplayTextService 側ですでに破棄済みの場合は同期だけ済ませます。
        }
    }

    private record SpawnPromptDisplay(
        @NotNull DisplayTextService.ManagedTextDisplay display,
        @NotNull String text
    ) {
    }
}
