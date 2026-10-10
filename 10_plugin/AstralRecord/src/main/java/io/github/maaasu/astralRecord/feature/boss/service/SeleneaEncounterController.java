package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 月蝕の綴り魔女の直列詠唱・封頁解除・反撃窓を5tickの共通時計で進めます。
 * HP、ダメージ、挑戦参加者、報酬の正本は既存サービスに置き、遅延taskを作りません。
 */
final class SeleneaEncounterController {
    static final String BOSS_ID = "selenea_eclipse_witch";
    private static final String GUARD = "selenea_sealed_grimoire";
    private static final String EXPOSURE = "selenea_unbound_grimoire";
    private static final int MAX_POINTS = 144;
    private static final int MAX_DISPLAYS = 16;
    private static final double RUNE_RADIUS = 1.7D;
    private static final long RUNE_HOLD = 30L;
    private static final Stage[] FIRST = {Stage.FORK, Stage.MARKS, Stage.INNER, Stage.SEALS};
    private static final Stage[] SECOND = {Stage.FORK, Stage.PASSAGE, Stage.MARKS, Stage.INNER, Stage.SEALS};
    private static final Stage[] FINAL = {Stage.FORK, Stage.MARKS, Stage.PASSAGE, Stage.INNER, Stage.SEALS};

    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hitHandler;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private @Nullable TemporarySkillEffectService effects;

    /**
     * @param mobs 実体位置を同期する共通サービス
     * @param particles 設定と閲覧者を考慮する共通演出サービス
     * @param participants 生存する確定参加者を返す関数
     * @param hitHandler 共通魔法ダメージへの委譲先
     */
    SeleneaEncounterController(MobService mobs, ParticleDisplayService particles,
        Function<MobInstance, List<Player>> participants, HitHandler hitHandler) {
        this.mobs = mobs; this.particles = particles; this.participants = participants; this.hitHandler = hitHandler;
    }

    /** @param service 専用の防護・反撃倍率を適用するサービス */
    void setTemporarySkillEffectService(@Nullable TemporarySkillEffectService service) { effects = service; }

    /**
     * 生存個体の予兆を同期処理で進めます。HP境界は一段ずつ完了まで保持します。
     * @param boss 操作可能な魔女
     * @param entity 有効な同一worldの実体
     * @param tick 単調増加する5tick時計
     */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        try {
            List<Player> active = active(boss);
            if (active.isEmpty() || entity.getWorld() != state.center.getWorld() || !SeleneaArena.loaded(state.center)) {
                cancelCast(boss.instanceId());
                return;
            }
            if (!SeleneaArena.inArena(entity.getLocation(), state.center)) {
                cancelCast(boss.instanceId());
                Location floor = SeleneaArena.ground(state.center);
                if (floor != null) mobs.resetPosition(boss, floor);
                state.nextAt = tick + 40L;
                return;
            }
            state.requestedPhase = Math.max(state.requestedPhase, phase(boss));
            if (tick % 20L == 0L) {
                List<Location> boundary = new ArrayList<>(48);
                ring(state.center, SeleneaArena.RADIUS, 48, boundary);
                particles.spawnForNearbyViewers(state.center, boundary, SharedParticleDefinitions.SELENEA_BOUNDARY);
            }
            if (state.cast != null) { advance(state, entity, active, tick); return; }
            if (tick < state.nextAt) return;
            if (!state.introduced) { begin(state, Stage.INTRO, tick, null); return; }
            if (tick >= state.nextBoundaryAt && active.stream().anyMatch(p -> !SeleneaArena.inArena(p.getLocation(), state.center))) {
                if (begin(state, Stage.RETURN, tick, null)) state.nextBoundaryAt = tick + 240L;
                return;
            }
            if (inside(active, state.center).isEmpty()) return;
            if (state.followUp != null) {
                if (begin(state, state.followUp, tick, state.followDirection)) {
                    state.followUp = null;
                    state.followDirection = null;
                }
                return;
            }
            if (state.completedPhase < state.requestedPhase) {
                state.pendingPhase = state.completedPhase + 1;
                if (begin(state, Stage.SEALS, tick, null)) announce(state, PlayerMsgId.P_7659, state.pendingPhase);
                return;
            }
            Stage[] rotation = state.completedPhase == 1 ? FIRST : state.completedPhase == 2 ? SECOND : FINAL;
            if (begin(state, rotation[state.actionIndex % rotation.length], tick, null)) state.actionIndex++;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6509, exception, boss.instanceId());
            cancelCast(boss.instanceId());
            state.nextAt = tick + 40L;
        }
    }

    /** @return 現在HPの65%・30%境界から算出した段階 */
    private static int phase(MobInstance boss) {
        if (boss.maxHealth() <= 0) return 1;
        return boss.currentHealth() <= boss.maxHealth() * 0.30D ? 3
            : boss.currentHealth() <= boss.maxHealth() * 0.65D ? 2 : 1;
    }

    /** @return 生存人数に応じた必要封頁数。ソロでも1枚で成立 */
    static int sealCount(int activeCount) { return Math.clamp((activeCount + 1) / 2, 1, 3); }

    /**
     * 詠唱を全時間付きで開始します。退避不能な攻撃・到達不能な封頁は予約しません。
     * @return 正常に開始した場合true。地形不備では一呼吸置いて再試行
     */
    private boolean begin(Encounter state, Stage stage, long tick, @Nullable Vector inherited) {
        List<Player> active = active(state.boss);
        List<Player> targets = stage == Stage.RETURN || stage == Stage.INTRO ? active : inside(active, state.center);
        if (targets.isEmpty()) return false;
        List<Player> ordered = new ArrayList<>(targets);
        ordered.sort(Comparator.comparing(Player::getUniqueId));
        Player primary = ordered.get(Math.floorMod(state.targetCursor, ordered.size()));
        Vector aim = inherited == null ? SeleneaArena.direction(state.center, primary.getLocation()) : inherited.clone();
        List<Location> marks = new ArrayList<>();
        if (stage == Stage.MARKS) {
            int count = Math.min(3, sealCount(ordered.size()));
            for (int i = 0; i < count; i++) {
                Player player = ordered.get(Math.floorMod(state.targetCursor + i, ordered.size()));
                Location mark = SeleneaArena.ground(player.getLocation());
                if (mark != null) marks.add(mark);
            }
            if (marks.isEmpty()) return retry(state, tick);
        }
        Cast cast = new Cast(stage, tick, state.center, aim, marks);
        if (stage.shape != SeleneaArena.Shape.NONE) {
            for (Player player : targets) {
                Location safe = SeleneaArena.safeSpot(player.getLocation(), state.center, stage.shape, aim, marks, stage.duration);
                if (safe == null) return retry(state, tick);
                cast.safe.add(safe);
            }
        }
        if (stage == Stage.SEALS && !prepareRunes(cast, ordered)) return retry(state, tick);
        state.cast = cast; // 生成途中の例外もこのcastが所有する表示を回収する。
        state.boss.scriptedAction(true);
        mobs.stopPathfinding(state.boss);
        cast.bar = Bukkit.createBossBar(stage == Stage.SEALS
                ? PlayerMsgResource.format(PlayerMsgId.P_7656.getId(), 0, cast.required, 0)
                : PlayerMsgResource.format(stage.message.getId()),
            stage == Stage.SEALS || stage == Stage.EXPOSURE ? BarColor.BLUE : BarColor.PURPLE, BarStyle.SEGMENTED_10);
        buildVisuals(cast);
        if (stage == Stage.SEALS) announce(state, PlayerMsgId.P_7660, cast.required);
        if (stage == Stage.INTRO || stage == Stage.EXPOSURE) announce(state, stage.message);
        if (stage == Stage.MARKS || stage == Stage.FORK) state.targetCursor++;
        updateEffects(state);
        updateBar(state, active, tick);
        draw(cast);
        sound(state.center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8F);
        return true;
    }

    /** 地形が整うまで無害に待ち、毎tickの床走査を防ぎます。 */
    private static boolean retry(Encounter state, long tick) { state.nextAt = tick + 40L; return false; }

    /** 1～3枚の封頁を歩いて届く床に配置し、同時解除と一人での順次解除を両立します。 */
    private static boolean prepareRunes(Cast cast, List<Player> players) {
        cast.required = sealCount(players.size());
        for (int i = 0; i < 12 && cast.runes.size() < cast.required; i++) {
            double angle = i * Math.PI * 5.0D / 6.0D;
            Location point = SeleneaArena.ground(cast.center.clone().add(Math.cos(angle) * 5.5D, 0, Math.sin(angle) * 5.5D));
            if (point == null || cast.runes.stream().anyMatch(r -> SeleneaArena.squared(r.point, point) < 16.0D)) continue;
            boolean reachable = players.stream().anyMatch(p -> {
                Location feet = SeleneaArena.ground(p.getLocation());
                return feet != null && SeleneaArena.walkablePath(feet, point);
            });
            if (reachable) cast.runes.add(new Rune(point));
        }
        return cast.runes.size() == cast.required;
    }

    /** 予兆・操作を進め、同じcastの着弾を一度だけ確定します。 */
    private void advance(Encounter state, Entity entity, List<Player> active, long tick) {
        Cast cast = state.cast;
        updateEffects(state);
        updateBar(state, active, tick);
        animate(cast, tick);
        if ((tick - cast.started) % 10L == 0L) draw(cast);
        if (cast.stage == Stage.SEALS) {
            if (advanceRunes(state, cast, active, tick)) { finishRitual(state, tick, true, cast.direction); return; }
        }
        if (tick < cast.ends) return;
        if (cast.stage.shape != SeleneaArena.Shape.NONE) {
            particles.spawnForNearbyViewers(cast.center, cast.danger, SharedParticleDefinitions.SELENEA_IMPACT);
            sound(cast.center, Sound.ENTITY_EVOKER_CAST_SPELL, 0.7F);
            for (Player player : List.copyOf(active)) {
                if (!contains(cast, player.getLocation())) continue;
                Location origin = cast.stage == Stage.MARKS ? cast.marks.stream()
                    .min(Comparator.comparingDouble(mark -> SeleneaArena.squared(mark, player.getLocation())))
                    .orElse(cast.center) : cast.center;
                if (!SeleneaArena.visible(origin.clone().add(0, 1.0D, 0), player.getEyeLocation())) continue;
                hitHandler.hit(state.boss, player, cast.stage.ratio);
                if (state.cast != cast || state.boss.currentHealth() <= 0 || !entity.isValid()) return;
            }
        }
        if (cast.stage == Stage.SEALS) { finishRitual(state, tick, false, cast.direction); return; }
        Stage previous = cast.stage;
        Vector direction = cast.direction.clone();
        close(state);
        if (previous == Stage.INTRO) state.introduced = true;
        // 成功反撃窓はHP境界を飛び越えても最後まで保証する。
        if (previous == Stage.EXPOSURE || previous == Stage.ECLIPSE) completePhase(state);
        Stage follow = switch (previous) {
            case INNER -> Stage.OUTER;
            case FORK -> state.completedPhase == 3 ? Stage.ECHO : Stage.RECOVERY;
            case PASSAGE -> state.completedPhase == 3 ? Stage.TURN_PAGE : Stage.RECOVERY;
            default -> Stage.RECOVERY;
        };
        if (previous == Stage.ECHO || previous == Stage.TURN_PAGE) follow = Stage.RECOVERY;
        if (previous == Stage.RECOVERY) { state.nextAt = tick; return; }
        if (follow == Stage.ECHO || follow == Stage.TURN_PAGE) direction.rotateAroundY(Math.PI / 2.0D);
        if (!begin(state, follow, tick, direction) && (follow != Stage.RECOVERY || state.followUp == null)) {
            state.followUp = follow;
            state.followDirection = direction;
        }
    }

    /**
     * 保持時間は個人ごとに計測し、解除済み枚数を残します。減員では必要残数を減らします。
     * @return 必要枚数が揃った場合true
     */
    private boolean advanceRunes(Encounter state, Cast cast, List<Player> active, long tick) {
        List<Player> available = inside(active, cast.center);
        if (available.isEmpty()) return false;
        int done = (int) cast.runes.stream().filter(r -> r.done).count();
        cast.required = Math.min(cast.required, done + sealCount(available.size()));
        Set<UUID> assigned = new HashSet<>();
        for (Rune rune : cast.runes) {
            if (rune.done) continue;
            Set<UUID> present = new HashSet<>();
            for (Player player : available) {
                UUID id = player.getUniqueId();
                if (assigned.contains(id) || !player.isSneaking()
                    || SeleneaArena.squared(player.getLocation(), rune.point) > RUNE_RADIUS * RUNE_RADIUS
                    || !SeleneaArena.sameFloor(player.getLocation(), rune.point)
                    || !SeleneaArena.visible(rune.point.clone().add(0, 0.5D, 0), player.getEyeLocation())) continue;
                assigned.add(id);
                present.add(id);
                long start = rune.holders.computeIfAbsent(id, ignored -> tick);
                if (tick - start >= RUNE_HOLD) {
                    rune.done = true;
                    rune.holders.clear();
                    if (rune.display != null) rune.display.setBlock(Material.SEA_LANTERN.createBlockData());
                    sound(rune.point, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.6F);
                    break;
                }
            }
            rune.holders.keySet().retainAll(present);
        }
        return cast.runes.stream().filter(r -> r.done).count() >= cast.required;
    }

    /** 儀式を解いた場合は5秒の反撃窓、失敗時は完全な予兆を持つ回避可能な月蝕へ進みます。 */
    private void finishRitual(Encounter state, long tick, boolean success, Vector direction) {
        close(state);
        Stage result = success ? Stage.EXPOSURE : Stage.ECLIPSE;
        if (!begin(state, result, tick, direction)) {
            // 解除成否を失わず、結果の予兆または反撃窓を再試行する。
            state.followUp = result;
            state.followDirection = direction.clone();
            state.nextAt = tick + 40L;
        }
    }

    /** 実際に完了した境界だけを記録し、次の未完了段階を温存します。 */
    private static void completePhase(Encounter state) {
        if (state.pendingPhase > 0) {
            state.completedPhase = state.pendingPhase;
            state.pendingPhase = 0;
            state.actionIndex = 0;
        }
    }

    /** 実時間期限の共有倍率をcastのtick時計に同期し、別ボスや別効果へ影響させません。 */
    private void updateEffects(Encounter state) {
        if (effects == null || state.cast == null) return;
        if (state.cast.stage == Stage.SEALS) effects.apply(state.boss.instanceId(), GUARD, 40L, 0.35D, 1, 1);
        if (state.cast.stage == Stage.EXPOSURE) effects.apply(state.boss.instanceId(), EXPOSURE, 40L, 1.45D, 1, 1);
    }

    /** CC・対象喪失では現在の予兆と待機中の次波を解除し、HP境界の進行記録を維持します。 */
    void cancelCast(@NotNull UUID id) {
        Encounter state = encounters.get(id);
        if (state != null) {
            close(state);
            state.followUp = null;
            state.followDirection = null;
            state.nextAt = 0L;
        }
    }

    /** @param id 死亡、帰還、破棄したボスのUUID */
    void clear(@NotNull UUID id) { cancelCast(id); encounters.remove(id); }

    /** 停止時に全個体の演出・倍率・行動ロックを回収します。 */
    void clearAll() { for (UUID id : List.copyOf(encounters.keySet())) clear(id); }

    /** @param liveIds 同期走査に残っている生存個体 */
    void retain(@NotNull Set<UUID> liveIds) {
        for (UUID id : List.copyOf(encounters.keySet())) if (!liveIds.contains(id)) clear(id);
    }

    /** castを先に無効化して再入を防ぎ、所有表示と専用効果だけを解除します。 */
    private void close(Encounter state) {
        Cast cast = state.cast;
        state.cast = null;
        state.boss.scriptedAction(false);
        if (effects != null) { effects.clear(state.boss.instanceId(), GUARD); effects.clear(state.boss.instanceId(), EXPOSURE); }
        if (cast == null) return;
        if (cast.bar != null) cast.bar.removeAll();
        for (Entity display : cast.displays) display.remove();
        cast.displays.clear();
        for (Rune rune : cast.runes) rune.holders.clear();
    }

    /** @return 同じworld・階・32m以内の戦闘参加者（最大6人） */
    private List<Player> active(MobInstance boss) {
        return participants.apply(boss).stream().filter(p -> p.isOnline() && !p.isDead())
            .filter(p -> SeleneaArena.sameFloor(p.getLocation(), boss.spawnLocation()))
            .filter(p -> SeleneaArena.squared(p.getLocation(), boss.spawnLocation()) <= 1024.0D)
            .sorted(Comparator.comparing(Player::getUniqueId)).limit(6).toList();
    }

    /** @return 通常技と解除操作を行える円内の参加者 */
    private static List<Player> inside(List<Player> players, Location center) {
        return players.stream().filter(p -> SeleneaArena.inArena(p.getLocation(), center)).toList();
    }

    /** @return 凍結済み形状の危険面に入っている場合true */
    private static boolean contains(Cast cast, Location point) {
        return SeleneaArena.contains(cast.stage.shape, cast.center, cast.direction, cast.marks, point);
    }

    /** 危険面の有限点群と本、封頁の表示を生成します。実体ブロックは変更しません。 */
    private static void buildVisuals(Cast cast) {
        List<Location> candidates = new ArrayList<>();
        double radius = cast.stage == Stage.RETURN ? SeleneaArena.ENGAGEMENT_RADIUS : SeleneaArena.RADIUS;
        if (cast.stage.shape != SeleneaArena.Shape.NONE) {
            List<Location> outline = new ArrayList<>();
            if (cast.stage.shape == SeleneaArena.Shape.FORK) {
                for (int ray = -1; ray <= 1; ray++) {
                    Vector aim = cast.direction.clone().rotateAroundY(ray * Math.PI / 6.0D);
                    Vector side = new Vector(-aim.getZ(), 0, aim.getX());
                    for (int step = 0; step <= 12; step++) {
                        for (double edge : new double[]{-1.24D, 1.24D}) {
                            outline.add(cast.center.clone().add(aim.clone().multiply(step * 1.4D))
                                .add(side.clone().multiply(edge)));
                        }
                    }
                }
            } else if (cast.stage == Stage.INNER || cast.stage == Stage.OUTER) {
                ring(cast.center, cast.stage == Stage.INNER ? 5.95D : 6.05D, 48, outline);
            }
            for (Location point : outline) {
                Location floor = SeleneaArena.ground(point);
                if (floor != null && contains(cast, floor)) candidates.add(floor.add(0, 0.12D, 0));
            }
            for (double x = -radius; x <= radius; x += 2.0D) {
                for (double z = -radius; z <= radius; z += 2.0D) {
                    Location point = SeleneaArena.ground(cast.center.clone().add(x, 0, z));
                    if (point != null && contains(cast, point)) candidates.add(point.add(0, 0.12D, 0));
                }
            }
            // 落款は床グリッドに加えて正確な円周を表示する。
            if (cast.stage == Stage.MARKS) for (Location mark : cast.marks) ring(mark, SeleneaArena.MARK_RADIUS, 24, candidates);
        }
        int count = Math.min(MAX_POINTS, candidates.size());
        for (int i = 0; i < count; i++) cast.danger.add(candidates.get(i * candidates.size() / count));
        int passageMarkers = cast.stage.shape == SeleneaArena.Shape.PASSAGE ? 4 : 0;
        int displays = Math.min(MAX_DISPLAYS - 4 - passageMarkers, cast.danger.size());
        for (int i = 0; i < displays; i++) {
            Location point = cast.danger.get(i * cast.danger.size() / displays);
            BlockDisplay display = block(cast, point.clone().add(0, 0.3D, 0), Material.AMETHYST_BLOCK, 0.24F);
            cast.shards.add(new Shard(display, point));
        }
        if (passageMarkers > 0) {
            Vector side = new Vector(-cast.direction.getZ(), 0, cast.direction.getX());
            for (double along : new double[]{-12.0D, 12.0D}) {
                for (double across : new double[]{-2.8D, 2.8D}) {
                    Location point = SeleneaArena.ground(cast.center.clone().add(cast.direction.clone().multiply(along))
                        .add(side.clone().multiply(across)));
                    if (point != null) block(cast, point.clone().add(0, 0.15D, 0), Material.SEA_LANTERN, 0.5F);
                }
            }
        }
        for (Rune rune : cast.runes) rune.display = block(cast, rune.point.clone().add(0, 0.2D, 0), Material.ENCHANTING_TABLE, 0.6F);
        for (int i = 0; i < 3; i++) {
            ItemDisplay book = cast.center.getWorld().spawn(cast.center.clone().add(0, 2.6D, 0), ItemDisplay.class);
            cast.displays.add(book);
            book.setPersistent(false);
            book.setGravity(false);
            book.setInvulnerable(true);
            book.setItemStack(new ItemStack(Material.ENCHANTED_BOOK));
            book.setBrightness(new Display.Brightness(15, 15));
            book.setTeleportDuration(5);
            book.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.7F), new Quaternionf()));
            cast.books.add(book);
        }
    }

    /** 所有権登録を最初に行い、生成途中でも回収できる表示を作ります。 */
    private static BlockDisplay block(Cast cast, Location point, Material material, float scale) {
        BlockDisplay display = point.getWorld().spawn(point, BlockDisplay.class);
        cast.displays.add(display);
        display.setPersistent(false);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setBlock(material.createBlockData());
        display.setBrightness(new Display.Brightness(15, 15));
        display.setTeleportDuration(5);
        display.setTransformation(new Transformation(new Vector3f(-scale / 2, 0, -scale / 2),
            new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        return display;
    }

    /** 共有5tick時計で本を周回させ、着弾直前だけ呪片を落とします。 */
    private static void animate(Cast cast, long tick) {
        for (int i = 0; i < cast.books.size(); i++) {
            double angle = (tick - cast.started) * 0.025D + i * Math.PI * 2 / 3;
            Location point = cast.center.clone().add(Math.cos(angle) * 1.5D,
                2.4D + Math.sin(angle * 2) * 0.25D, Math.sin(angle) * 1.5D);
            if (SeleneaArena.loaded(point)) cast.books.get(i).teleport(point);
        }
        double progress = Math.clamp((tick + 5 - (cast.ends - 20)) / 20.0D, 0, 1);
        for (Shard shard : cast.shards) {
            if (shard.display.isValid() && SeleneaArena.loaded(shard.point)) {
                shard.display.teleport(shard.point.clone().add(0, 1.2D * (1 - progress), 0));
            }
        }
    }

    /** 10tickごとに危険面・白い退避輪・封頁進捗を点群単位で描画します。 */
    private void draw(Cast cast) {
        particles.spawnForNearbyViewers(cast.center, cast.danger, SharedParticleDefinitions.BOSS_ATTACK_WARNING);
        List<Location> safe = new ArrayList<>();
        for (Location point : cast.safe) ring(point, 0.65D, 12, safe);
        if (cast.stage.shape == SeleneaArena.Shape.PASSAGE) {
            Vector side = new Vector(-cast.direction.getZ(), 0, cast.direction.getX());
            for (int i = -8; i <= 8; i++) {
                for (double edge : new double[]{-2.9D, 2.9D}) {
                    Location point = SeleneaArena.ground(cast.center.clone().add(cast.direction.clone().multiply(i * 2))
                        .add(side.clone().multiply(edge)));
                    if (point != null) safe.add(point.add(0, 0.15D, 0));
                }
            }
        }
        for (Rune rune : cast.runes) {
            ring(rune.point, RUNE_RADIUS, 20, safe);
            if (!rune.done) safe.add(rune.point.clone().add(0, 1.2D, 0));
        }
        BossParticleEffect.safeArea(particles, cast.center, safe);
    }

    /** 残時間、解除枚数、個人の継続保持進捗を参加者のBossBarへ反映します。 */
    private void updateBar(Encounter state, List<Player> active, long tick) {
        Cast cast = state.cast;
        if (cast.bar == null) return;
        cast.bar.setProgress(Math.clamp((cast.ends - tick) / (double) cast.stage.duration, 0, 1));
        if (cast.stage == Stage.SEALS) {
            long done = cast.runes.stream().filter(r -> r.done).count();
            long held = cast.runes.stream().flatMap(r -> r.holders.values().stream())
                .mapToLong(start -> tick - start).max().orElse(0L);
            cast.bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_7656.getId(), done, cast.required,
                Math.min(100, held * 100 / RUNE_HOLD)));
        }
        Set<UUID> ids = new HashSet<>();
        for (Player player : active) { ids.add(player.getUniqueId()); if (!cast.bar.getPlayers().contains(player)) cast.bar.addPlayer(player); }
        for (Player player : List.copyOf(cast.bar.getPlayers())) if (!ids.contains(player.getUniqueId())) cast.bar.removePlayer(player);
    }

    /** @param state 送信先の個体 @param id 共通文言ID @param args 置換値 */
    private void announce(Encounter state, PlayerMsgId id, Object... args) {
        for (Player player : active(state.boss)) PlayerMessageService.getInstance().send(player, id, args);
    }

    /** @param center 輪の中心 @param radius 半径 @param count 有限点数 @param out 出力先 */
    private static void ring(Location center, double radius, int count, List<Location> out) {
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius);
            if (SeleneaArena.loaded(point)) out.add(point);
        }
    }

    /** 同じ会場内だけへ技の節目の音を送ります。 */
    private static void sound(Location point, Sound sound, float pitch) { point.getWorld().playSound(point, sound, 0.85F, pitch); }

    /** 共通RPGダメージへの委譲。 */
    @FunctionalInterface interface HitHandler {
        /** @param boss 攻撃者 @param player 確定参加者 @param ratio 魔法攻撃倍率 */
        void hit(MobInstance boss, Player player, double ratio);
    }

    /** 技の予兆時間、倍率、形状、操作案内。 */
    private enum Stage {
        INTRO(60, 0, SeleneaArena.Shape.NONE, PlayerMsgId.P_7650),
        FORK(60, 0.75D, SeleneaArena.Shape.FORK, PlayerMsgId.P_7651),
        ECHO(60, 0.75D, SeleneaArena.Shape.FORK, PlayerMsgId.P_7661),
        MARKS(65, 0.80D, SeleneaArena.Shape.MARKS, PlayerMsgId.P_7652),
        INNER(60, 0.85D, SeleneaArena.Shape.INNER, PlayerMsgId.P_7653),
        OUTER(100, 0.85D, SeleneaArena.Shape.OUTER, PlayerMsgId.P_7654),
        PASSAGE(110, 0.90D, SeleneaArena.Shape.PASSAGE, PlayerMsgId.P_7655),
        TURN_PAGE(110, 0.90D, SeleneaArena.Shape.PASSAGE, PlayerMsgId.P_7662),
        SEALS(280, 0, SeleneaArena.Shape.NONE, PlayerMsgId.P_7660),
        EXPOSURE(100, 0, SeleneaArena.Shape.NONE, PlayerMsgId.P_7657),
        ECLIPSE(120, 1.10D, SeleneaArena.Shape.PASSAGE, PlayerMsgId.P_7658),
        RECOVERY(30, 0, SeleneaArena.Shape.NONE, PlayerMsgId.P_7663),
        RETURN(120, 0.9D, SeleneaArena.Shape.RETURN, PlayerMsgId.P_7664);
        final long duration;
        final double ratio;
        final SeleneaArena.Shape shape;
        final PlayerMsgId message;
        /** @param duration tick時間 @param ratio 倍率 @param shape 危険形状 @param message 文言 */
        Stage(long duration, double ratio, SeleneaArena.Shape shape, PlayerMsgId message) {
            this.duration = duration; this.ratio = ratio; this.shape = shape; this.message = message;
        }
    }

    /** 一個体だけに属する進行状態。 */
    private static final class Encounter {
        final MobInstance boss;
        final Location center;
        int completedPhase = 1, requestedPhase = 1, pendingPhase, actionIndex, targetCursor;
        long nextAt, nextBoundaryAt;
        boolean introduced;
        @Nullable Cast cast;
        @Nullable Stage followUp;
        @Nullable Vector followDirection;
        /** @param boss 所有ボス */
        Encounter(MobInstance boss) { this.boss = boss; this.center = boss.spawnLocation().clone(); }
    }

    /** 単一詠唱の固定情報と所有する演出。 */
    private static final class Cast {
        final Stage stage;
        final long started, ends;
        final Location center;
        final Vector direction;
        final List<Location> marks, safe = new ArrayList<>(), danger = new ArrayList<>();
        final List<Entity> displays = new ArrayList<>();
        final List<Shard> shards = new ArrayList<>();
        final List<ItemDisplay> books = new ArrayList<>();
        final List<Rune> runes = new ArrayList<>();
        @Nullable BossBar bar;
        int required;
        /** @param stage 技 @param tick 開始 @param center 中心 @param direction 固定方向 @param marks 固定落款 */
        Cast(Stage stage, long tick, Location center, Vector direction, List<Location> marks) {
            this.stage = stage; started = tick; ends = tick + stage.duration;
            this.center = center.clone(); this.direction = direction.clone(); this.marks = List.copyOf(marks);
        }
    }

    /** 一枚の封頁。保持時間は個人間で合算しません。 */
    private static final class Rune {
        final Location point;
        final Map<UUID, Long> holders = new HashMap<>();
        @Nullable BlockDisplay display;
        boolean done;
        /** @param point 到達可能な床 */
        Rune(Location point) { this.point = point; }
    }

    /** 落下する呪片と固定された着弾位置。 */
    private record Shard(BlockDisplay display, Location point) { }
}
