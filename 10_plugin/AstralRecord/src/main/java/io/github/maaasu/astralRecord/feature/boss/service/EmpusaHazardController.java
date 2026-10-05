package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 通常魔法を止めずに、地表を広がる跳躍波と召喚魔女の防御結界を同期進行します。 */
final class EmpusaHazardController {
    private static final String COVEN_ID = "empusa_coven_witch";
    private static final double WAVE_REACH = 22.0D;
    private static final double WAVE_SPEED = 0.28D;
    private static final double WAVE_HALF_WIDTH = 0.45D;
    private static final double JUMP_CLEARANCE = 0.65D;
    private static final long SUMMON_COOLDOWN = 600L;
    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<Location, List<Player>> targets;
    private final EmpusaSpellController.HitHandler hits;
    private final Map<UUID, Encounter> encounters = new HashMap<>();

    /**
     * 独立ギミックの依存を受け取ります。全操作はBukkit同期スレッドで実行します。
     * @param mobs 召喚・生存確認・破棄サービス
     * @param particles プレイヤーの表示設定を尊重する共通表示
     * @param targets 半径32以内の管理対象プレイヤー取得
     * @param hits ボスの魔法ダメージ適用
     */
    EmpusaHazardController(MobService mobs, ParticleDisplayService particles,
                          Function<Location, List<Player>> targets, EmpusaSpellController.HitHandler hits) {
        this.mobs = mobs;
        this.particles = particles;
        this.targets = targets;
        this.hits = hits;
    }

    /**
     * 5tick周期で召喚と波を通常魔法から独立して進めます。
     * @param boss 生存する親ボス
     * @param entity 親ボスの有効な実体
     * @param tick 共通tick
     * @param phase 完了済みのHP段階（1〜3）
     */
    void tick(MobInstance boss, Entity entity, long tick, int phase) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss, tick));
        pruneSummons(state, entity.getLocation());
        if (targets.apply(entity.getLocation()).isEmpty()) {
            suspend(boss.instanceId());
            return;
        }
        if (state.summons.isEmpty() && state.summonAt == 0L && tick >= state.nextSummonAt) {
            prepareSummons(state, entity.getLocation(), tick, phase);
        }
        if (state.summonAt > 0L) {
            if (tick >= state.summonAt) spawnSummons(state, tick);
            else drawSummonWarning(state, tick);
        }
        if (!state.summons.isEmpty()) drawWard(state, entity.getLocation());
        if (state.wave == null && tick >= state.nextWaveAt) {
            Location ground = EmpusaSpellController.groundNear(entity.getLocation());
            if (ground != null) state.wave = new Wave(ground, tick, phase);
            else state.nextWaveAt = tick + 20L;
        }
        if (state.wave != null) advanceWave(state, tick);
    }

    /**
     * 拘束・対象喪失時は攻撃と未発動召喚を取消し、既存魔女の生存確認は継続します。
     * @param bossId 中断する個体ID
     */
    void suspend(UUID bossId) {
        Encounter state = encounters.get(bossId);
        if (state == null) return;
        if (state.wave != null) state.wave.bar.removeAll();
        state.wave = null;
        state.nextWaveAt = 0L;
        state.summonAt = 0L;
        state.spawnPoints.clear();
        pruneSummons(state, state.boss.currentLocation());
        if (state.summons.isEmpty() && state.wardBar != null) {
            state.wardBar.removeAll();
            state.wardBar = null;
        }
    }

    /**
     * 親の破棄では全召喚と防御を回収し、魔女の破棄通知では親の結界を即時弱めます。
     * @param id 破棄または遭遇リセットされたMobのID
     */
    void clear(UUID id) {
        Encounter state = encounters.remove(id);
        if (state != null) {
            state.boss.defenseMultiplier(1.0D);
            if (state.wave != null) state.wave.bar.removeAll();
            if (state.wardBar != null) state.wardBar.removeAll();
            for (UUID summon : List.copyOf(state.summons)) mobs.destroy(summon);
            return;
        }
        for (Encounter owner : encounters.values()) {
            if (owner.summons.remove(id)) updateDefense(owner);
        }
    }

    /** 到達可能な床へ召喚円を先に表示し、ロード済みマスタがない場合は召喚を保留します。 */
    private void prepareSummons(Encounter state, Location origin, long tick, int phase) {
        state.nextSummonAt = tick + SUMMON_COOLDOWN;
        if (mobs.findLoadedTemplate(COVEN_ID) == null) {
            Logger.log(LogId.W_5701, COVEN_ID);
            return;
        }
        Location ground = EmpusaSpellController.groundNear(origin);
        if (ground == null) return;
        for (int index = 0; index < 24 && state.spawnPoints.size() < phase + 2; index++) {
            double angle = index * Math.PI / 6.0D;
            double radius = index < 12 ? 6.0D : 9.0D;
            Location candidate = EmpusaSpellController.groundNear(ground.clone().add(
                Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
            if (candidate != null && Math.abs(candidate.getY() - ground.getY()) <= 1.0D
                && EmpusaSpellController.walkablePath(ground, candidate)
                && EmpusaSpellController.clearSight(ground, candidate)
                && state.spawnPoints.stream().noneMatch(point -> distance(point, candidate) < 9.0D)) {
                state.spawnPoints.add(candidate);
            }
        }
        if (state.spawnPoints.isEmpty()) return;
        state.summonAt = tick + 40L;
        state.wardBar = Bukkit.createBossBar(PlayerMsgResource.format(PlayerMsgId.P_7630.getId()),
            BarColor.PURPLE, BarStyle.SEGMENTED_10);
        sound(ground, "entity.evoker.prepare_summon", 1.3F, 0.7F);
    }

    /** 各召喚円の輪郭を40tick間表示してから魔女を出現させます。 */
    private void drawSummonWarning(Encounter state, long tick) {
        List<Location> points = new ArrayList<>();
        for (Location center : state.spawnPoints) addRing(points, center, 1.2D, 16, 0.12D);
        particles.spawnForNearbyViewers(state.boss.currentLocation(), points, SharedParticleDefinitions.EMPUSA_HEX);
        updateBar(state.wardBar, state.boss.currentLocation(), (state.summonAt - tick) / 40.0D);
    }

    /** マスタを同期経路で遅延取得せず、予兆済み地点の魔女だけを最大5体生成します。 */
    private void spawnSummons(Encounter state, long tick) {
        state.summonAt = 0L;
        state.nextSummonAt = tick + SUMMON_COOLDOWN;
        MobTemplate template = mobs.findLoadedTemplate(COVEN_ID);
        for (Location point : List.copyOf(state.spawnPoints)) {
            if (template == null || EmpusaSpellController.groundNear(point) == null
                || !EmpusaSpellController.clearSight(state.boss.currentLocation(), point)) continue;
            MobInstance summoned = mobs.spawn(template, point);
            if (summoned == null) continue;
            state.summons.add(summoned.instanceId());
            summoned.rewardEligible(false);
            summoned.keepWhenUnobserved(true);
            summoned.targetId(state.boss.targetId());
            summoned.state(MobState.AGGRO);
            sound(point, "entity.witch.celebrate", 0.7F, 1.15F);
        }
        state.spawnPoints.clear();
        state.initialSummons = state.summons.size();
        updateDefense(state);
    }

    /** 死亡・無効実体・別world・離脱した召喚を除外し、空の結界を残しません。 */
    private void pruneSummons(Encounter state, Location origin) {
        for (UUID id : List.copyOf(state.summons)) {
            MobInstance summon = mobs.getInstance(id);
            Entity entity = summon == null ? null : mobs.entityController().getEntity(summon);
            if (summon == null || summon.currentHealth() <= 0.0D || entity == null
                || !entity.isValid() || entity.isDead() || entity.getWorld() != origin.getWorld()
                || distance(origin, entity.getLocation()) > 32.0D * 32.0D
                || summon.state() == MobState.LEASHED) {
                state.summons.remove(id);
                mobs.destroy(id);
            }
        }
        updateDefense(state);
    }

    /** 生存割合に応じ最大1.65倍の一時防御を適用し、全滅通知の時点で通常防御へ戻します。 */
    private void updateDefense(Encounter state) {
        double fraction = state.summons.size() / (double) Math.max(1, state.initialSummons);
        state.boss.defenseMultiplier(1.0D + 0.65D * fraction);
        if (state.wardBar == null || state.summonAt > 0L) return;
        if (state.summons.isEmpty()) {
            state.wardBar.removeAll();
            state.wardBar = null;
        } else {
            state.wardBar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_7629.getId(), state.summons.size()));
            updateBar(state.wardBar, state.boss.currentLocation(), fraction);
        }
    }

    /** 結界の軌道と生存魔女からの魔力線で、撃破すべき雑魚と防御の関係を示します。 */
    private void drawWard(Encounter state, Location origin) {
        List<Location> points = new ArrayList<>();
        addRing(points, origin, 1.8D, 12, 1.3D);
        for (UUID id : state.summons) {
            MobInstance summon = mobs.getInstance(id);
            if (summon == null) continue;
            Location source = summon.currentLocation();
            for (int index = 0; index < 8; index++) {
                double fraction = index / 8.0D;
                points.add(source.clone().add(origin.toVector().subtract(source.toVector()).multiply(fraction)).add(0, 1.4D, 0));
            }
        }
        particles.spawnForNearbyViewers(origin, points, SharedParticleDefinitions.EMPUSA_HEX);
    }

    /** 各波に独立した予兆を置き、広がる輪の通過区間を5tick間の移動量込みで判定します。 */
    private void advanceWave(Encounter state, long tick) {
        Wave wave = state.wave;
        if (wave == null) return;
        if (state.boss.currentLocation().getWorld() != wave.origin.getWorld()
            || distance(state.boss.currentLocation(), wave.origin) > 32.0D * 32.0D) {
            suspend(state.boss.instanceId());
            return;
        }
        if (tick < wave.releaseAt) {
            List<Location> warning = new ArrayList<>();
            addRing(warning, wave.origin, 2.0D + (tick % 10L) * 0.06D, 32, 0.12D);
            particles.spawnForNearbyViewers(wave.origin, warning, SharedParticleDefinitions.EMPUSA_FIRE);
            updateBar(wave.bar, wave.origin, (wave.releaseAt - tick) / (double) wave.warningTicks);
            return;
        }
        if (wave.previousRadius == 0.0D) sound(wave.origin, "entity.warden.sonic_boom", 1.0F, 0.65F);
        double radius = Math.min(WAVE_REACH, (tick - wave.releaseAt + 5L) * WAVE_SPEED);
        List<Location> ring = new ArrayList<>();
        int samples = Math.clamp((int) Math.ceil(2.0D * Math.PI * radius / 0.8D), 32, 96);
        for (int index = 0; index < samples; index++) {
            double angle = index * Math.PI * 2.0D / samples;
            Location point = waveGround(wave, wave.origin.clone().add(
                Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
            if (point != null) ring.add(point.add(0, 0.18D, 0));
        }
        particles.spawnForNearbyViewers(wave.origin, ring, SharedParticleDefinitions.EMPUSA_FIRE);
        updateBar(wave.bar, wave.origin, 1.0D - radius / WAVE_REACH);
        for (Player player : targets.apply(wave.origin)) {
            Location location = player.getLocation();
            double range = Math.sqrt(distance(wave.origin, location));
            Location ground = waveGround(wave, location);
            if (wave.hitPlayers.contains(player.getUniqueId()) || ground == null
                || range < Math.max(0.0D, wave.previousRadius - WAVE_HALF_WIDTH)
                || range > radius + WAVE_HALF_WIDTH || location.getY() < ground.getY() - 0.12D
                || location.getY() - ground.getY() > JUMP_CLEARANCE) continue;
            wave.hitPlayers.add(player.getUniqueId());
            try {
                hits.hit(state.boss, player, DamageElement.FIRE, 0.50D + wave.phase * 0.05D);
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_6505, exception, "ground_wave", player.getUniqueId());
            }
        }
        wave.previousRadius = radius;
        if (radius < WAVE_REACH) return;
        if (++wave.number < wave.count) {
            wave.warningTicks = 30L;
            wave.releaseAt = tick + wave.warningTicks;
            wave.previousRadius = 0.0D;
            wave.hitPlayers.clear();
            wave.bar.setTitle(waveTitle(wave.number, wave.count));
            sound(wave.origin, "block.bell.resonate", 1.0F, 0.9F);
        } else {
            wave.bar.removeAll();
            state.wave = null;
            state.nextWaveAt = tick + 180L;
        }
    }

    /**
     * 描画と被弾の両方で、波原点を基準に同じ歩行面と遮蔽条件を解決します。
     * @param wave 原点の高さを固定した波
     * @param horizontal 調べる水平座標。Playerの跳躍高度には依存しない
     * @return 原点から床高差2.5以内で見通せる床。候補なしならnull
     */
    private static @Nullable Location waveGround(Wave wave, Location horizontal) {
        Location probe = horizontal.clone();
        probe.setY(wave.origin.getY() + 1.5D);
        Location ground = EmpusaSpellController.groundNear(probe);
        if (ground == null) {
            probe.setY(wave.origin.getY() - 0.5D);
            ground = EmpusaSpellController.groundNear(probe);
        }
        return ground != null && Math.abs(ground.getY() - wave.origin.getY()) <= 2.5D
            && EmpusaSpellController.clearSight(wave.origin, ground) ? ground : null;
    }

    /** 読込済みの円周点だけを描画候補へ追加し、未ロードchunkを触りません。 */
    private static void addRing(List<Location> points, Location center, double radius, int count, double height) {
        for (int index = 0; index < count; index++) {
            double angle = index * Math.PI * 2.0D / count;
            Location point = center.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
            if (point.getWorld() != null && point.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)) {
                points.add(point);
            }
        }
    }

    /** 波・召喚・結界のBarをそれぞれの時計へ同期し、範囲外の閲覧者を外します。 */
    private void updateBar(@Nullable BossBar bar, Location origin, double progress) {
        if (bar == null) return;
        bar.setProgress(Math.clamp(progress, 0.0D, 1.0D));
        List<Player> viewers = targets.apply(origin);
        for (Player old : List.copyOf(bar.getPlayers())) if (!viewers.contains(old)) bar.removePlayer(old);
        for (Player viewer : viewers) if (!bar.getPlayers().contains(viewer)) bar.addPlayer(viewer);
    }

    /** 波の現在番号と跳躍指示を共通メッセージから解決します。 */
    private static String waveTitle(int number, int count) {
        return PlayerMsgResource.format(PlayerMsgId.P_7628.getId(), number + 1, count);
    }

    /** 演出地点に効果音を鳴らします。 */
    private static void sound(Location origin, String name, float volume, float pitch) {
        if (origin.getWorld() != null) origin.getWorld().playSound(origin, name, volume, pitch);
    }

    /** 地表ギミックの水平距離二乗を返します。 */
    private static double distance(Location first, Location second) {
        return EmpusaSpellController.horizontalSquared(first, second);
    }

    private static final class Encounter {
        private final MobInstance boss;
        private final Set<UUID> summons = new HashSet<>();
        private final List<Location> spawnPoints = new ArrayList<>();
        private int initialSummons;
        private long nextSummonAt;
        private long summonAt;
        private long nextWaveAt;
        private @Nullable BossBar wardBar;
        private @Nullable Wave wave;

        private Encounter(MobInstance boss, long tick) {
            this.boss = boss;
            nextSummonAt = tick + 80L;
            nextWaveAt = tick + 100L;
        }
    }

    private static final class Wave {
        private final Location origin;
        private final int phase;
        private final int count;
        private final BossBar bar;
        private final Set<UUID> hitPlayers = new HashSet<>();
        private int number;
        private long warningTicks = 40L;
        private long releaseAt;
        private double previousRadius;

        private Wave(Location origin, long tick, int phase) {
            this.origin = origin;
            this.phase = phase;
            this.count = phase == 1 ? 2 : 3;
            this.releaseAt = tick + warningTicks;
            this.bar = Bukkit.createBossBar(waveTitle(0, count), BarColor.YELLOW, BarStyle.SEGMENTED_10);
            sound(origin, "block.beacon.activate", 1.0F, 0.7F);
        }
    }
}
