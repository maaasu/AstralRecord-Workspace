package io.github.maaasu.astralRecord.feature.fishing.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.fishing.message.FishingMsgId;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingEntityProtection;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
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
import org.bukkit.entity.Fish;
import org.bukkit.entity.Player;
import org.bukkit.entity.PufferFish;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.FishDefinition;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.Rules;
import org.bukkit.entity.Salmon;
import org.bukkit.entity.TropicalFish;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;
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
 * マスタ定義に従う一回分の魚探索、リール操作、陸上での捕獲を管理します。
 * Bukkit API と実魚の移動は親キャストタスクからメインスレッドで更新します。
 */
public final class FishingSession {
    /** キャスト・釣り待機・リールで共用する、最大糸長の丸め誤差許容（m）。 */
    public static final double LINE_LENGTH_EPSILON = 1.0E-8D;
    private static final int MAX_SEARCH_BLOCKS = 5_000;
    private static final double MIN_SPAWN_DISTANCE_SQUARED = 5.0D;
    private static final double MIN_FISH_SEPARATION_SQUARED = 3.0D;
    private static final int MAX_TRAVEL_STEPS = 256;
    private static final double TRAVEL_SAMPLE_STEP = 0.12D;
    private static final int MAX_SURFACE_SCAN = 64;
    private static final int[] DELTA_X = {1, -1, 0, 0, 0, 0};
    private static final int[] DELTA_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] DELTA_Z = {0, 0, 0, 0, 1, -1};

    private final ParticleDisplayService particles;
    private final AstPlayer owner;
    private final Player player;
    private final MobService mobService;
    private final World world;
    private final Location submergedHook;
    private final double maxLineLength;
    private final double rodAttack;
    private final FishingMasterService masterService;
    private final FishingMasterService.SessionDefinition definition;
    private final Rules rules;
    private static final int MAX_SESSION_TICKS = 72_000;
    private final List<Swimmer> fish = new ArrayList<>();
    private final BossBar bar;

    private Phase phase = Phase.WAITING;
    private Swimmer selected;
    private Location hook;
    private Location landingStart;
    private FishingEscapeMotion escapeMotion;
    private BoundingBox combatBounds;
    private FishingCombatBridge.Handle combatHandle;
    private Location previousRodTip;
    private int ticks;
    private int phaseTicks;
    private int lastReelTick = Integer.MIN_VALUE / 2;
    private double combatMaxHealth;
    private double hp;
    private double distance;
    private double surfaceY;
    private int surfaceBlockX = Integer.MIN_VALUE;
    private int surfaceBlockZ = Integer.MIN_VALUE;

    /**
     * 針と連続する読み込み済み水域へ、開始時マスタに従う魚を生成します。
     *
     * @param plugin 実行中のプラグイン。呼び出しはメインスレッドで行うこと
     * @param particles 共通粒子表示サービス
     * @param owner 釣りを開始したプレイヤー
     * @param submergedHook 水中にある仮想針の位置
     * @param maxLineLength 竿から許される糸の最大距離
     * @param rodAttack キャスト時点の竿単体の攻撃力
     * @param masterService 釣果確定サービス
     * @param definition 着水ワールドと竿レベルから取得した開始時定義
     */
    public FishingSession(@NotNull AstralRecord plugin, @NotNull ParticleDisplayService particles,
                                   @NotNull AstPlayer owner, @NotNull Location submergedHook,
                                   double maxLineLength, double rodAttack,
                          @NotNull FishingMasterService masterService,
                          @NotNull FishingMasterService.SessionDefinition definition) {
        this.masterService = masterService;
        this.definition = definition;
        this.rules = definition.rules();
        this.particles = particles;
        this.owner = owner;
        this.player = owner.getBukkit();
        this.mobService = plugin.getMobService();
        this.world = submergedHook.getWorld();
        this.submergedHook = submergedHook.clone();
        this.hook = submergedHook.clone();
        this.maxLineLength = maxLineLength;
        this.rodAttack = Double.isFinite(rodAttack) && rodAttack > 0.0D ? rodAttack : 10.0D;
        this.bar = plugin.getServer().createBossBar(bossTitle(FishingFeedback.text(FishingMsgId.BATTLE)),
            BarColor.BLUE, BarStyle.SOLID);
        this.bar.addPlayer(player);
        this.bar.setProgress(0.01D);
        this.bar.setVisible(false);
        try {
            if (world == null || !Double.isFinite(maxLineLength) || maxLineLength <= 0.0D || !spawnFish()) {
                FishingFeedback.notify(owner, FishingMsgId.NO_WATER);
                close();
            } else {
                FishingFeedback.notify(owner, FishingMsgId.WAITING, levelLabel());
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /**
     * 魚と表示を1tick進めます。親キャストタスクからメインスレッドで呼びます。
     * 糸長判定はキャストと同じ丸め誤差許容を使い、着水境界だけで逃走させません。
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
        if (ticks >= MAX_SESSION_TICKS) { escape(); return; }
        if (phase == Phase.WAITING && rodTip.distance(submergedHook) > maxLineLength + LINE_LENGTH_EPSILON) {
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
     * 釣り上げ済みの魚へ通常戦闘を行える状態かを返します。
     * @return 吊り下げ中または取り外した後ならtrue。武器持ち替えも許可します
     */
    public boolean isCombatPhase() {
        return phase == Phase.HANGING || phase == Phase.RELEASED;
    }

    /**
     * リール操作と魚の取り外しに必要な入力だけを予約します。
     * @param rightClick 右クリックならtrue
     * @return 専用操作中ならtrue。釣り上げ後の攻撃入力は予約しません
     */
    public boolean reservesInput(boolean rightClick) {
        return phase != Phase.FINISHED && phase != Phase.RELEASED
            && (phase != Phase.HANGING || rightClick);
    }

    /**
     * 戦闘中の左クリックでゲージ値と竿攻撃力に応じて糸を巻きます。
     * 巻き取り距離は {@code マスタ係数 * 竿単体攻撃力 * ゲージ値} メートルです。
     */
    public void leftClick() {
        if (phase != Phase.BATTLE || phaseTicks % rules.turnTicks() >= rules.attackTicks()
            || ticks - lastReelTick < rules.reelCooldownTicks()) return;
        lastReelTick = ticks;
        distance = Math.max(0.0D, distance - rules.reelMultiplier() * rodAttack * gauge());
        phaseTicks = (phaseTicks / rules.turnTicks()) * rules.turnTicks() + rules.attackTicks();
        bar.setProgress(0.01D);
        player.swingMainHand();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE,
            SoundCategory.PLAYERS, 0.55F, 1.35F);
        showDistance();
        if (distance <= 0.0D) startLanding();
    }

    /**
     * 戦闘中は左クリックと同じゲージ・ターン・入力間隔で巻き取り、吊り下げ中は魚を外します。
     * 親キャストの状態確認後、メインスレッドで呼びます。
     */
    public void rightClick() {
        if (phase == Phase.BATTLE) {
            leftClick();
        } else if (phase == Phase.HANGING) {
            releaseFish();
        }
    }

    /** 手動操作とマスタ時間経過を同じ取り外しに接続します。取り外すだけでは魚を削除しません。 */
    private void releaseFish() {
        if (phase != Phase.HANGING || selected == null) return;
        phase = Phase.RELEASED;
        phaseTicks = 0;
        bar.setColor(BarColor.RED);
        bar.setTitle(bossTitle(FishingFeedback.text(FishingMsgId.HIT, healthLabel())));
        bar.setProgress(hp / combatMaxHealth);
        bar.setVisible(true);
        escapeMotion = new FishingEscapeMotion(selected.entity.getLocation(), submergedHook, combatBounds,
            selected.definition.landFleeSpeed(), selected.definition.hopSpeed());
        FishingFeedback.notify(owner, FishingMsgId.RELEASED);
    }

    /** 戦闘登録、魚、ボスバーを回収します。重複呼び出しは安全で、終了だけでは釣果を付けません。 */
    public void close() {
        if (phase == Phase.FINISHED) return;
        phase = Phase.FINISHED;
        bar.removeAll();
        if (combatHandle != null) {
            combatHandle.unregister();
            combatHandle = null;
        }
        for (Swimmer swimmer : fish) {
            if (swimmer.entity.isValid()) swimmer.entity.remove();
        }
        fish.clear();
    }

    /** @return 接続した水域へ定義された最小匹数を生成できた場合はtrue */
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
                if (parents.containsKey(next) || next.center(world).distanceSquared(submergedHook) > rules.searchRadius() * rules.searchRadius()
                    || !isWater(next.x, next.y, next.z)) continue;
                parents.put(next, current);
                queue.addLast(next);
            }
        }
        Collections.shuffle(candidates);
        int targetCount = ThreadLocalRandom.current().nextInt(rules.fishCountMin(), rules.fishCountMax() + 1);
        List<BlockPos> chosen = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            if (chosen.stream().anyMatch(other -> other.center(world).distanceSquared(candidate.center(world))
                < MIN_FISH_SEPARATION_SQUARED)) continue;
            chosen.add(candidate);
            if (chosen.size() == targetCount) break;
        }
        if (chosen.size() < rules.fishCountMin()) return false;
        for (BlockPos position : chosen) {
            List<Location> path = new ArrayList<>();
            BlockPos cursor = position;
            while (!cursor.equals(root)) {
                path.add(cursor.center(world));
                cursor = parents.get(cursor);
            }
            path.add(submergedHook.clone());
            Collections.reverse(path);
            FishDefinition fishDefinition = definition.drawFish(ThreadLocalRandom.current().nextDouble(100.0));
            Class<? extends Fish> species = switch (fishDefinition.entityType()) {
                case COD -> Cod.class;
                case SALMON -> Salmon.class;
                case TROPICAL_FISH -> TropicalFish.class;
                case PUFFERFISH -> PufferFish.class;
                default -> throw new IllegalStateException("Unsupported fishing entity");
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
            fish.add(new Swimmer(entity, path, fishDefinition));
        }
        return true;
    }

    /** 魚を水路に沿って針へ寄せ、つつきと最初の食いつきを判定します。 */
    private void tickWaiting() {
        if (phaseTicks >= rules.waitingTicks()
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
                nibble(swimmer);
                if (swimmer.nibbleTicks % rules.nibbleCycleTicks() == rules.nibbleCycleTicks() / 2) {
                    particles.spawnForNearbyViewers(submergedHook, SharedParticleDefinitions.FISHING_ROD_SPLASH);
                    player.playSound(submergedHook, Sound.ENTITY_FISHING_BOBBER_SPLASH,
                        SoundCategory.NEUTRAL, 0.5F, 1.45F);
                }
                if (swimmer.nibbleTicks >= rules.nibbleTicks()) {
                    if (ThreadLocalRandom.current().nextDouble(100.0) < swimmer.definition.biteChancePercent()) {
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
            movePath(swimmer, swimmer.direction, swimmer.definition.swimSpeed());
        }
    }

    /** 魚を針から約0.15m離して戻す往復を8tickずつ3回行います。 */
    private void nibble(Swimmer swimmer) {
        if (swimmer.path.size() < 2) return;
        Vector outward = swimmer.path.get(1).toVector().subtract(submergedHook.toVector());
        if (outward.lengthSquared() < 1.0E-6D) return;
        double phase = (swimmer.nibbleTicks % rules.nibbleCycleTicks()) / (double) rules.nibbleCycleTicks();
        double offset = rules.nibbleDistance() * (1.0D + Math.cos(2.0D * Math.PI * phase)) * 0.5D;
        Location next = submergedHook.clone().add(outward.normalize().multiply(offset));
        Location current = swimmer.entity.getLocation();
        if (!isWaterAt(next) || !canTravel(swimmer.entity, current, next)) return;
        Vector motion = next.toVector().subtract(current.toVector());
        if (motion.lengthSquared() > 1.0E-8D) next.setDirection(motion);
        swimmer.entity.teleport(next);
    }

    /**
     * 共通戦闘と同じ最低1の実効最大HPで表示を初期化します。
     * @param winner 最初に食いついた魚
     */
    private void beginBattle(Swimmer winner) {
        selected = winner;
        combatMaxHealth = Math.max(1.0D, winner.definition.maxHealth());
        hp = combatMaxHealth;
        phase = Phase.BATTLE;
        phaseTicks = 0;
        hook = winner.entity.getLocation();
        surfaceY = surfaceYAt(hook);
        surfaceBlockX = hook.getBlockX();
        surfaceBlockZ = hook.getBlockZ();
        distance = Math.min(player.getEyeLocation().distance(hook), maxLineLength * 0.65D);
        previousRodTip = player.getEyeLocation();
        for (Swimmer swimmer : fish) {
            if (swimmer != winner) swimmer.fleeTicks = 20;
        }
        FishingFeedback.notify(owner, FishingMsgId.BITE);
        FishingFeedback.notify(owner, FishingMsgId.BATTLE);
        FishingFeedback.notify(owner, FishingMsgId.GAUGE_HINT);
        bar.setVisible(true);
        player.playSound(hook, Sound.ENTITY_FISHING_BOBBER_SPLASH, SoundCategory.NEUTRAL, 1.0F, 0.9F);
        particles.spawnForNearbyViewers(hook, SharedParticleDefinitions.FISHING_ROD_SPLASH);
    }

    /**
     * 張力、攻撃ゲージ、選択魚の浮上と位置を更新し、丸め誤差許容を超える糸長で逃走させます。
     * @param rodTip 現在の竿先。同じworldでメインスレッドから渡します
     */
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
        distance += selected.definition.pullPerTick();
        if (distance > maxLineLength + LINE_LENGTH_EPSILON) { escape(); return; }
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
            movePath(swimmer, 1, swimmer.definition.fleeSpeed());
            if (--swimmer.fleeTicks <= 0) swimmer.entity.remove();
        }
    }

    /** 選択魚を現在の水柱の水面へ浮上させ、空中へ無限上昇させません。 */
    private void riseSelected() {
        if (selected == null || !selected.entity.isValid()) return;
        Location current = selected.entity.getLocation();
        boolean columnChanged = current.getBlockX() != surfaceBlockX || current.getBlockZ() != surfaceBlockZ;
        boolean scanLimitReached = current.getY() >= surfaceY - 0.05D
            && isWater(current.getBlockX(), (int) Math.floor(surfaceY + 0.05D), current.getBlockZ());
        if (columnChanged || scanLimitReached) {
            double columnSurface = surfaceYAt(current);
            if (Double.isFinite(columnSurface)) {
                surfaceY = columnSurface;
                surfaceBlockX = current.getBlockX();
                surfaceBlockZ = current.getBlockZ();
            }
        }
        double vertical = Math.max(-0.10D, Math.min(0.10D, surfaceY - current.getY()));
        if (Math.abs(vertical) > 1.0E-4D) {
            Location next = current.clone().add(0.0D, vertical, 0.0D);
            if (canTravel(selected.entity, current, next)) selected.entity.teleport(next);
        }
    }

    /**
     * 現在座標の真下16ブロックと上方64ブロックまでで連続水柱の水面を求めます。
     * @return 水面上の魚中心Y。水柱がなければNaN
     */
    private double surfaceYAt(Location point) {
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int waterY = Integer.MIN_VALUE;
        for (int depth = 0; depth <= 16; depth++) {
            int y = point.getBlockY() - depth;
            if (isWater(x, y, z)) { waterY = y; break; }
        }
        if (waterY == Integer.MIN_VALUE) return Double.NaN;
        int top = waterY;
        for (int step = 0; step < MAX_SURFACE_SCAN && top < world.getMaxHeight() - 1
            && isWater(x, top + 1, z); step++) top++;
        return top + 1.02D;
    }

    /**
     * 残り糸距離に合わせて魚を竿先へ寄せ、張力で離し、横方向にも暴れさせます。
     * 障害物や未読込チャンクに入る移動は止めます。
     */
    private void moveSelectedWithLine(Location rodTip) {
        Location current = selected.entity.getLocation();
        if (current.getY() < surfaceY - 0.25D) return;
        Vector radial = new Vector(current.getX() - rodTip.getX(), 0.0D, current.getZ() - rodTip.getZ());
        if (radial.lengthSquared() < 1.0E-6D) {
            radial = player.getEyeLocation().getDirection().setY(0.0D);
        }
        if (radial.lengthSquared() < 1.0E-6D) radial = new Vector(1.0D, 0.0D, 0.0D);
        radial.normalize();
        Vector lateral = radial.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D)).normalize();
        double height = current.getY() - rodTip.getY();
        double horizontalDistance = Math.sqrt(Math.max(0.0D, distance * distance - height * height));
        Location desired = new Location(world,
            rodTip.getX() + radial.getX() * horizontalDistance + lateral.getX() * Math.sin(phaseTicks * 0.25D) * 0.18D,
            current.getY(),
            rodTip.getZ() + radial.getZ() * horizontalDistance + lateral.getZ() * Math.sin(phaseTicks * 0.25D) * 0.18D);
        if (moveOnSurface(current, desired)) return;
        // 岸に引っかかるときも、同じ水面内で横方向へ暴れる。
        Location side = current.clone().add(lateral.multiply(Math.sin(phaseTicks * 0.35D) * 0.13D));
        moveOnSurface(current, side);
    }

    /** @return 同じ水面の読込済み位置へ魚を動かせた場合はtrue */
    private boolean moveOnSurface(Location current, Location desired) {
        Vector delta = desired.toVector().subtract(current.toVector());
        if (delta.lengthSquared() < 1.0E-6D) return false;
        Vector motion = delta.length() > 0.24D ? delta.normalize().multiply(0.24D) : delta;
        Location next = current.clone().add(motion).setDirection(motion);
        if (!isWater(next.getBlockX(), (int) Math.floor(surfaceY - 0.10D), next.getBlockZ())
            || !canTravel(selected.entity, current, next)) return false;
        selected.entity.teleport(next);
        return true;
    }

    /** @return 攻撃ターンの往復ゲージ、休止ターンでは0.01 */
    private double gauge() {
        int turn = phaseTicks % rules.turnTicks();
        if (turn >= rules.attackTicks()) return 0.01D;
        return Math.max(0.0D, 1.0D - Math.abs(turn - rules.attackTicks() / 2.0D) / (rules.attackTicks() / 2.0D));
    }

    /** @return 適用レベル。下位定義を使用する場合だけ竿レベルも併記します */
    private String levelLabel() {
        int effective = definition.effectiveFishingLevel();
        return effective == definition.fishingLevel()
            ? FishingFeedback.text(FishingMsgId.LEVEL, effective)
            : FishingFeedback.text(FishingMsgId.LEVEL_WITH_ROD, effective, definition.fishingLevel());
    }

    /** @param state フェーズの案内 @return 既存ボスバーに収める短いレベル表示と案内 */
    private String bossTitle(String state) {
        return levelLabel() + " | " + state;
    }

    /** 残り糸距離をボスバーの題名へ表示します。 */
    private void showDistance() {
        bar.setTitle(bossTitle(FishingFeedback.text(FishingMsgId.BATTLE) + " | "
            + FishingFeedback.text(FishingMsgId.REELING, String.format(Locale.ROOT, "%.1f", distance))));
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

    /** @param rodTip 弧の到達点を決める現在の竿先。障害物がある間は魚をその手前に保ちます */
    private void tickLanding(Location rodTip) {
        if (selected == null) { close(); return; }
        fleeLosers();
        double progress = Math.min(1.0D, phaseTicks / (double) rules.landingTicks());
        Location end = hangPosition(rodTip);
        Vector travel = end.toVector().subtract(landingStart.toVector());
        Location next = landingStart.clone().add(travel.multiply(progress))
            .add(0.0D, Math.sin(Math.PI * progress) * 1.3D, 0.0D);
        if (!canTravel(selected.entity, selected.entity.getLocation(), next)) return;
        selected.entity.teleport(next);
        hook = next;
        if (phaseTicks >= rules.landingTicks()) {
            phase = Phase.HANGING;
            phaseTicks = 0;
            combatBounds = selected.entity.getBoundingBox().clone()
                .shift(selected.entity.getLocation().toVector().multiply(-1.0D));
            combatHandle = mobService.fishingCombat().register(owner, selected.entity, selected.definition.name(), selected.definition.maxHealth(),
                this::canReceiveCombatDamage, this::onCombatHealthChanged, this::onFishDefeated);
            bar.setTitle(bossTitle(FishingFeedback.text(FishingMsgId.HANGING,
                String.format(Locale.ROOT, "%.1f", rules.hangingTicks() / 20.0))));
            bar.setColor(BarColor.YELLOW);
            bar.setProgress(1.0D);
            bar.setVisible(true);
            FishingFeedback.notify(owner, FishingMsgId.LANDED);
        }
    }

    /** @param rodTip 吊り下げ位置を決める現在の竿先。接水・障害物では逃がさず、マスタ時間で自動解除します */
    private void tickHanging(Location rodTip) {
        if (selected == null) { close(); return; }
        fleeLosers();
        Location position = hangPosition(rodTip).add(0.0D, Math.sin(phaseTicks * 0.18D) * 0.05D, 0.0D);
        if (canTravel(selected.entity, selected.entity.getLocation(), position)) {
            selected.entity.teleport(position);
        }
        hook = selected.entity.getLocation();
        combatHandle.syncLocation();
        int remaining = Math.max(0, rules.hangingTicks() - phaseTicks);
        bar.setProgress(remaining / (double) rules.hangingTicks());
        if (phaseTicks % 5 == 0) {
            bar.setTitle(bossTitle(FishingFeedback.text(FishingMsgId.HANGING,
                String.format(Locale.ROOT, "%.1f", remaining / 20.0D))));
        }
        if (remaining == 0) {
            releaseFish();
        }
    }

    /** @return 竿先の先に吊り下げる魚の位置 */
    private Location hangPosition(Location rodTip) {
        Vector forward = player.getEyeLocation().getDirection().setY(0.0D);
        if (forward.lengthSquared() < 1.0E-6D) forward = new Vector(0.0D, 0.0D, 1.0D);
        return rodTip.clone().add(forward.normalize().multiply(1.2D)).add(0.0D, -1.15D, 0.0D);
    }

    /** 重力で落下し、接地時に高さ・方向を変えて跳ね、壁を避けます。元の水域以外への接水も逃走となります。 */
    private void tickReleased() {
        if (selected == null || escapeMotion == null) { close(); return; }
        if (phaseTicks >= rules.escapeTicks()) {
            escape();
            return;
        }
        FishingEscapeMotion.Step step = escapeMotion.tick(selected.entity.getLocation());
        if (step.escaped()) { escape(); return; }
        selected.entity.teleport(step.position());
        hook = step.position();
        combatHandle.syncLocation();
        if (step.bounced()) world.playSound(hook, Sound.ENTITY_COD_FLOP, SoundCategory.NEUTRAL, 0.8F, 1.0F);
    }

    /** @return 吊り下げ中、または取り外し後にまだ接水していない実魚ならtrue。攻撃直前にも確認します */
    private boolean canReceiveCombatDamage() {
        return isCombatPhase() && selected != null && selected.entity.isValid()
            && player.isOnline() && !player.isDead()
            && owner.getAccount().getMode() == AccountMode.PLAYER
            && player.getWorld() == world && selected.entity.getWorld() == world
            && (phase != Phase.RELEASED
                || !FishingEscapeMotion.touchesWater(selected.entity.getLocation(), combatBounds));
    }

    /**
     * 共通DamageServiceで確定したHPを、共通戦闘の実効最大HPと同じ上限・割合で表示へ反映します。
     * @param remaining 共通戦闘が確定した残HP。ダメージは再計算しません
     */
    private void onCombatHealthChanged(double remaining) {
        if (!isCombatPhase()) return;
        hp = Math.max(0.0D, Math.min(combatMaxHealth, remaining));
        if (isReleased()) {
            bar.setTitle(bossTitle(FishingFeedback.text(FishingMsgId.HIT, healthLabel())));
            bar.setProgress(hp / combatMaxHealth);
        }
        if (hp > 0.0D) {
            world.playSound(selected.entity.getLocation(), Sound.ENTITY_COD_HURT, SoundCategory.NEUTRAL, 0.8F, 1.0F);
        }
    }

    /** 共通戦闘で所有者またはそのペットが倒した場合だけ釣果成立を通知します。 */
    private void onFishDefeated() {
        if (phase == Phase.FINISHED) return;
        FishDefinition caught = selected.definition;
        Location catchLocation = hook.clone();
        // 報酬処理より先に終了し、重複コールバックからの二重付与を防ぎます。
        close();
        masterService.grantCatch(owner, catchLocation, definition, caught);
        FishingFeedback.notify(owner, FishingMsgId.DEFEATED);
    }

    /** @return ボスバーへ表示する残HP。端数を切り上げ、残りがあるとき0に見せません */
    private int healthLabel() {
        return (int) Math.ceil(hp);
    }

    /** 報酬を付けず魚を逃がし、全表示を回収します。 */
    private void escape() {
        FishingFeedback.notify(owner, FishingMsgId.ESCAPED);
        close();
    }

    /** @return 移動線分上で魚の実体AABBが読込済みかつ固体の衝突形状と重ならない場合true */
    private boolean canTravel(Fish movingFish, Location from, Location to) {
        if (from.getWorld() != world || to.getWorld() != world) return false;
        Vector travel = to.toVector().subtract(from.toVector());
        double length = travel.length();
        if (!Double.isFinite(length) || length > MAX_TRAVEL_STEPS * TRAVEL_SAMPLE_STEP) {
            return false;
        }
        int steps = Math.max(1, (int) Math.ceil(length / TRAVEL_SAMPLE_STEP));
        BoundingBox localBounds = movingFish.getBoundingBox().clone()
            .shift(from.toVector().multiply(-1.0D));
        for (int step = 1; step <= steps; step++) {
            Location point = from.clone().add(travel.clone().multiply(step / (double) steps));
            if (!FishingEscapeMotion.canOccupy(point, localBounds)) return false;
        }
        return true;
    }

    /** @return 読込済みの水・泡柱・水草に位置する場合はtrue */
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
            if (!canTravel(swimmer.entity, current, target)) return false;
            Location destination = target.clone();
            if (delta.lengthSquared() > 1.0E-8D) destination.setDirection(delta);
            swimmer.entity.teleport(destination);
            swimmer.pathIndex = nextIndex;
            return true;
        }
        Location next = current.add(delta.normalize().multiply(speed)).setDirection(delta);
        if (!canTravel(swimmer.entity, swimmer.entity.getLocation(), next)) return false;
        swimmer.entity.teleport(next);
        return true;
    }

    /**
     * 読み込み済みの水源・流水・泡柱・水草を水路として扱います。
     * @param x 調べるブロックのX座標
     * @param y 調べるブロックのY座標
     * @param z 調べるブロックのZ座標
     * @return 読込済みの水路ならtrue
     */
    private boolean isWater(int x, int y, int z) {
        if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight()
            || !world.isChunkLoaded(x >> 4, z >> 4)) return false;
        Material material = world.getBlockAt(x, y, z).getType();
        return material == Material.WATER || material == Material.BUBBLE_COLUMN
            || material == Material.SEAGRASS || material == Material.TALL_SEAGRASS
            || material == Material.KELP || material == Material.KELP_PLANT;
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
        private final FishDefinition definition;
        private final List<Location> path;
        private int pathIndex;
        private int restTicks;
        private int nibbleTicks;
        private int fleeTicks;
        private int direction = -1;

        /** @param entity 接続水域に生成した実魚 @param path 針から魚へ続く水路 */
        private Swimmer(Fish entity, List<Location> path, FishDefinition definition) {
            this.definition = definition;
            this.entity = entity;
            this.path = path;
            this.pathIndex = path.size() - 1;
            this.restTicks = ThreadLocalRandom.current().nextInt(10, 35);
        }
    }
}
