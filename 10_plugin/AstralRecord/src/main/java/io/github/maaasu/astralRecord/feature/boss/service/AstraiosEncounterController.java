package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** アストライオスの観測・無敵中の移動連鎖・反撃時間を個体単位で管理します。 */
final class AstraiosEncounterController {
    static final String BOSS_ID = "astraios_star_warden";
    private static final double[] BOUNDARIES = {0.75D, 0.50D, 0.25D};
    private static final long RECOVERY_TICKS = 200L;
    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hit;
    private final Map<UUID, Encounter> encounters = new HashMap<>();

    /** @param mobs 実体と無敵表示の管理 @param particles 共通の粒子描画 @param participants 確定参加者 @param hit 共通戦闘への委譲 */
    AstraiosEncounterController(MobService mobs, ParticleDisplayService particles,
                               Function<MobInstance, List<Player>> participants, HitHandler hit) {
        this.mobs = mobs;
        this.particles = particles;
        this.participants = participants;
        this.hit = hit;
    }

    /**
     * 一撃・固定HPダメージでも未完了の大技を飛ばさないHP下限を返します。
     * @param boss 被弾Mob @return 対象外・全大技完了・会場不備時は0
     */
    double healthFloor(MobInstance boss) {
        if (!BOSS_ID.equals(boss.template().id())) return 0;
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        return state.disabled || state.completed >= BOUNDARIES.length ? 0 : boss.maxHealth() * BOUNDARIES[state.completed];
    }

    /** @return 次の5tickを待たず、HP境界到達から大技完了まで攻撃を遮断する場合true */
    boolean protectedNow(MobInstance boss) {
        if (!BOSS_ID.equals(boss.template().id()) || boss.currentHealth() <= 0) return false;
        double floor = healthFloor(boss);
        Encounter state = encounters.get(boss.instanceId());
        return !state.disabled && (state.ritual || floor > 0 && boss.currentHealth() <= floor + 0.001D);
    }

    /**
     * 同期5tick周期で進めます。始まった大技はCC中も進め、期限切れの無敵を残しません。
     * @param boss 生存個体 @param entity Bukkit実体 @param tick 単調時計 @param canAct 通常攻撃を開始できる状態か
     * @param persistentEncounter 挑戦・ダンジョンの復帰待ち中も通常AIを止めてHPを保持する場合true
     */
    void tick(MobInstance boss, Entity entity, long tick, boolean canAct, boolean persistentEncounter) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        if (state.disabled) return;
        List<Player> active = List.of();
        try {
            active = participants.apply(boss);
            if (active.isEmpty()) {
                closeCast(state);
                release(state);
                boss.scriptedAction(persistentEncounter || state.originalScripted);
                state.plan = List.of();
                state.nextAt = tick + 40;
                return;
            }
            // 専用行動が通常AIの帰還・通常攻撃と競合しないよう、反撃時間も静止する。
            boss.scriptedAction(true);
            mobs.stopPathfinding(boss);
            if (!state.introduced) {
                announce(active, PlayerMsgId.P_8000);
                state.introduced = true;
                state.nextAt = tick + 80;
            }
            if (tick % 20 == 0) {
                for (Player player : active) {
                    if (!AstraiosArena.contains(player.getLocation(), state.center)) hit.hit(boss, player, 0.35D);
                    if (encounters.get(boss.instanceId()) != state) return;
                }
            }
            if (!state.ritual && protectedNow(boss)) {
                closeCast(state);
                startRitual(state, active, tick, true);
                return;
            }
            if (state.cast != null) {
                if (!state.ritual && !canAct) {
                    closeCast(state);
                    state.nextAt = tick + 40;
                } else advance(state, active, tick);
                return;
            }
            if (tick < state.nextAt) return;
            if (state.ritual) { beginWave(state, active, tick); return; }
            if (canAct) {
                if (state.actions > 0 && state.actions % 7 == 0 && state.lastCycle != state.actions) {
                    state.lastCycle = state.actions;
                    startRitual(state, active, tick, false);
                } else {
                    int rotationLength = state.completed == 0 ? 4 : state.completed == 1 ? 6 : 8;
                    AstraiosArena.Shape shape = switch (state.actions++ % rotationLength) {
                        case 0 -> AstraiosArena.Shape.CROSS;
                        case 1 -> AstraiosArena.Shape.MARKS;
                        case 2 -> AstraiosArena.Shape.INNER;
                        case 3 -> AstraiosArena.Shape.OUTER;
                        case 4 -> AstraiosArena.Shape.HORIZONTAL;
                        case 5 -> AstraiosArena.Shape.VERTICAL;
                        default -> AstraiosArena.Shape.QUADRANTS;
                    };
                    begin(state, new Step(shape, state.actions % 4), active, tick);
                }
            }
        } catch (RuntimeException failure) {
            Logger.log(LogId.E_6540, failure, boss.instanceId());
            disable(state, active);
        }
    }

    /** HP大技は4・8・10波、定期観測は2波。円・通路の切替で長距離移動を促します。 */
    private void startRitual(Encounter state, List<Player> active, long tick, boolean boundary) {
        state.boundary = boundary;
        state.wave = 0;
        int start = (state.completed + state.cycles++) % 4;
        List<Step> plan = new ArrayList<>();
        plan.add(new Step(AstraiosArena.Shape.REFUGE, start));
        plan.add(new Step(AstraiosArena.Shape.ALIGNMENT, start + (state.completed == 0 ? 2 : 1)));
        if (boundary && state.completed >= 1) {
            plan.add(new Step(AstraiosArena.Shape.REFUGE, start + 2));
            plan.add(new Step(AstraiosArena.Shape.REFUGE, start + 3));
            plan.add(new Step(AstraiosArena.Shape.HORIZONTAL, 0));
            plan.add(new Step(AstraiosArena.Shape.VERTICAL, 0));
        }
        if (boundary) {
            plan.add(new Step(AstraiosArena.Shape.INNER, 0));
            plan.add(new Step(AstraiosArena.Shape.OUTER, 0));
        }
        if (boundary && state.completed >= 2) {
            plan.add(new Step(AstraiosArena.Shape.QUADRANTS, 0));
            plan.add(new Step(AstraiosArena.Shape.QUADRANTS, 1));
        }
        state.plan = List.copyOf(plan);
        String route = plan.stream().map(AstraiosEncounterController::instruction).reduce((a, b) -> a + " → " + b).orElse("");
        state.ritual = true;
        state.originalImmune = state.boss.damageImmune();
        mobs.setDamageImmune(state.boss, true);
        if (boundary) announce(active, PlayerMsgId.P_8001, state.completed + 1, route);
        else announce(active, PlayerMsgId.P_8006, route);
        beginWave(state, active, tick);
    }

    /** 各波を完全な予告時間で開始し、最終波の判定後だけ無敵を解除します。 */
    private void beginWave(Encounter state, List<Player> active, long tick) {
        if (state.wave >= state.plan.size()) {
            if (state.boundary) state.completed++;
            release(state);
            state.plan = List.of();
            state.nextAt = tick + RECOVERY_TICKS;
            announce(active, PlayerMsgId.P_8003, RECOVERY_TICKS / 20);
            return;
        }
        begin(state, state.plan.get(state.wave), active, tick);
    }

    /**
     * 生存者全員について体幅と歩行経路を検査し、徒歩3m/s＋1.5秒で到達できる予告を固定します。
     * 会場不備は無敵とHP下限を解除して通知し、不可避攻撃を発生させません。
     */
    private void begin(Encounter state, Step step, List<Player> active, long tick) {
        List<Location> marks = step.shape == AstraiosArena.Shape.MARKS
            ? active.stream().filter(p -> AstraiosArena.contains(p.getLocation(), state.center))
                .map(Player::getLocation).toList() : List.of();
        AstraiosArena.Zone zone = new AstraiosArena.Zone(step.shape, state.center, step.direction, marks);
        double longest = 0;
        for (Player player : active) {
            if (!AstraiosArena.contains(player.getLocation(), state.center)) continue;
            double distance = AstraiosArena.escapeDistance(player.getLocation(), zone);
            if (!Double.isFinite(distance)) { disable(state, active); return; }
            longest = Math.max(longest, distance);
        }
        long duration = Math.max(state.ritual ? 90 : 50, (long) Math.ceil(longest / 3.0D * 20) + 30);
        if (step.shape == AstraiosArena.Shape.ALIGNMENT) duration += 30;
        if (duration > 380) { disable(state, active); return; }
        Cast cast = new Cast(zone, tick, duration);
        for (Player player : active) cast.eligible.add(player.getUniqueId());
        cast.required = Math.clamp((cast.eligible.size() + 1) / 2, 1, 3);
        state.cast = cast;
        cast.bar = Bukkit.createBossBar("", state.ritual ? BarColor.PURPLE : BarColor.YELLOW, BarStyle.SEGMENTED_10);
        buildVisuals(cast, instruction(step));
        updateBar(state, active, tick);
        announce(active, PlayerMsgId.P_8002, instruction(step), (duration + 19) / 20,
            state.ritual ? state.wave + 1 : 1, state.ritual ? state.plan.size() : 1);
        sound(state.center, Sound.BLOCK_BEACON_ACTIVATE, 1.2F);
    }

    /** 判定・描画を同じZoneで行い、途中の死亡・終了による再入後には処理を続けません。 */
    private void advance(Encounter state, List<Player> active, long tick) {
        Cast cast = state.cast;
        Set<UUID> present = new HashSet<>();
        for (Player player : active) {
            present.add(player.getUniqueId());
            if (!cast.eligible.contains(player.getUniqueId()) && cast.lateNotified.add(player.getUniqueId()))
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_8021);
        }
        cast.eligible.retainAll(present);
        boolean aligned = cast.zone.shape() == AstraiosArena.Shape.ALIGNMENT && align(cast, active, tick);
        updateBar(state, active, tick);
        if (tick % 10 == 0) draw(cast);
        long remaining = cast.ends - tick;
        if (remaining > 0 && remaining <= 60 && tick % 20 == 0)
            sound(state.center, Sound.BLOCK_NOTE_BLOCK_PLING, remaining <= 20 ? 1.6F : 1.0F);
        if (remaining > 0 && !aligned) return;
        if (aligned) {
            sound(state.center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4F);
        } else {
            List<Location> impacts = new ArrayList<>();
            for (int i = 0; i < cast.danger.size() && impacts.size() < 24; i += 12) impacts.add(cast.danger.get(i));
            particles.spawnForNearbyViewers(impacts, SharedParticleDefinitions.BOSS_DAMAGE_AREA_EXPLOSION, true);
            sound(state.center, Sound.ENTITY_GENERIC_EXPLODE, 0.7F);
        }
        for (Player player : active) {
            if (cast.eligible.contains(player.getUniqueId()) && !aligned
                && (cast.zone.shape() == AstraiosArena.Shape.ALIGNMENT || !cast.zone.safe(player.getLocation())))
                hit.hit(state.boss, player, cast.zone.shape() == AstraiosArena.Shape.ALIGNMENT ? 0.85D
                    : state.ritual ? 1.65D : 0.70D);
            if (encounters.get(state.boss.instanceId()) != state || state.cast != cast
                || mobs.getInstance(state.boss.instanceId()) != state.boss) return;
        }
        if (aligned) announce(active, PlayerMsgId.P_8024);
        closeCast(state);
        if (state.ritual) {
            state.wave++;
            // 次の波に無敵を引き継ぐが、前波の予兆や判定を引き継がない。
            state.nextAt = tick + 15;
        } else state.nextAt = tick + 35;
    }

    /**
     * 観測円に接地して30tickしゃがみ続けた個人だけを数えます。離脱や解除では保持を破棄します。
     * @return 生存する波開始者の半数（最大3人、ソロ1人）が保持を完了した場合true
     */
    private static boolean align(Cast cast, List<Player> active, long tick) {
        Set<UUID> holding = new HashSet<>();
        for (Player player : active) {
            UUID id = player.getUniqueId();
            Location floor = GreatrootArena.ground(player.getLocation());
            if (cast.eligible.contains(id) && player.isSneaking() && cast.zone.safe(player.getLocation())
                && floor != null && Math.abs(floor.getY() - player.getY()) < 0.12D) {
                holding.add(id);
                cast.holders.putIfAbsent(id, tick);
            }
        }
        cast.holders.keySet().retainAll(holding);
        cast.aligned = (int) cast.holders.values().stream().filter(start -> tick - start >= 30).count();
        cast.required = Math.clamp((cast.eligible.size() + 1) / 2, 1, 3);
        return cast.aligned >= cast.required;
    }

    /** 現在の退避指示、残秒、波数、次の行き先を粒子設定に依存しないBossBarへ表示します。 */
    private void updateBar(Encounter state, List<Player> active, long tick) {
        Cast cast = state.cast;
        if (cast.bar == null) return;
        String next = state.ritual && state.wave + 1 < state.plan.size()
            ? instruction(state.plan.get(state.wave + 1)) : PlayerMsgResource.getMessage(PlayerMsgId.P_8015.getId());
        cast.bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_8004.getId(),
            instruction(new Step(cast.zone.shape(), cast.zone.direction())),
            Math.max(0, (cast.ends - tick + 19) / 20), state.ritual ? state.wave + 1 : 1,
            state.ritual ? state.plan.size() : 1, next));
        cast.bar.setProgress(Math.clamp((cast.ends - tick) / (double) cast.duration, 0, 1));
        if (cast.zone.shape() == AstraiosArena.Shape.ALIGNMENT)
            cast.bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_8023.getId(),
                instruction(new Step(cast.zone.shape(), cast.zone.direction())), cast.aligned, cast.required,
                Math.max(0, (cast.ends - tick + 19) / 20)));
        Set<UUID> ids = new HashSet<>();
        for (Player player : active) {
            ids.add(player.getUniqueId());
            if (!cast.bar.getPlayers().contains(player)) cast.bar.addPlayer(player);
        }
        for (Player player : List.copyOf(cast.bar.getPlayers()))
            if (!ids.contains(player.getUniqueId())) cast.bar.removePlayer(player);
    }

    /** 有限の地表サンプルで安全面と危険面を同じ判定から描きます。 */
    private void draw(Cast cast) {
        particles.spawnForNearbyViewers(cast.danger, SharedParticleDefinitions.BOSS_ATTACK_WARNING, true);
        particles.spawnForNearbyViewers(cast.safe, SharedParticleDefinitions.CHARON_SAFE_LIGHT, true);
    }

    /**
     * 安全域は白灯、危険域は橙の小片で補強します。粒子OFFでも文字・実体表示を維持します。
     * @param cast 生成した表示を所有する詠唱 @param label 安全条件を説明する文言
     */
    private void buildVisuals(Cast cast, String label) {
        Location center = cast.zone.center();
        for (double x = -21; x <= 21; x += 3) {
            for (double z = -21; z <= 21; z += 3) {
                Location point = center.clone().add(x, 0.15D, z);
                if (!AstraiosArena.contains(point, center)) continue;
                (cast.zone.safe(point) ? cast.safe : cast.danger).add(point);
            }
        }
        List<Location> destinations = cast.zone.destinations();
        for (int i = 0; i < Math.min(8, destinations.size()); i++) {
            Location point = destinations.get(i * destinations.size() / Math.min(8, destinations.size()));
            block(cast, point.clone().add(0, 0.25D, 0), Material.SEA_LANTERN, 0.4F);
        }
        for (int i = 0; i < Math.min(12, cast.danger.size()); i++)
            block(cast, cast.danger.get(i * cast.danger.size() / Math.min(12, cast.danger.size()))
                .clone().add(0, 0.2D, 0), Material.ORANGE_STAINED_GLASS, 0.45F);
        Location labelAt = destinations.isEmpty() ? center : destinations.getFirst();
        TextDisplay text = center.getWorld().spawn(labelAt.clone().add(0, 2.5D, 0), TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setViewRange(1.5F);
            display.text(LegacyComponentSerializer.legacySection()
                .deserialize(PlayerMsgResource.format(PlayerMsgId.P_8016.getId(), label)));
            display.setShadowed(true);
        });
        cast.displays.add(text.getUniqueId());
    }

    /** @param cast 表示の所有者 @param point 表示位置 @param material 共有Material @param scale 一辺の長さ */
    private static void block(Cast cast, Location point, Material material, float scale) {
        BlockDisplay display = point.getWorld().spawn(point, BlockDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setViewRange(1.5F);
            entity.setBlock(material.createBlockData());
            entity.setTransformation(new Transformation(new Vector3f(-scale / 2, 0, -scale / 2),
                new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        });
        cast.displays.add(display.getUniqueId());
    }

    /** @return 方向は地理と一致する北(-Z)・東(+X)・南(+Z)・西(-X)。色だけに依存しません */
    private static String instruction(Step step) {
        PlayerMsgId id = switch (step.shape) {
            case REFUGE, ALIGNMENT -> switch (Math.floorMod(step.direction, 4)) {
                case 0 -> PlayerMsgId.P_8007;
                case 1 -> PlayerMsgId.P_8008;
                case 2 -> PlayerMsgId.P_8009;
                default -> PlayerMsgId.P_8010;
            };
            case INNER -> PlayerMsgId.P_8011;
            case OUTER -> PlayerMsgId.P_8012;
            case QUADRANTS -> step.direction % 2 == 0 ? PlayerMsgId.P_8013 : PlayerMsgId.P_8014;
            case CROSS -> PlayerMsgId.P_8017;
            case MARKS -> PlayerMsgId.P_8018;
            case HORIZONTAL -> PlayerMsgId.P_8019;
            case VERTICAL -> PlayerMsgId.P_8020;
        };
        String text = PlayerMsgResource.getMessage(id.getId());
        return step.shape == AstraiosArena.Shape.ALIGNMENT
            ? PlayerMsgResource.format(PlayerMsgId.P_8022.getId(), text) : text;
    }

    /** 会場異常を一度通知し、HP下限・専用行動・無敵・一時表示を全て解除します。 */
    private void disable(Encounter state, List<Player> active) {
        closeCast(state);
        release(state);
        state.boss.scriptedAction(false);
        state.disabled = true;
        announce(active, PlayerMsgId.P_8005);
    }

    /** 無敵は所有開始前の状態へ戻します。解除後も通常AIとの競合を防ぐ静止はtick側で維持します。 */
    private void release(Encounter state) {
        if (state.ritual) mobs.setDamageImmune(state.boss, state.originalImmune);
        state.ritual = false;
    }

    /** 先に参照を外して、再入・二重解除でも他の詠唱に触れないようにします。 */
    private static void closeCast(Encounter state) {
        Cast cast = state.cast;
        state.cast = null;
        if (cast == null) return;
        if (cast.bar != null) {
            try { cast.bar.removeAll(); }
            catch (RuntimeException failure) { Logger.log(LogId.E_6541, failure, state.boss.instanceId()); }
        }
        for (UUID id : cast.displays) {
            try {
                Entity display = Bukkit.getEntity(id);
                if (display != null) display.remove();
            } catch (RuntimeException failure) { Logger.log(LogId.E_6541, failure, state.boss.instanceId()); }
        }
        cast.displays.clear();
    }

    /** @param bossId 終了・消失・帰還した個体。別個体の状態には触れません */
    void clear(UUID bossId) {
        Encounter state = encounters.remove(bossId);
        if (state == null) return;
        closeCast(state);
        release(state);
        state.boss.scriptedAction(state.originalScripted);
    }

    /** @param active 現在存在する対象個体。取り残された表示も回収します */
    void retain(Set<UUID> active) {
        for (UUID id : List.copyOf(encounters.keySet())) if (!active.contains(id)) clear(id);
    }

    /** Plugin停止時に全個体の表示と無敵を解放します。 */
    void clearAll() { for (UUID id : List.copyOf(encounters.keySet())) clear(id); }

    /** @param active 確定参加者 @param id 共通文言 @param args 置換値 */
    private static void announce(List<Player> active, PlayerMsgId id, Object... args) {
        for (Player player : active) PlayerMessageService.getInstance().send(player, id, args);
    }

    /** 観測台の節目だけ、有限の音量で音を送ります。 */
    private static void sound(Location point, Sound sound, float pitch) {
        point.getWorld().playSound(point, sound, 1.8F, pitch);
    }

    @FunctionalInterface interface HitHandler {
        /** @param boss 攻撃元 @param player 生存する確定参加者 @param ratio 魔法攻撃倍率 */
        void hit(MobInstance boss, Player player, double ratio);
    }

    private record Step(AstraiosArena.Shape shape, int direction) { }

    private static final class Encounter {
        final MobInstance boss;
        final Location center;
        final boolean originalScripted;
        boolean originalImmune;
        boolean introduced;
        boolean ritual;
        boolean boundary;
        boolean disabled;
        int completed;
        int actions;
        int lastCycle;
        int cycles;
        int wave;
        long nextAt;
        List<Step> plan = List.of();
        Cast cast;

        /** @param boss 所有個体。既存の専用行動状態も終了時のため記録します */
        Encounter(MobInstance boss) {
            this.boss = boss;
            center = boss.spawnLocation().clone();
            originalScripted = boss.scriptedAction();
        }
    }

    private static final class Cast {
        final AstraiosArena.Zone zone;
        final long duration;
        final long ends;
        final List<Location> safe = new ArrayList<>();
        final List<Location> danger = new ArrayList<>();
        final List<UUID> displays = new ArrayList<>();
        final Set<UUID> eligible = new HashSet<>();
        final Set<UUID> lateNotified = new HashSet<>();
        final Map<UUID, Long> holders = new HashMap<>();
        int aligned;
        int required = 1;
        BossBar bar;

        /** @param zone 判定形状 @param tick 開始時計 @param duration 固定予告時間 */
        Cast(AstraiosArena.Zone zone, long tick, long duration) {
            this.zone = zone;
            this.duration = duration;
            ends = tick + duration;
        }
    }
}
