package io.github.maaasu.astralRecord.feature.mob.skill.caldera;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;
import java.util.function.IntPredicate;

/** 火山Mobの同期2tick管理。固定予告・CC中断・表示破棄を一箇所で管理します。 */
public final class CalderaSkillSupport {
    private final MobService mobs;
    private final DamageService damage;
    private final ConditionService conditions;
    private final ParticleDisplayService particles;
    private final Map<UUID, Effect> effects = new HashMap<>();
    private BukkitTask task;

    /** @param mobs Mob実体と同期scheduler @param damage 対象解決・ダメージ @param conditions 行動可否 @param particles 一括粒子描画 */
    public CalderaSkillSupport(MobService mobs, DamageService damage, ConditionService conditions,
                               ParticleDisplayService particles) {
        this.mobs = mobs; this.damage = damage; this.conditions = conditions; this.particles = particles;
    }

    /** 時限効果の開始・継続ではCOMBATを要求し、戦闘解除で直ちに回収します。 */
    boolean active(MobSkillContext context) {
        return context.mob().state() == MobState.COMBAT && impactCaster(context);
    }

    /** 同一着弾tick内では主対象の撃破による状態遷移に依存せず、発動者自身の行動可否を確認します。 */
    private boolean impactCaster(MobSkillContext context) {
        var mob = context.mob();
        Entity entity = mobs.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        return mobs.plugin().isEnabled() && mobs.getInstance(mob.instanceId()) == mob
                && mob.state() != MobState.DEAD && mob.currentHealth() > 0 && entity != null
                && entity.isValid() && !entity.isDead() && entity.getWorld() == context.origin().getWorld()
                && conditions.canRunAi(caster) && conditions.canCastSkill(caster) && conditions.canAttack(caster);
    }

    /** 同一Mobの旧効果を回収し、最大64効果を同期管理します。開始失敗時はfalseを返します。 */
    boolean begin(MobSkillContext context, List<BlockDisplay> displays, IntPredicate pulse) {
        clearCasterState(context.mob().instanceId());
        if (!active(context) || effects.size() >= 64) return false;
        context.mob().scriptedAction(true);
        effects.put(context.mob().instanceId(), new Effect(context, displays, pulse));
        if (task == null) task = mobs.plugin().getServer().getScheduler().runTaskTimer(mobs.plugin(), this::tick, 2L, 2L);
        return true;
    }

    /** 継続条件を確認し、例外時にも全表示とAI停止を回収して例外をschedulerへ返します。 */
    private void tick() {
        for (Effect effect : List.copyOf(effects.values())) {
            UUID id = effect.context.mob().instanceId();
            try {
                effect.elapsed += 2;
                if (!active(effect.context) || !effect.pulse.test(effect.elapsed)) clearCasterState(id);
            } catch (RuntimeException exception) {
                clearCasterState(id);
                throw exception;
            }
        }
        if (effects.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    /** @param id 破棄対象Mob ID。登録済みの全BlockDisplayとAI停止を解除します。 */
    public void clearCasterState(UUID id) {
        Effect effect = effects.remove(id);
        if (effect != null) {
            for (BlockDisplay display : effect.displays) display.remove();
            effect.displays.clear();
            effect.context.mob().scriptedAction(false);
        }
    }

    /** Plugin停止時にtaskと全効果を同期回収します。 */
    public void stop() {
        for (UUID id : List.copyOf(effects.keySet())) clearCasterState(id);
        if (task != null) { task.cancel(); task = null; }
    }

    /** 半径表示は既存の赤橙メテオ粒子を、最大64点・4tick間隔で一括描画します。 */
    void warning(Location center, List<Location> points) {
        particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.WIZARD_METEOR_SIGIL_RUNE);
    }

    /** 地面を6block下まで探索します。未読込chunkはロードせず失敗します。 */
    Location ground(Location feet) {
        World world = feet.getWorld();
        if (world == null || !world.isChunkLoaded(feet.getBlockX() >> 4, feet.getBlockZ() >> 4)) return null;
        for (int y = Math.min(feet.getBlockY(), world.getMaxHeight() - 1);
             y >= Math.max(world.getMinHeight(), feet.getBlockY() - 6); y--) {
            if (!world.getBlockAt(feet.getBlockX(), y, feet.getBlockZ()).isPassable())
                return new Location(world, feet.getX(), y + 1.0D, feet.getZ());
        }
        return null;
    }

    /** 表示の水平半径を含む柱を最大16block上まで調べ、低い天井の直下へ高さを制限します。 */
    double clearance(Location ground, double desired) {
        int top = (int) Math.ceil(desired + 1);
        for (int y = 0; y <= top; y++) {
            for (double x : new double[]{-0.65D, 0, 0.65D}) for (double z : new double[]{-0.65D, 0, 0.65D}) {
                Location sample = ground.clone().add(x, y, z);
                if (sample.getBlockY() >= ground.getWorld().getMaxHeight()
                        || !ground.getWorld().isChunkLoaded(sample.getBlockX() >> 4, sample.getBlockZ() >> 4)
                        || !sample.getBlock().isPassable()) return Math.max(0, y - 1.05D);
            }
        }
        return desired;
    }

    /** 地形を変更せず、最大13個までの一時表示を生成して直ちに回収リストへ登録します。 */
    BlockDisplay display(Location location, float scale, List<BlockDisplay> displays) {
        if (displays.size() >= 13) throw new IllegalStateException("Caldera display limit");
        BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class);
        displays.add(display);
        display.setBlock(Material.MAGMA_BLOCK.createBlockData());
        display.setPersistent(false); display.setGravity(false); display.setInvulnerable(true);
        display.setTeleportDuration(2);
        display.setTransformation(new Transformation(new Vector3f(-scale / 2, 0, -scale / 2),
                new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        return display;
    }

    /** 共通の有効player/pet候補を返し、未登録entityを攻撃しません。 */
    List<LivingEntity> targets(Location center, double radius) { return damage.mobCombatTargets(center, radius, 1.25D); }

    /** 壁と高低差を確認し、一回だけ魔法・炎倍率を既存DamageServiceへ渡します。 */
    void hit(MobSkillContext context, Location from, LivingEntity target, double ratio) {
        if (!impactCaster(context) || !damage.isMobCombatTarget(target)
                || Math.abs(from.getY() - target.getLocation().getY()) > 1.25D || !clearPath(from, target)) return;
        damage.attack(AstEntity.mob(context.mob()), damage.resolveEntity(target), AttackType.MAGIC,
                List.of(new DamageComponent(DamageElement.FIRE, ratio)), DamageSource.SKILL);
    }

    /** 地上の攻撃点から対象の胴体までのblock遮蔽を確認します。 */
    private boolean clearPath(Location from, LivingEntity target) {
        Location start = from.clone().add(0, 0.5D, 0);
        Vector direction = target.getLocation().add(0, Math.min(1, target.getHeight() / 2), 0).toVector().subtract(start.toVector());
        double distance = direction.length();
        return distance < 0.01D || start.getWorld().rayTraceBlocks(start, direction, distance, FluidCollisionMode.NEVER, true) == null;
    }

    /** 着弾時の既存メテオ爆発粒子を一度表示します。 */
    void explosion(Location center) {
        particles.spawnForNearbyViewers(center, List.of(center.clone().add(0, .5D, 0)), SharedParticleDefinitions.WIZARD_METEOR_FLAME_BURST);
        center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, .9F, .8F);
    }

    /** 未知キーとは別に、有限値・値域・tick偶数を検証します。 */
    static double parameter(Map<String, Double> params, String key, double fallback, double min, double max, boolean even) {
        double value = params.getOrDefault(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max || even && (value != Math.rint(value) || value % 2 != 0))
            throw new IllegalArgumentException("Invalid Caldera parameter: " + key);
        return value;
    }

    /** 一発動の表示・文脈・有限経過tickを保持します。 */
    private static final class Effect {
        final MobSkillContext context; final List<BlockDisplay> displays; final IntPredicate pulse; int elapsed;
        /** 一発動の固定文脈、回収リスト、2tick更新処理を保持します。 */
        Effect(MobSkillContext context, List<BlockDisplay> displays, IntPredicate pulse) {
            this.context = context; this.displays = displays; this.pulse = pulse;
        }
    }
}
