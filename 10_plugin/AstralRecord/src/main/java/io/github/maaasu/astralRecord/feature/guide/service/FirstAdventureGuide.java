package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

/** 初回チュートリアルの現在の段階を観測し、本人向けに一時表示します。 */
final class FirstAdventureGuide {
    static final String BUY_WEAPON = "buy_starter_weapon";
    static final String HUNT_GRASSBOAR = "defeat_grassboar";

    private static final String OPEN_MENU = "open_menu";
    private static final String GREENFIELDS = "midgard_greenfields";
    private static final String GRASSBOAR_SPAWNER = "midgard_grassboar_lv1_spawner";
    private static final String GRASSBOAR = "midgard_grassboar";
    private static final String GREENFIELDS_NAME = "ミズガルズの緑野";
    private static final long POLL_TICKS = 10L;
    private static final long HINT_TICKS = 80L;
    private static final double SPAWN_CIRCLE_RADIUS = 2.0D;
    private static final double HUNT_RADIUS_SQUARED = 18.0D * 18.0D;
    private static final int MAX_MARKED_MOBS = 4;
    private static final Title.Times TITLE_TIMES = Title.Times.times(
        Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(350)
    );

    private final AstralRecord plugin;
    private final ParticleDisplayService particles;
    private final Function<AstPlayer, String> navigateMerchant;
    private final Consumer<UUID> clearMerchantHighlight;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private @Nullable BukkitTask timer;
    private @Nullable NamespacedKey seenAccountKey;
    private long tick;
    private long placementTick = Long.MIN_VALUE;
    private Collection<MobSpawnerLocation> placements = java.util.List.of();

    FirstAdventureGuide(
        AstralRecord plugin,
        ParticleDisplayService particles, Function<AstPlayer, String> navigateMerchant,
        Consumer<UUID> clearMerchantHighlight
    ) {
        this.plugin = plugin;
        this.particles = particles;
        this.navigateMerchant = navigateMerchant;
        this.clearMerchantHighlight = clearMerchantHighlight;
    }

    void start() {
        if (timer == null) {
            timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 1L, POLL_TICKS);
        }
    }

    void stop() {
        try {
            if (timer != null) {
                timer.cancel();
            }
        } finally {
            timer = null;
            placements = java.util.List.of();
            placementTick = Long.MIN_VALUE;
            for (Session session : sessions.values()) {
                cleanUp(session);
            }
            sessions.clear();
        }
    }

    void complete(Player player) {
        UUID playerId = player.getUniqueId();
        discard(playerId);
        clearMerchantHighlight.accept(playerId);
        AstPlayer ast = AstPlayerCache.get(player);
        if (player.isOnline() && ast != null && plugin.getGuideService().isGuideDisplayEnabled(ast)) {
            showTitle(player, "最初の冒険 完了", "グラスボアを1体倒した！");
        }
    }

    boolean showHunt(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return false;
        }
        Location from = player.getLocation();
        HuntRoute route = resolveHunt(from);
        player.sendMessage(Component.text(route.hint()));
        renderRoute(player, from, route);
        return true;
    }

    private void poll() {
        tick += POLL_TICKS;
        for (AstPlayer ast : AstPlayerCache.getAll()) {
            try {
                observe(ast);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "初回チュートリアルの表示を更新できませんでした。", failure);
            }
        }

        // キャッシュから消えた参加者と、ロード待ち・死亡・完了に移った参加者を回収します。
        var iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (session.lastObservedTick != tick) {
                iterator.remove();
                cleanUp(session);
            }
        }
    }

    void refreshDisplay(AstPlayer ast) {
        if (!plugin.getGuideService().isGuideDisplayEnabled(ast)) {
            discard(ast.getBukkit().getUniqueId());
        } else {
            observe(ast);
        }
    }

    private void observe(AstPlayer ast) {
        if (!AccountModeGuard.isGameplayPlayer(ast)) {
            return;
        }

        Player player = ast.getBukkit();
        if (!player.isOnline() || player.isDead()) {
            return;
        }

        UUID accountId = ast.getAccount().getUuid();
        String step = plugin.getGuideService().tutorialStep(accountId);
        if (step == null || !plugin.getGuideService().isGuideDisplayEnabled(ast)) {
            return;
        }

        Location from = player.getLocation();
        if (from.getWorld() == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        UUID worldId = from.getWorld().getUID();
        Session session = sessions.get(playerId);
        boolean sameStep = session != null && session.ast == ast && session.player == player
            && session.accountId.equals(accountId) && session.step.equals(step);
        boolean hud = plugin.getGuideService().usesJavaGuideHud(ast);
        boolean changed = !sameStep || !session.worldId.equals(worldId) || session.hud != hud;
        if (changed) {
            discard(playerId);
            session = new Session(ast, player, accountId, worldId, step);
            session.hud = hud;
            sessions.put(playerId, session);
        }

        if (hud || tick >= session.hintExpiresAt) {
            hideHint(session);
        }
        if (HUNT_GRASSBOAR.equals(step)) {
            updateHunt(session, from, changed);
        } else if ("open_initial_bundle".equals(step)) {
            boolean inHand = "50a00001".equals(ItemStackFactory.getAstralItemId(player.getInventory().getItemInMainHand()));
            if (changed || inHand != session.bundleInHand) {
                String use = ast.isBedrock() ? "「使う」操作" : "右クリック";
                showHint(session, inHand ? "画面を閉じ、" + use + "でバンドルを開けよう"
                    : "持ち物でバンドルを選び、ホットバーに持とう");
                session.bundleInHand = inHand;
            }
        } else if (changed) {
            if (OPEN_MENU.equals(step)) {
                showIntroductionOnce(player, accountId);
            }
            String hint = BUY_WEAPON.equals(step) ? navigateMerchant.apply(ast) : instruction(step);
            if (hint != null) {
                showHint(session, hint);
            }
        }
        session.lastObservedTick = tick;
    }

    private static @Nullable String instruction(String step) {
        return switch (step) {
            case OPEN_MENU -> "案内具を使うか /menu でメニューを開こう";
            case "receive_welcome_mail" -> "メールで『初ログイン記念の贈り物』を受け取ろう";
            case "equip_starter_weapon" -> "案内具で『装備』を開き、下のバッグの簡素な武器を選ぼう";
            default -> null;
        };
    }

    private void showIntroductionOnce(Player player, UUID accountId) {
        if (seenAccountKey == null) {
            seenAccountKey = new NamespacedKey(plugin, "first_adventure_seen_account");
        }
        var data = player.getPersistentDataContainer();
        String current = accountId.toString();
        String seen = data.get(seenAccountKey, PersistentDataType.STRING);
        if (seen != null && Arrays.asList(seen.split(",")).contains(current)) {
            return;
        }

        showTitle(player, "最初の冒険", "グラスボアを1体倒そう");
        data.set(seenAccountKey, PersistentDataType.STRING,
            seen == null || seen.isEmpty() ? current : seen + "," + current);
    }

    private static void showTitle(Player player, String title, String subtitle) {
        player.showTitle(Title.title(Component.text(title), Component.text(subtitle), TITLE_TIMES));
    }

    private void showHint(Session session, String hint) {
        session.hudInstruction = hint;
        if (plugin.getGuideService().usesJavaGuideHud(session.ast)) return;
        hideHint(session);
        session.hint = BossBar.bossBar(Component.text(hint), 1.0F, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        session.hintExpiresAt = tick + HINT_TICKS;
        session.player.showBossBar(session.hint);
    }

    private static void hideHint(Session session) {
        BossBar hint = session.hint;
        if (hint != null) {
            session.player.hideBossBar(hint);
            session.hint = null;
        }
    }

    private void discard(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session != null) {
            cleanUp(session);
        }
    }

    private void cleanUp(Session session) {
        try {
            try {
                hideHint(session);
            } finally {
                clearMerchantHighlight.accept(session.player.getUniqueId());
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "初回チュートリアルの表示を回収できませんでした。", failure);
        }
    }

    private void updateHunt(Session session, Location from, boolean changed) {
        HuntRoute route = resolveHunt(from);
        Location target = route.target();
        boolean arrived = route.hunting() && target != null && distanceSquared(from, target) <= HUNT_RADIUS_SQUARED;
        String hint = arrived ? "狩場に到着。目印のグラスボアを1体倒そう" : route.hint();
        session.hudInstruction = hint;
        if (changed || arrived != session.atHunt) {
            showHint(session, hint);
        }
        session.atHunt = arrived;
        renderRoute(session.player, from, route);
    }

    private HuntRoute resolveHunt(Location from) {
        World world = from.getWorld();
        var worldService = plugin.getWorldService();
        var here = worldService.findByBukkitWorld(world);
        var greenfields = worldService.getById(GREENFIELDS);
        String destinationName = greenfields == null ? GREENFIELDS_NAME
            : ColorCodeUtil.toPlainText(greenfields.displayName(), GREENFIELDS_NAME);

        if (here != null && here.worldType() == WorldType.BASE) {
            if (greenfields == null || greenfields.overworldTeleportGui() == null
                || !greenfields.overworldTeleportGui().hasValidSlot()) {
                return new HuntRoute(null, false, "草原への転送先を確認できません");
            }
            Location spawn = worldService.resolveSpawnLocation(here);
            if (spawn == null || !world.equals(spawn.getWorld())) {
                return new HuntRoute(null, false, "拠点のスポーン円を確認できません。転送先は「" + destinationName + "」です");
            }
            return new HuntRoute(spawn, false,
                "スポーン円 " + coordinates(spawn) + " でスニークして「" + destinationName + "」へ");
        }

        if (here != null && GREENFIELDS.equals(here.id())) {
            // 同じ更新周期に複数人を案内するとき、配置のコピーは一度で済ませます。
            if (placementTick != tick || timer == null) {
                placements = plugin.getMobSpawnerService().getLocations();
                placementTick = tick;
            }
            Location target = nearestSpawner(from, placements);
            if (target == null) {
                return new HuntRoute(null, false, destinationName + "のグラスボア狩場の配置を確認できません");
            }
            return new HuntRoute(target, true,
                destinationName + " " + coordinates(target) + " でグラスボアを1体倒そう");
        }

        return new HuntRoute(null, false, destinationName + "へ向かうには、メニューの『帰還』で拠点に戻ろう");
    }

    static @Nullable Location nearestSpawner(Location from, Iterable<MobSpawnerLocation> placements) {
        World world = from.getWorld();
        if (world == null) {
            return null;
        }

        Location nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        String worldName = world.getName();
        for (MobSpawnerLocation placement : placements) {
            if (!GRASSBOAR_SPAWNER.equals(placement.spawnerId()) || !worldName.equals(placement.worldName())) {
                continue;
            }
            // 現在のワールドと一致する配置だけを座標に変換します。
            Location target = new Location(world, placement.x() + 0.5D, placement.y(), placement.z() + 0.5D);
            double distance = from.distanceSquared(target);
            if (distance < nearestDistance) {
                nearest = target;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static String coordinates(Location location) {
        return "(" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
    }

    private void renderRoute(Player player, Location from, HuntRoute route) {
        Location target = route.target();
        if (target == null || !player.getWorld().equals(target.getWorld())) {
            return;
        }
        double distance = distanceSquared(from, target);
        if (!Double.isFinite(distance)) {
            return;
        }

        double markerRange = route.hunting() ? HUNT_RADIUS_SQUARED : 12.0D * 12.0D;
        if (distance > markerRange) {
            showDirection(player, from, target);
            return;
        }
        if (route.hunting()) {
            particle(player, target.clone().add(0, 0.6D, 0));
            particle(player, target.clone().add(0, 1.5D, 0));
            markGrassboars(player, from, target);
        } else {
            showSpawnCircle(player, target);
        }
    }

    private void showDirection(Player player, Location from, Location target) {
        Vector direction = target.toVector().subtract(from.toVector()).normalize();
        for (int i = 1; i <= 3; i++) {
            Vector offset = direction.clone().multiply(i * 0.65D);
            particle(player, from.clone().add(0, 0.9D, 0).add(offset));
        }
    }

    private void showSpawnCircle(Player player, Location spawn) {
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0D;
            particle(player, spawn.clone().add(
                SPAWN_CIRCLE_RADIUS * Math.cos(angle), 0.25D, SPAWN_CIRCLE_RADIUS * Math.sin(angle)
            ));
        }
    }

    private void markGrassboars(Player player, Location from, Location target) {
        int marked = 0;
        for (MobInstance instance : plugin.getMobService().getInstances()) {
            if (!GRASSBOAR.equals(instance.template().id())
                || instance.template().level() != 1
                || instance.currentHealth() <= 0
                || distanceSquared(target, instance.spawnLocation()) > HUNT_RADIUS_SQUARED) {
                continue;
            }
            Location current = instance.currentLocation();
            if (distanceSquared(target, current) > HUNT_RADIUS_SQUARED
                || distanceSquared(from, current) > HUNT_RADIUS_SQUARED) {
                continue;
            }
            particle(player, current.clone().add(0, 1.4D, 0));
            if (++marked == MAX_MARKED_MOBS) {
                break;
            }
        }
    }

    private static double distanceSquared(Location from, Location to) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return Double.POSITIVE_INFINITY;
        }
        return from.distanceSquared(to);
    }

    private void particle(Player player, Location at) {
        particles.spawnForViewer(player, at, SharedParticleDefinitions.TUTORIAL_WAYPOINT);
    }

    private record HuntRoute(@Nullable Location target, boolean hunting, String hint) {
    }

    @Nullable String hudInstruction(AstPlayer player, String step) {
        Session session = sessions.get(player.getBukkit().getUniqueId());
        if (session == null || session.ast != player || !session.accountId.equals(player.getAccount().getUuid())
            || !session.worldId.equals(player.getBukkit().getWorld().getUID()) || !session.step.equals(step)) return null;
        return switch (step) {
            case OPEN_MENU -> "案内具か /menu を使う";
            case "receive_welcome_mail" -> "メールで贈り物を受け取る";
            case "open_initial_bundle" -> session.bundleInHand ? "画面を閉じて右クリックで開封" : "バンドルをホットバーへ";
            case "equip_starter_weapon" -> "装備画面でバッグの武器を選ぶ";
            default -> session.hudInstruction;
        };
    }

    private static final class Session {
        private final AstPlayer ast;
        private final Player player;
        private final UUID accountId;
        private final UUID worldId;
        private final String step;

        private @Nullable BossBar hint;
        private @Nullable String hudInstruction;
        private long hintExpiresAt;
        private long lastObservedTick;
        private boolean atHunt;
        private boolean hud;
        private boolean bundleInHand;

        private Session(AstPlayer ast, Player player, UUID accountId, UUID worldId, String step) {
            this.ast = ast;
            this.player = player;
            this.accountId = accountId;
            this.worldId = worldId;
            this.step = step;
        }
    }
}
