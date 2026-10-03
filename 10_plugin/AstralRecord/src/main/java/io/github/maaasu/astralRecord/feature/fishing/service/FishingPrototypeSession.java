package io.github.maaasu.astralRecord.feature.fishing.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.fishing.message.FishingMsgId;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingEntityProtection;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Cod;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Player;
import org.bukkit.entity.Salmon;
import org.bukkit.entity.TropicalFish;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * デバッグ釣り竿の一回分の魚探索、リール操作、陸上での捕獲を管理します。
 * Bukkit API と実魚の移動は親キャストタスクからメインスレッドで更新します。
 */
public final class FishingPrototypeSession {
    private static final double SEARCH_RADIUS = 10.0D;
    private static final int MAX_SEARCH_BLOCKS = 5_000;
    private static final int MIN_FISH = 4;
    private static final int MAX_FISH = 6;
    private static final double MIN_SPAWN_DISTANCE_SQUARED = 5.0D;
    private static final double MIN_FISH_SEPARATION_SQUARED = 3.0D;
    private static final double FISH_SPEED = 0.13D;
    private static final double FLEE_SPEED = 0.26D;
    private static final double FISH_FORCE_PER_TICK = 0.008D;
    private static final double REEL_MULTIPLIER = 0.30D;
    private static final double SLACK_DISTANCE = 0.65D;
    private static final int FISH_MAX_HP = 4;
    private static final int ATTACK_TICKS = 40;
    private static final int TURN_TICKS = 80;
    private static final int LANDING_TICKS = 14;
    private static final int HANGING_TICKS = 100;
    private static final int MAX_ESCAPE_TICKS = 120;
    private static final int MAX_WAITING_TICKS = 1_200;
    private static final int REEL_COOLDOWN_TICKS = 6;
    private static final int[] DELTA_X = {1, -1, 0, 0, 0, 0};
    private static final int[] DELTA_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] DELTA_Z = {0, 0, 0, 0, 1, -1};

    private final ParticleDisplayService particles;
    private final AstPlayer owner;
    private final Player player;
    private final World world;
    private final Location submergedHook;
    private final double maxLineLength;
    private final double rodAttack;
    private final List<Swimmer> fish = new ArrayList<>();
    private final BossBar bar;

    private Phase phase = Phase.WAITING;
    private Swimmer selected;
    private Location hook;
    private Location landingStart;
    private Location escapeStart;
    private Location escapeTarget;
    private Location previousRodTip;
    private int ticks;
    private int phaseTicks;
    private int lastReelTick = Integer.MIN_VALUE / 2;
    private int hp = FISH_MAX_HP;
    private double distance;

    /**
     * 針と連続する読み込み済み水域へ4～6匹の魚を生成します。
     *
     * @param plugin 実行中のプラグイン。呼び出しはメインスレッドで行うこと
     * @param particles 共通粒子表示サービス
     * @param owner 釣りを開始したプレイヤー
     * @param submergedHook 水中にある仮想針の位置
     * @param maxLineLength 竿から許される糸の最大距離
     * @param rodAttack キャスト時点の竿の攻撃力
     */
    public FishingPrototypeSession(@NotNull AstralRecord plugin, @NotNull ParticleDisplayService particles,
                                   @NotNull AstPlayer owner, @NotNull Location submergedHook,
                                   double maxLineLength, double rodAttack) {
        this.particles = particles;
        this.owner = owner;
        this.player = owner.getBukkit();
        this.world = submergedHook.getWorld();
        this.submergedHook = submergedHook.clone();
        this.hook = submergedHook.clone();
        this.maxLineLength = maxLineLength;
        this.rodAttack = Double.isFinite(rodAttack) && rodAttack > 0.0D ? rodAttack : 10.0D;
        this.bar = plugin.getServer().createBossBar(FishingFeedback.text(FishingMsgId.BATTLE),
            BarColor.BLUE, BarStyle.SOLID);
        this.bar.addPlayer(player);
        this.bar.setProgress(0.01D);
        this.bar.setVisible(false);
        try {
            if (world == null || !Double.isFinite(maxLineLength) || maxLineLength <= 0.0D || !spawnFish()) {
                FishingFeedback.notify(owner, FishingMsgId.NO_WATER);
                close();
            } else {
                FishingFeedback.notify(owner, FishingMsgId.WAITING);
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /**
     * 魚と表示を1tick進めます。親キャストタスクからメインスレッドで呼びます。
     *
     * @param rodTip 現在の竿先。針と同じworldであること
     */
    public void tick(@NotNull Location rodTip) {
        if (phase == Phase.FINISHED) return;
        if (!player.isOnline() || player.isDead() || player.getWorld() != world
            || rodTip.getWorld() != world || !allFishValid()) {
            close();
            return;
        }
        ticks++;
        phaseTicks++;
        if (phase == Phase.WAITING && rodTip.distance(submergedHook) > maxLineLength) {
            escape();
            return;
        }
        switch (phase) {
            case WAITING -> tickWaiting();
            case BATTLE -> tickBattle(rodTip);
            case LANDING -> tickLanding(rodTip);
            case HANGING -> tickHanging(rodTip);
            case RELEASED -> tickReleased();
            case FINISHED -> { }
        }
        if (ticks % 5 == 0 && phase != Phase.FINISHED) {
            List<Location> trail = new ArrayList<>(fish.size());
            for (Swimmer swimmer : fish) {
                if (swimmer.entity.isValid() && isWaterAt(swimmer.entity.getLocation())) {
                    trail.add(swimmer.entity.getLocation());
                }
            }
            particles.spawnForNearbyViewers(trail, SharedParticleDefinitions.FISHING_ROD_TRAIL);
        }
    }

    /**
     * 糸の終点を返します。
     * @return 針または選択魚の現在位置のコピー
     */
    public @NotNull Location hookLocation() {
        return hook.clone();
    }

    /**
     * 糸の張り具合を確認します。
     * @return 魚が食いつき、糸が張っている間はtrue
     */
    public boolean isTaut() {
        return phase == Phase.BATTLE || phase == Phase.LANDING || phase == Phase.HANGING;
    }

    /**
     * 終了状態を確認します。
     * @return 魚を削除しセッションを終了した場合はtrue
     */
    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    /**
     * 魚を外した状態を確認します。
     * @return 右クリックで魚を外し、陸上を逃走中の場合はtrue
     */
    public boolean isReleased() {
        return phase == Phase.RELEASED;
    }

    /**
     * 戦闘中の左クリックでゲージ値と竿攻撃力に応じて糸を巻きます。
     * 仮式は {@code 0.30 * 竿攻撃力 * ゲージ値} メートルです。
     * 攻撃力10なら最大3m/攻撃ターン、張力は同じ80tickで0.64m増加します。
     */
    public void leftClick() {
        if (phase != Phase.BATTLE || phaseTicks % TURN_TICKS >= ATTACK_TICKS
            || ticks - lastReelTick < REEL_COOLDOWN_TICKS) return;
        lastReelTick = ticks;
        distance = Math.max(0.0D, distance - REEL_MULTIPLIER * rodAttack * gauge());
        phaseTicks = (phaseTicks / TURN_TICKS) * TURN_TICKS + ATTACK_TICKS;
        bar.setProgress(0.01D);
        player.swingMainHand();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE,
            SoundCategory.PLAYERS, 0.55F, 1.35F);
        showDistance();
        if (distance <= 0.0D) startLanding();
    }

    /** 戦闘中は糸を緩め、吊り下げ中は魚を外して逃走を開始します。 */
    public void rightClick() {
        if (phase == Phase.BATTLE) {
            distance += SLACK_DISTANCE;
            player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE,
                SoundCategory.PLAYERS, 0.4F, 0.7F);
            showDistance();
            if (distance > maxLineLength) escape();
        } else if (phase == Phase.HANGING) {
            phase = Phase.RELEASED;
            phaseTicks = 0;
            bar.setColor(BarColor.RED);
            bar.setTitle(FishingFeedback.text(FishingMsgId.HIT, hp));
            bar.setProgress(1.0D);
            bar.setVisible(true);
            if (selected != null) escapeStart = selected.entity.getLocation();
            escapeTarget = submergedHook.clone();
            FishingFeedback.notify(owner, FishingMsgId.RELEASED);
        }
    }

    /**
     * 親が所有者の近接攻撃と射程・視線を検証した後、逃走中の魚に1ダメージを与えます。
     *
     * @param target 近接攻撃が命中した実エンティティ
     */
    public void hit(@NotNull Entity target) {
        if (phase != Phase.RELEASED || selected == null || target != selected.entity || !target.isValid()) return;
        hp--;
        particles.spawnForNearbyViewers(target.getLocation(), SharedParticleDefinitions.FISHING_ROD_SPLASH);
        player.playSound(target.getLocation(), Sound.ENTITY_COD_HURT, SoundCategory.NEUTRAL, 1.0F, 1.2F);
        if (hp <= 0) {
            FishingFeedback.notify(owner, FishingMsgId.DEFEATED);
            close();
        } else {
            bar.setTitle(FishingFeedback.text(FishingMsgId.HIT, hp));
            bar.setProgress(hp / (double) FISH_MAX_HP);
            FishingFeedback.notify(owner, FishingMsgId.HIT, hp);
        }
    }

    /**
     * イベント保護や命中判定に使う、所有する試作魚かを判定します。
     * @param entity 判定対象
     * @return このセッションが生成した魚ならtrue
     */
    public boolean owns(@NotNull Entity entity) {
        for (Swimmer swimmer : fish) {
            if (swimmer.entity == entity) return true;
        }
        return false;
    }

    /** 魚、ボスバー、titleを回収します。重複呼び出しは安全です。 */
    public void close() {
        if (phase == Phase.FINISHED) return;
        phase = Phase.FINISHED;
        bar.removeAll();
        for (Swimmer swimmer : fish) {
            if (swimmer.entity.isValid()) swimmer.entity.remove();
        }
        fish.clear();
    }

    /** @return 接続した水域へ最低4匹を生成できた場合はtrue */
    private boolean spawnFish() {
        if (!isWater(submergedHook.getBlockX(), submergedHook.getBlockY(), submergedHook.getBlockZ())) return false;
        BlockPos root = new BlockPos(submergedHook.getBlockX(), submergedHook.getBlockY(), submergedHook.getBlockZ());
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> candidates = new ArrayList<>();
        parents.put(root, root);
        queue.add(root);
        while (!queue.isEmpty() && parents.size() < MAX_SEARCH_BLOCKS) {
            BlockPos current = queue.removeFirst();
            if (current.center(world).distanceSquared(submergedHook) >= MIN_SPAWN_DISTANCE_SQUARED) {
                candidates.add(current);
            }
            for (int side = 0; side < 6 && parents.size() < MAX_SEARCH_BLOCKS; side++) {
                BlockPos next = new BlockPos(current.x + DELTA_X[side], current.y + DELTA_Y[side],
                    current.z + DELTA_Z[side]);
                if (parents.containsKey(next) || next.center(world).distanceSquared(submergedHook) > SEARCH_RADIUS * SEARCH_RADIUS
                    || !isWater(next.x, next.y, next.z)) continue;
                parents.put(next, current);
                queue.addLast(next);
            }
        }
        Collections.shuffle(candidates);
        int targetCount = ThreadLocalRandom.current().nextInt(MIN_FISH, MAX_FISH + 1);
        List<BlockPos> chosen = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            if (chosen.stream().anyMatch(other -> other.center(world).distanceSquared(candidate.center(world))
                < MIN_FISH_SEPARATION_SQUARED)) continue;
            chosen.add(candidate);
            if (chosen.size() == targetCount) break;
        }
        if (chosen.size() < MIN_FISH) return false;
        for (BlockPos position : chosen) {
            List<Location> path = new ArrayList<>();
            BlockPos cursor = position;
            while (!cursor.equals(root)) {
                path.add(cursor.center(world));
                cursor = parents.get(cursor);
            }
            path.add(submergedHook.clone());
            Collections.reverse(path);
            Class<? extends Fish> species = switch (ThreadLocalRandom.current().nextInt(3)) {
                case 0 -> Cod.class;
                case 1 -> Salmon.class;
                default -> TropicalFish.class;
            };
            Fish[] creating = new Fish[1];
            Fish entity;
            try {
                entity = world.spawn(position.center(world), species,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, false, spawned -> {
                        creating[0] = spawned;
                        FishingEntityProtection.mark(spawned);
                    });
            } catch (RuntimeException failure) {
                if (creating[0] != null && creating[0].isValid()) creating[0].remove();
                throw failure;
            }
            if (!entity.isValid()) {
                entity.remove();
                return false;
            }
            fish.add(new Swimmer(entity, path));
        }
        return true;
    }

    /** 魚を水路に沿って針へ寄せ、つつきと最初の食いつきを判定します。 */
    private void tickWaiting() {
        if (phaseTicks >= MAX_WAITING_TICKS
            || !isWater(submergedHook.getBlockX(), submergedHook.getBlockY(), submergedHook.getBlockZ())
            || fish.stream().noneMatch(swimmer -> swimmer.entity.isValid())) {
            FishingFeedback.notify(owner, FishingMsgId.ESCAPED);
            close();
            return;
        }
        for (Swimmer swimmer : fish) {
            if (!swimmer.entity.isValid()) continue;
            if (swimmer.restTicks > 0) {
                swimmer.restTicks--;
                continue;
            }
            if (swimmer.pathIndex == 0 && swimmer.direction < 0) {
                swimmer.nibbleTicks++;
                if (swimmer.nibbleTicks == 1) {
                    particles.spawnForNearbyViewers(submergedHook, SharedParticleDefinitions.FISHING_ROD_SPLASH);
                }
                if (swimmer.nibbleTicks >= 8) {
                    if (ThreadLocalRandom.current().nextDouble() < 0.45D) {
                        beginBattle(swimmer);
                        return;
                    }
                    swimmer.direction = 1;
                    swimmer.nibbleTicks = 0;
                }
                continue;
            }
            if (swimmer.direction > 0 && swimmer.pathIndex >= Math.min(swimmer.path.size() - 1, 4)) {
                swimmer.direction = -1;
                swimmer.restTicks = 8;
                continue;
            }
            if (movePath(swimmer, swimmer.direction, FISH_SPEED) && swimmer.pathIndex == 0) {
                player.playSound(submergedHook, Sound.ENTITY_FISHING_BOBBER_SPLASH,
                    SoundCategory.NEUTRAL, 0.5F, 1.5F);
            }
        }
    }

    /** @param winner 最初に食いついた魚 */
    private void beginBattle(Swimmer winner) {
        selected = winner;
        phase = Phase.BATTLE;
        phaseTicks = 0;
        hook = winner.entity.getLocation();
        distance = Math.min(player.getEyeLocation().distance(hook), maxLineLength * 0.65D);
        previousRodTip = player.getEyeLocation();
        for (Swimmer swimmer : fish) {
            if (swimmer != winner) swimmer.fleeTicks = 20;
        }
        FishingFeedback.notify(owner, FishingMsgId.BITE);
        FishingFeedback.notify(owner, FishingMsgId.BATTLE);
        bar.setVisible(true);
        player.playSound(hook, Sound.ENTITY_FISHING_BOBBER_SPLASH, SoundCategory.NEUTRAL, 1.0F, 0.9F);
        particles.spawnForNearbyViewers(hook, SharedParticleDefinitions.FISHING_ROD_SPLASH);
    }

    /** 張力、攻撃ゲージ、選択魚の浮上と位置を更新します。 */
    private void tickBattle(Location rodTip) {
        if (selected == null) { close(); return; }
        fleeLosers();
        riseSelected();
        if (previousRodTip != null) {
            double oldSeparation = previousRodTip.distance(selected.entity.getLocation());
            double newSeparation = rodTip.distance(selected.entity.getLocation());
            distance += Math.max(0.0D, newSeparation - oldSeparation);
        }
        previousRodTip = rodTip.clone();
        moveSelectedWithLine(rodTip);
        hook = selected.entity.getLocation();
        distance += FISH_FORCE_PER_TICK;
        if (distance > maxLineLength) { escape(); return; }
        bar.setProgress(gauge());
        if (phaseTicks % 5 == 0) showDistance();
        if (phaseTicks % 12 == 0) {
            particles.spawnForNearbyViewers(hook, SharedParticleDefinitions.FISHING_ROD_SPLASH);
            world.playSound(hook, Sound.ENTITY_COD_FLOP, SoundCategory.NEUTRAL, 0.3F, 1.2F);
        }
    }

    /** 選ばれなかった魚を針から外向きに泳がせてから削除します。 */
    private void fleeLosers() {
        for (Swimmer swimmer : fish) {
            if (swimmer == selected || !swimmer.entity.isValid()) continue;
            movePath(swimmer, 1, FLEE_SPEED);
            if (--swimmer.fleeTicks <= 0) swimmer.entity.remove();
        }
    }

    /** 選択魚を現在の水柱の水面へ浮上させます。 */
    private void riseSelected() {
        if (selected == null || !selected.entity.isValid()) return;
        Location current = selected.entity.getLocation();
        int x = current.getBlockX();
        int z = current.getBlockZ();
        int y = current.getBlockY();
        int top = y;
        for (int step = 0; step < 16 && top < world.getMaxHeight() - 1 && isWater(x, top + 1, z); step++) top++;
        double targetY = top + 0.65D;
        if (current.getY() < targetY) {
            Location next = current.clone().add(0.0D, Math.min(0.10D, targetY - current.getY()), 0.0D);
            if (canTravel(current, next)) selected.entity.teleport(next);
        }
    }

    /**
     * 残り糸距離に合わせて魚を竿先へ寄せ、張力で離し、横方向にも暴れさせます。
     * 障害物や未読込チャンクに入る移動は止めます。
     */
    private void moveSelectedWithLine(Location rodTip) {
        Location current = selected.entity.getLocation();
        Vector radial = current.toVector().subtract(rodTip.toVector());
        if (radial.lengthSquared() < 1.0E-6D) radial = player.getEyeLocation().getDirection();
        radial.normalize();
        Vector lateral = radial.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        if (lateral.lengthSquared() > 1.0E-6D) lateral.normalize().multiply(Math.sin(phaseTicks * 0.25D) * 0.15D);
        Location desired = rodTip.clone().add(radial.multiply(distance)).add(lateral);
        Vector delta = desired.toVector().subtract(current.toVector());
        if (delta.lengthSquared() < 1.0E-6D) return;
        Vector motion = delta.length() > 0.24D ? delta.normalize().multiply(0.24D) : delta;
        Location next = current.clone().add(motion).setDirection(motion);
        if (canTravel(current, next)) selected.entity.teleport(next);
    }

    /** @return 攻撃ターンの往復ゲージ、休止ターンでは0.01 */
    private double gauge() {
        int turn = phaseTicks % TURN_TICKS;
        if (turn >= ATTACK_TICKS) return 0.01D;
        return Math.max(0.0D, 1.0D - Math.abs(turn - ATTACK_TICKS / 2.0D) / (ATTACK_TICKS / 2.0D));
    }

    /** 残り糸距離をtitleへ表示します。 */
    private void showDistance() {
        bar.setTitle(FishingFeedback.text(FishingMsgId.BATTLE) + " | "
            + FishingFeedback.text(FishingMsgId.REELING, String.format(Locale.ROOT, "%.1f", distance)));
    }

    /** 距離0から放物線の釣り上げを開始します。 */
    private void startLanding() {
        phase = Phase.LANDING;
        phaseTicks = 0;
        bar.setVisible(false);
        landingStart = selected.entity.getLocation();
        player.swingMainHand();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE,
            SoundCategory.PLAYERS, 1.0F, 1.5F);
    }

    /** @param rodTip 弧の到達点を決める現在の竿先 */
    private void tickLanding(Location rodTip) {
        if (selected == null) { close(); return; }
        fleeLosers();
        double progress = Math.min(1.0D, phaseTicks / (double) LANDING_TICKS);
        Location end = hangPosition(rodTip);
        Vector travel = end.toVector().subtract(landingStart.toVector());
        Location next = landingStart.clone().add(travel.multiply(progress))
            .add(0.0D, Math.sin(Math.PI * progress) * 1.3D, 0.0D);
        if (!canTravel(selected.entity.getLocation(), next)) { escape(); return; }
        selected.entity.teleport(next);
        hook = next;
        if (phaseTicks >= LANDING_TICKS) {
            phase = Phase.HANGING;
            phaseTicks = 0;
            bar.setTitle(FishingFeedback.text(FishingMsgId.HANGING, "5.0"));
            bar.setColor(BarColor.YELLOW);
            bar.setProgress(1.0D);
            bar.setVisible(true);
            FishingFeedback.notify(owner, FishingMsgId.LANDED);
        }
    }

    /** @param rodTip 吊り下げ位置を決める現在の竿先 */
    private void tickHanging(Location rodTip) {
        if (selected == null) { close(); return; }
        fleeLosers();
        Location position = hangPosition(rodTip).add(0.0D, Math.sin(phaseTicks * 0.18D) * 0.05D, 0.0D);
        if (!canTravel(selected.entity.getLocation(), position)) { escape(); return; }
        selected.entity.teleport(position);
        hook = position;
        int remaining = Math.max(0, HANGING_TICKS - phaseTicks);
        bar.setProgress(remaining / (double) HANGING_TICKS);
        if (phaseTicks % 5 == 0) {
            bar.setTitle(FishingFeedback.text(FishingMsgId.HANGING,
                String.format(Locale.ROOT, "%.1f", remaining / 20.0D)));
        }
        if (remaining == 0) {
            escape();
        }
    }

    /** @return 竿先の先に吊り下げる魚の位置 */
    private Location hangPosition(Location rodTip) {
        Vector forward = player.getEyeLocation().getDirection().setY(0.0D);
        if (forward.lengthSquared() < 1.0E-6D) forward = new Vector(0.0D, 0.0D, 1.0D);
        return rodTip.clone().add(forward.normalize().multiply(1.2D)).add(0.0D, -1.15D, 0.0D);
    }

    /** 解除魚を水まで跳ねさせ、接水または時間切れで報酬なく消します。 */
    private void tickReleased() {
        if (selected == null || escapeStart == null) { close(); return; }
        if (isWaterAt(selected.entity.getLocation()) || phaseTicks >= MAX_ESCAPE_TICKS) {
            escape();
            return;
        }
        double progress = Math.min(1.0D, phaseTicks / 60.0D);
        Vector travel = escapeTarget.toVector().subtract(escapeStart.toVector());
        Location next = escapeStart.clone().add(travel.multiply(progress))
            .add(0.0D, Math.abs(Math.sin(phaseTicks * 0.42D)) * 0.45D, 0.0D);
        Location current = selected.entity.getLocation();
        if (touchesWater(current, next)) { escape(); return; }
        if (!canTravel(current, next)) {
            Location hop = next.clone().add(0.0D, 0.45D, 0.0D);
            if (!canTravel(current, hop)) return;
            next = hop;
        }
        next.setDirection(next.toVector().subtract(current.toVector()));
        selected.entity.teleport(next);
        hook = next.clone();
        if (phaseTicks % 9 == 0) {
            world.playSound(next, Sound.ENTITY_COD_FLOP, SoundCategory.NEUTRAL, 0.8F, 1.0F);
        }
        if (progress >= 1.0D) {
            escape();
        }
    }

    /** 報酬を付けず魚を逃がし、全表示を回収します。 */
    private void escape() {
        FishingFeedback.notify(owner, FishingMsgId.ESCAPED);
        close();
    }

    /** @return 線分が水または泡柱へ触れる場合はtrue */
    private boolean touchesWater(Location from, Location to) {
        Vector travel = to.toVector().subtract(from.toVector());
        int steps = Math.max(1, (int) Math.ceil(travel.length() / 0.12D));
        for (int step = 1; step <= steps; step++) {
            Location point = from.clone().add(travel.clone().multiply(step / (double) steps));
            if (isWaterAt(point)) return true;
        }
        return false;
    }

    /** @return 移動線分が読込済みで固体ブロックを通らない場合はtrue */
    private boolean canTravel(Location from, Location to) {
        if (from.getWorld() != world || to.getWorld() != world) return false;
        Vector travel = to.toVector().subtract(from.toVector());
        int steps = Math.max(1, (int) Math.ceil(travel.length() / 0.12D));
        for (int step = 1; step <= steps; step++) {
            Location point = from.clone().add(travel.clone().multiply(step / (double) steps));
            if (!world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)
                || point.getBlock().getType().isSolid()) return false;
        }
        return true;
    }

    /** @return 読込済みの水または泡柱に位置する場合はtrue */
    private boolean isWaterAt(Location point) {
        return point.getWorld() == world && isWater(point.getBlockX(), point.getBlockY(), point.getBlockZ());
    }

    /** @return 水路上を移動できた場合はtrue */
    private boolean movePath(Swimmer swimmer, int step, double speed) {
        int nextIndex = swimmer.pathIndex + step;
        if (nextIndex < 0 || nextIndex >= swimmer.path.size()) return false;
        Location current = swimmer.entity.getLocation();
        Location target = swimmer.path.get(nextIndex);
        if (!isWater(target.getBlockX(), target.getBlockY(), target.getBlockZ())) {
            swimmer.entity.remove();
            return false;
        }
        Vector delta = target.toVector().subtract(current.toVector());
        if (delta.lengthSquared() <= speed * speed) {
            if (!canTravel(current, target)) return false;
            swimmer.entity.teleport(target);
            swimmer.pathIndex = nextIndex;
            return true;
        }
        Location next = current.add(delta.normalize().multiply(speed));
        if (!canTravel(swimmer.entity.getLocation(), next)) return false;
        swimmer.entity.teleport(next);
        return true;
    }

    /** @return 読み込み済みチャンク内の水源または流水ならtrue */
    private boolean isWater(int x, int y, int z) {
        return world != null && y >= world.getMinHeight() && y < world.getMaxHeight()
            && world.isChunkLoaded(x >> 4, z >> 4)
            && (world.getBlockAt(x, y, z).getType() == Material.WATER
                || world.getBlockAt(x, y, z).getType() == Material.BUBBLE_COLUMN);
    }

    /** @return 選択魚がまだ実体として存在すればtrue */
    private boolean allFishValid() {
        return selected == null || selected.entity.isValid();
    }

    private enum Phase { WAITING, BATTLE, LANDING, HANGING, RELEASED, FINISHED }

    private record BlockPos(int x, int y, int z) {
        /** @return ブロック中央の位置 */
        private Location center(World world) {
            return new Location(world, x + 0.5D, y + 0.5D, z + 0.5D);
        }
    }

    private static final class Swimmer {
        private final Fish entity;
        private final List<Location> path;
        private int pathIndex;
        private int restTicks;
        private int nibbleTicks;
        private int fleeTicks;
        private int direction = -1;

        /** @param entity 接続水域に生成した実魚 @param path 針から魚へ続く水路 */
        private Swimmer(Fish entity, List<Location> path) {
            this.entity = entity;
            this.path = path;
            this.pathIndex = path.size() - 1;
            this.restTicks = ThreadLocalRandom.current().nextInt(10, 35);
        }
    }
}
