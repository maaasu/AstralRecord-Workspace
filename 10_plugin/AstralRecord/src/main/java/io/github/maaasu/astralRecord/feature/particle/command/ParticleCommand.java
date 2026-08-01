package io.github.maaasu.astralRecord.feature.particle.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 管理者が視線の先へ任意のパーティクルを表示するコマンドです。
 */
public final class ParticleCommand extends AstCommand {

    private static final double DISPLAY_DISTANCE = 3.0D;
    private static final int MAX_COUNT = 256;
    private static final int MAX_DURATION_SECONDS = 300;
    private static final long DISPLAY_INTERVAL_TICKS = 10L;
    private static final double MIN_DISTRIBUTION_RADIUS = 0.25D;
    private static final double MAX_DISTRIBUTION_RADIUS = 2.0D;
    private static final double RADIUS_PER_SQRT_COUNT = 0.08D;

    private final Plugin plugin;
    private final Supplier<ParticleDisplayService> particleDisplayServiceSupplier;

    /**
     * パーティクル管理コマンドを初期化します。
     *
     * @param plugin scheduler の所有プラグイン
     * @param particleDisplayServiceSupplier 共通パーティクル表示サービスの遅延取得元
     */
    public ParticleCommand(
            @NotNull Plugin plugin,
            @NotNull Supplier<ParticleDisplayService> particleDisplayServiceSupplier
    ) {
        super(
                "particle",
                "パーティクルを表示します。",
                "/particle <id> [amount] [seconds]",
                true,
                UserPermission.ADMIN.getValue()
        );
        this.plugin = plugin;
        this.particleDisplayServiceSupplier = particleDisplayServiceSupplier;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 1 || args.length > 3) {
            sendUsage(player.getBukkit());
            return;
        }

        SharedParticleDefinition definition = SharedParticleDefinitions.resolveDefinition(args[0]);
        if (definition == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_6901.getId(), args[0]));
            return;
        }

        Integer parsedAmount = parseAmount(player, args);
        int amount = parsedAmount == null ? 1 : parsedAmount;
        if (parsedAmount == null && args.length >= 2) {
            return;
        }

        Integer durationSeconds = args.length >= 3 ? parseDuration(player, args) : 0;
        if (durationSeconds == null) {
            return;
        }

        ParticleDisplayService particleDisplayService = particleDisplayServiceSupplier.get();
        if (particleDisplayService == null) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_6905.getId()));
            return;
        }

        Location center = player.getBukkit().getEyeLocation();
        center.add(center.getDirection().normalize().multiply(DISPLAY_DISTANCE));
        display(particleDisplayService, center, definition, amount);

        if (durationSeconds > 0) {
            scheduleRepeatedDisplay(particleDisplayService, center, definition, amount, durationSeconds);
        }

        sendSuccess(
                player.getBukkit(),
                PlayerMsgResource.format(
                        PlayerMsgId.P_6900.getId(),
                        definition.id(),
                        amount,
                        durationSeconds
                )
        );
    }

    private Integer parseAmount(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            return 1;
        }
        try {
            int amount = Integer.parseInt(args[1]);
            if (amount < 1 || amount > MAX_COUNT) {
                sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_6902.getId(), MAX_COUNT));
                return null;
            }
            return amount;
        } catch (NumberFormatException exception) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_6903.getId(), "量"));
            return null;
        }
    }

    private Integer parseDuration(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 3) {
            return null;
        }
        try {
            int duration = Integer.parseInt(args[2]);
            if (duration < 1 || duration > MAX_DURATION_SECONDS) {
                sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_6904.getId(), MAX_DURATION_SECONDS));
                return null;
            }
            return duration;
        } catch (NumberFormatException exception) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_6903.getId(), "時間"));
            return null;
        }
    }

    private void scheduleRepeatedDisplay(
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull Location center,
            @NotNull SharedParticleDefinition definition,
            int amount,
            int durationSeconds
    ) {
        long durationTicks = durationSeconds * 20L;
        new BukkitRunnable() {
            private long elapsedTicks = DISPLAY_INTERVAL_TICKS;

            @Override
            public void run() {
                if (elapsedTicks >= durationTicks) {
                    cancel();
                    return;
                }
                display(particleDisplayService, center, definition, amount);
                elapsedTicks += DISPLAY_INTERVAL_TICKS;
            }
        }.runTaskTimer(plugin, DISPLAY_INTERVAL_TICKS, DISPLAY_INTERVAL_TICKS);
    }

    private void display(
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull Location center,
            @NotNull SharedParticleDefinition definition,
            int amount
    ) {
        List<Location> locations = createLocations(center, amount);
        particleDisplayService.spawnForNearbyViewers(
                center,
                locations,
                definition.withCount(1).withOffsets(0.0D, 0.0D, 0.0D)
        );
    }

    private @NotNull List<Location> createLocations(@NotNull Location center, int amount) {
        if (amount <= 1) {
            return List.of(center.clone());
        }

        double radius = Math.min(
                MAX_DISTRIBUTION_RADIUS,
                MIN_DISTRIBUTION_RADIUS + Math.sqrt(amount) * RADIUS_PER_SQRT_COUNT
        );
        List<Location> locations = new ArrayList<>(amount);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < amount; index++) {
            double x;
            double y;
            double z;
            do {
                x = random.nextDouble(-1.0D, 1.0D);
                y = random.nextDouble(-1.0D, 1.0D);
                z = random.nextDouble(-1.0D, 1.0D);
            } while (x * x + y * y + z * z > 1.0D);
            locations.add(center.clone().add(x * radius, y * radius, z * radius));
        }
        return locations;
    }
}
