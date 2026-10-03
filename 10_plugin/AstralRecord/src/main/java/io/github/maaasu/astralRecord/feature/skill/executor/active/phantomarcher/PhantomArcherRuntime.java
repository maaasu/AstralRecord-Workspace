package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** ファントムアーチャーの所有者別霊印と退避後の幻影矢を管理します。メインスレッド専用です。 */
public final class PhantomArcherRuntime {
    public static final int MAX_MARKS = 3;
    public static final int MARK_DURATION_TICKS = 120;
    private static final Map<UUID, Map<UUID, Mark>> MARKS = new HashMap<>();
    private static final Map<UUID, ReadyArrow> READY_ARROWS = new HashMap<>();
    private static final Map<UUID, OwnerEpoch> OWNER_EPOCHS = new HashMap<>();
    private static final UUID INITIAL_OWNER_EPOCH = new UUID(0L, 0L);
    private static long globalEpoch;
    private static PassiveSkillService passives;
    private static SkillService skills;
    private static ItemWeaponAttackService weapons;
    private static SkillEffectService effects;

    private PhantomArcherRuntime() { }

    /**
     * 起動時にパッシブ、定義、正規の武器タグ解決経路を接続します。
     * @param passiveService 有効パッシブの解決元
     * @param skillService 公開済みスキル定義の解決元
     * @param weaponService 現在の主手武器の解決元
     * @param effectService 霊印表示に使う共有演出サービス
     */
    public static void configure(@NotNull PassiveSkillService passiveService,
                                 @NotNull SkillService skillService,
                                 @NotNull ItemWeaponAttackService weaponService,
                                 @NotNull SkillEffectService effectService) {
        passives = passiveService;
        skills = skillService;
        weapons = weaponService;
        effects = effectService;
        clearAll();
    }

    /**
     * 現在の主手が使用可能な弓・短弓・長弓か確認します。
     * @param player 判定する所有者
     * @return 正規装備判定を通る弓ならtrue
     */
    public static boolean hasBow(@NotNull AstPlayer player) {
        if (weapons == null || !weapons.hasUsableMainHandWeapon(player)) return false;
        String attack = weapons.currentLeftClickSkillId(player);
        return BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW.equals(attack)
                || BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SHORTBOW.equals(attack)
                || BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_LONGBOW.equals(attack);
    }

    /**
     * 指定した固有パッシブが現在有効か判定します。
     * @param player 判定する所有者
     * @param skillId パッシブID
     * @return バインド済みで有効ならtrue
     */
    public static boolean activePassive(@NotNull AstPlayer player, @NotNull String skillId) {
        return passives != null && passives.isPassiveSkillActive(player, skillId);
    }

    /**
     * 公開済みのLv1パッシブ定義から固有パラメータを取得します。
     * @param skillId パッシブID
     * @param key パラメータ名
     * @return 定義値。不明な場合は0
     */
    public static double passiveRatio(@NotNull String skillId, @NotNull String key) {
        if (skills == null) return 0.0D;
        SkillDefinition definition = skills.registry().getDefinition(skillId);
        return definition == null ? 0.0D
                : new SkillParamReader(skillId, definition.getParams()).getDouble(key, 0.0D);
    }

    /**
     * 公開済みのLv1パッシブ定義から継続tickを取得します。
     * @param skillId パッシブID
     * @param key パラメータ名
     * @return 定義値。不明な場合は0
     */
    public static int passiveTicks(@NotNull String skillId, @NotNull String key) {
        if (skills == null) return 0;
        SkillDefinition definition = skills.registry().getDefinition(skillId);
        return definition == null ? 0
                : new SkillParamReader(skillId, definition.getParams()).getInt(key, 0);
    }

    /**
     * 回避されずHPまたは盾へ正のダメージを与えたか確認します。
     * @param result 共通戦闘の確定結果
     * @return 有効命中ならtrue
     */
    public static boolean effective(@Nullable DamageResult result) {
        return result != null && !result.evaded()
                && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D);
    }

    /**
     * 通常弓攻撃の有効命中に限って霊印を付けます。専用弾からは呼びません。
     * @param player 攻撃者
     * @param target 命中した管理Mob
     * @param result 共通戦闘の確定結果
     * @param launchEpoch 弾の射出時に保存した職業・公開世代
     */
    public static void onNormalBowHit(@NotNull AstPlayer player, @NotNull AstEntity target,
                                      @Nullable DamageResult result, @NotNull ShotEpoch launchEpoch) {
        if (target.isMob() && effective(result) && hasBow(player)
                && !player.getBukkit().isDead()
                && player.getBukkit().getWorld() == target.location().getWorld()
                && launchEpoch.equals(shotEpoch(player.getBukkit().getUniqueId()))
                && activePassive(player, PhantomArcherPassiveExecutor.ATTUNEMENT_ID)) {
            addMark(player.getBukkit().getUniqueId(), target.id());
            if (effects != null) PhantomArcherEffects.resonance(effects, target.location(),
                    marks(player.getBukkit().getUniqueId(), target.id()));
        }
    }

    /**
     * 弓弾の射出時点の職業・公開世代を保存します。
     * @param owner 攻撃者UUID
     * @return 再読込や職業変更後の弾を識別する世代
     */
    public static @NotNull ShotEpoch shotEpoch(@NotNull UUID owner) {
        OWNER_EPOCHS.values().removeIf(epoch -> epoch.expiresAtTick <= Bukkit.getCurrentTick());
        OwnerEpoch epoch = OWNER_EPOCHS.get(owner);
        return new ShotEpoch(globalEpoch, epoch == null ? INITIAL_OWNER_EPOCH : epoch.token);
    }

    /**
     * 弓弾を撃つ時点の世代を取り、着弾まで所有者世代の有効期間を延長します。
     * @param owner 攻撃者UUID
     * @return 射出時点の世代
     */
    public static @NotNull ShotEpoch captureShotEpoch(@NotNull UUID owner) {
        ShotEpoch snapshot = shotEpoch(owner);
        OwnerEpoch epoch = OWNER_EPOCHS.get(owner);
        if (epoch != null) OWNER_EPOCHS.put(owner,
                new OwnerEpoch(epoch.token, Bukkit.getCurrentTick() + 120L));
        return snapshot;
    }

    /**
     * 有効命中した対象へ印を一層重ねます。
     * @param owner 攻撃者UUID
     * @param target 管理Mob UUID
     */
    public static void addMark(@NotNull UUID owner, @NotNull UUID target) {
        Map<UUID, Mark> marks = MARKS.computeIfAbsent(owner, ignored -> new HashMap<>());
        marks.values().removeIf(mark -> mark.expiresAtTick <= Bukkit.getCurrentTick());
        Mark old = marks.get(target);
        int stacks = old == null || old.expiresAtTick <= Bukkit.getCurrentTick() ? 0 : old.stacks;
        marks.put(target, new Mark(Math.min(MAX_MARKS, stacks + 1), Bukkit.getCurrentTick() + MARK_DURATION_TICKS));
    }

    /**
     * 対象に残る未失効印の層数を取得します。
     * @param owner 攻撃者UUID
     * @param target 管理Mob UUID
     * @return 0から3の層数
     */
    public static int marks(@NotNull UUID owner, @NotNull UUID target) {
        Map<UUID, Mark> marks = MARKS.get(owner);
        if (marks == null) return 0;
        Mark mark = marks.get(target);
        if (mark == null) return 0;
        if (mark.expiresAtTick > Bukkit.getCurrentTick()) return mark.stacks;
        marks.remove(target);
        if (marks.isEmpty()) MARKS.remove(owner);
        return 0;
    }

    /**
     * 有効命中後に、計算時に使った対象の印を消費します。
     * @param owner 攻撃者UUID
     * @param target 管理Mob UUID
     */
    public static void consume(@NotNull UUID owner, @NotNull UUID target) {
        Map<UUID, Mark> marks = MARKS.get(owner);
        if (marks == null) return;
        marks.remove(target);
        if (marks.isEmpty()) MARKS.remove(owner);
    }

    /**
     * 回避成功後の幻影矢を一回だけ準備します。
     * @param owner 攻撃者UUID
     * @param durationTicks 使用可能なtick数
     */
    public static void readyArrow(@NotNull UUID owner, int durationTicks) {
        READY_ARROWS.put(owner, new ReadyArrow(Bukkit.getCurrentTick() + durationTicks));
    }

    /**
     * 次の専用直射で使用する準備済み幻影矢を取り出します。
     * @param player 攻撃者
     * @return 有効な幻影矢を消費できた場合true
     */
    public static boolean takeArrow(@NotNull AstPlayer player) {
        UUID owner = player.getBukkit().getUniqueId();
        ReadyArrow ready = READY_ARROWS.remove(owner);
        return ready != null && ready.expiresAtTick > Bukkit.getCurrentTick() && hasBow(player)
                && activePassive(player, PhantomArcherPassiveExecutor.WRAITH_ID);
    }

    /**
     * パッシブ解除時に未使用の幻影矢だけを破棄します。
     * @param owner 所有者UUID
     */
    public static void clearReadyArrow(@NotNull UUID owner) {
        READY_ARROWS.remove(owner);
    }

    /**
     * 転職・死亡・退出時に所有者の一時状態を破棄します。
     * @param owner 所有者UUID
     */
    public static void clearOwner(@NotNull UUID owner) {
        MARKS.remove(owner);
        READY_ARROWS.remove(owner);
        OWNER_EPOCHS.put(owner, new OwnerEpoch(UUID.randomUUID(), Bukkit.getCurrentTick() + 120L));
    }

    /** 公開切替または停止時に全所有者の一時状態を破棄します。 */
    public static void clearAll() {
        MARKS.clear();
        READY_ARROWS.clear();
        OWNER_EPOCHS.clear();
        globalEpoch++;
    }

    private record Mark(int stacks, long expiresAtTick) { }
    private record ReadyArrow(long expiresAtTick) { }
    private record OwnerEpoch(UUID token, long expiresAtTick) { }

    /** 射出時点の全体公開世代と所有者ライフサイクル世代です。 */
    public record ShotEpoch(long global, @NotNull UUID owner) { }
}
