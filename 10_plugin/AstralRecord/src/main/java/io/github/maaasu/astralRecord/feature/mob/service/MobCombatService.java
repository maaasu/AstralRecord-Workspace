package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.mob.model.CombatStyle;
import io.github.maaasu.astralRecord.feature.mob.model.DamageType;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCombatConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassExperienceResult;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob の戦闘ロジックを担うサービス。
 *
 * <p>バニラの {@code EntityDamageEvent} や Bukkit Attribute には依存せず、
 * 命中判定・ダメージ計算・会心判定・致死処理を独自に実装する。</p>
 */
public class MobCombatService {

    private final MobService mobService;
    private final MobDropService dropService;
    private final MobDropPresentationService dropPresentationService;
    private final PartyService partyService;
    private final AdventureRecordService adventureRecordService;
    private final AccountService accountService;
    private final PlayerClassService playerClassService;
    private final StatusService statusService;
    private final SkillTreeService skillTreeService;
    private final ParticleDisplayService particleDisplayService;
    private PlayerDeathService playerDeathService;

    /**
     * コンストラクタ。
     *
     * @param mobService       Mob サービス
     * @param dropService      ドロップ抽選サービス
     */
    public MobCombatService(
            @NotNull MobService mobService,
            @NotNull MobDropService dropService,
            @NotNull MobDropPresentationService dropPresentationService,
            @NotNull PartyService partyService,
            @NotNull AdventureRecordService adventureRecordService,
            @NotNull AccountService accountService,
            @NotNull PlayerClassService playerClassService,
            @NotNull StatusService statusService,
            @NotNull SkillTreeService skillTreeService,
            @NotNull ParticleDisplayService particleDisplayService) {
        this.mobService = mobService;
        this.dropService = dropService;
        this.dropPresentationService = dropPresentationService;
        this.partyService = partyService;
        this.adventureRecordService = adventureRecordService;
        this.accountService = accountService;
        this.playerClassService = playerClassService;
        this.statusService = statusService;
        this.skillTreeService = skillTreeService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * 死亡中プレイヤーの戦闘除外判定に使うサービスを設定します。
     *
     * @param playerDeathService プレイヤー死亡状態管理サービス
     */
    public void setPlayerDeathService(@Nullable PlayerDeathService playerDeathService) {
        this.playerDeathService = playerDeathService;
    }

    /**
     * Mob のターゲットを選定して {@link MobInstance#targetId(UUID)} に設定します。
     *
     * @param instance 対象 Mob
     * @return 選定されたプレイヤー（候補なしなら {@code null}）
     */
    @Nullable
    public Player selectTarget(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        MobTargetingConfig targeting = template.targeting();
        if (targeting == null || template.category() == MobCategory.NPC) {
            instance.targetId(null);
            return null;
        }

        double aggroSq = targeting.aggroRange() * targeting.aggroRange();
        Location loc = instance.currentLocation();
        List<Player> candidates = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != loc.getWorld()) continue;
            if (isPlayerDead(player)) continue;
            if (player.getLocation().distanceSquared(loc) > aggroSq) continue;
            candidates.add(player);
        }
        if (candidates.isEmpty()) {
            instance.targetId(null);
            return null;
        }

        Player chosen = switch (targeting.strategy()) {
            case NEAREST -> nearest(candidates, loc);
            case HIGHEST_THREAT -> {
                UUID top = instance.threatTable().top();
                Player p = top == null ? null : Bukkit.getPlayer(top);
                yield p != null && candidates.contains(p) ? p : nearest(candidates, loc);
            }
            case RANDOM -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            case LOWEST_HP -> lowestHp(candidates);
        };

        instance.targetId(chosen == null ? null : chosen.getUniqueId());
        return chosen;
    }

    /**
     * {@link MobState#COMBAT} 状態の Mob について 1 tick 分の戦闘処理を実行します。
     *
     * @param instance   対象 Mob
     * @param serverTick 現在のサーバ tick
     */
    public void tickCombat(@NotNull MobInstance instance, long serverTick) {
        UUID targetId = instance.targetId();
        if (targetId == null) {
            instance.state(MobState.AGGRO);
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline() || isPlayerDead(target)) {
            instance.targetId(null);
            instance.state(MobState.AGGRO);
            return;
        }

        MobCombatConfig combat = instance.template().combat();
        if (combat == null) {
            instance.state(MobState.IDLE);
            return;
        }

        // 攻撃間隔チェック
        if (serverTick - instance.lastAttackTick() < combat.attackIntervalTicks()) {
            return;
        }

        // ターゲット距離チェック
        double preferredSq = combat.preferredRange() * combat.preferredRange();
        if (target.getLocation().distanceSquared(instance.currentLocation()) > preferredSq + 4.0) {
            // 範囲外。AGGRO に戻る
            instance.state(MobState.AGGRO);
            return;
        }

        // 命中判定
        double accuracy = 100.0; // 命中率は Mob 側のデフォルト
        double evasion = 0.0;    // プレイヤー側 EVASION は将来 statusSnapshot から取得
        if (ThreadLocalRandom.current().nextDouble(0.0, 100.0) >= accuracy - evasion) {
            // 不命中
            instance.lastAttackTick(serverTick);
            return;
        }

        // ダメージ計算
        double baseDamage = computeDamage(instance, target);
        double finalDamage = applyCriticalMultiplier(instance, baseDamage);

        applyDamageToPlayer(target, finalDamage, damageTypeOf(combat.style()));
        instance.lastAttackTick(serverTick);
    }

    /**
     * Mob からターゲット（プレイヤー）への基礎ダメージを算出します。
     * 戦闘スタイルに応じて使用ステータスを切り替え、ターゲットの防御で軽減します。
     *
     * @param instance 攻撃側 Mob
     * @param target   被攻撃側プレイヤー
     * @return 基礎ダメージ
     */
    public double computeDamage(@NotNull MobInstance instance, @NotNull Player target) {
        MobTemplate template = instance.template();
        double attack = template.statValue("ATTACK", 1.0);
        CombatStyle style = template.combat() == null ? CombatStyle.MELEE : template.combat().style();
        double scaling = switch (style) {
            case MELEE -> template.statValue("STRENGTH", 0.0);
            case RANGED -> template.statValue("DEXTERITY", 0.0);
            case MAGIC -> template.statValue("INTELLIGENCE", 0.0);
        };
        double offensive = attack * (1.0 + scaling / 100.0);
        double defense = resolvePlayerDefense(target, damageTypeOf(style));
        return Math.max(1.0, offensive - defense * 0.5);
    }

    /**
     * 基礎ダメージに会心・超会心の倍率を適用します。
     *
     * @param instance   攻撃側
     * @param baseDamage 基礎ダメージ
     * @return 会心適用後ダメージ
     */
    public double applyCriticalMultiplier(@NotNull MobInstance instance, double baseDamage) {
        MobTemplate template = instance.template();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        double critRate = template.statValue("CRITICAL_RATE", 0.0);
        if (rng.nextDouble(0.0, 100.0) >= critRate) {
            return baseDamage;
        }

        double critMul = template.statValue("CRITICAL_DAMAGE", 150.0) / 100.0;
        double damage = baseDamage * critMul;

        double superRate = template.statValue("SUPER_CRITICAL_RATE", 0.0);
        if (rng.nextDouble(0.0, 100.0) < superRate) {
            double superMul = template.statValue("SUPER_CRITICAL_DAMAGE", 100.0) / 100.0;
            damage *= superMul;
        }
        return damage;
    }

    /**
     * プレイヤーから Mob への被ダメージを処理します。
     * ダメージ計算（プレイヤー側のスケーリング・装備補正）は呼び出し元の責務です。
     *
     * @param instance     被ダメージ対象 Mob
     * @param attacker     攻撃元プレイヤー
     * @param damage       確定済み攻撃側ダメージ（防御適用前）
     * @param type         ダメージ種別
     * @return 実際に与えたダメージ（防御適用後）
     */
    public double applyDamage(
            @NotNull MobInstance instance,
            @NotNull AstPlayer attacker,
            double damage,
            @NotNull DamageType type) {
        if (instance.state() == MobState.DEAD) return 0.0;
        if (isPlayerDead(attacker.getBukkit())) return 0.0;

        MobTemplate template = instance.template();
        double defenseStat = type == DamageType.PHYSICAL
                ? template.statValue("DEFENSE", 0.0)
                : template.statValue("MAGIC_DEFENSE", 0.0);
        double effective = Math.max(1.0, damage - defenseStat * 0.5);

        instance.currentHealth(instance.currentHealth() - effective);
        instance.threatTable().add(attacker.getBukkit().getUniqueId(), effective);
        instance.lastAttackerUuid(attacker.getBukkit().getUniqueId());

        if (instance.state() == MobState.IDLE) {
            instance.state(MobState.AGGRO);
            instance.targetId(attacker.getBukkit().getUniqueId());
        }
        if (instance.currentHealth() <= 0.0) {
            instance.state(MobState.DEAD);
        }
        return effective;
    }

    /**
     * 死亡確定 Mob のドロップ・破棄処理を実行します。
     *
     * @param instance 死亡 Mob
     * @return 配布対象プレイヤーごとのドロップ結果
     */
    @NotNull
    public List<MobDropResult> handleDeath(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        UUID killerId = instance.lastAttackerUuid();
        if (killerId == null) {
            killerId = instance.threatTable().top();
        }
        Player killer = killerId == null ? null : Bukkit.getPlayer(killerId);
        Logger.log(LogId.D_5703, template.id(), killerId);

        List<AstPlayer> recipients = resolveDropRecipients(instance, killer);
        if (recipients.isEmpty()) {
            Logger.log(LogId.W_5703, template.id());
        }

        List<MobDropResult> results = new ArrayList<>();
        for (AstPlayer recipient : recipients) {
            MobDropResult result;
            try {
                result = dropService.roll(template, recipient);
            } catch (RuntimeException ex) {
                Logger.error(LogId.E_5703, ex, template.id());
                result = new MobDropResult(List.of(), 0, 0);
            }
            results.add(result);
            applyExperienceAndSkillPoints(recipient, result);
            adventureRecordService.recordDefeatAsync(recipient, template);
            dropPresentationService.presentAndGrant(
                    recipient,
                    instance.currentLocation(),
                    ColorCodeUtil.toLegacyText(template.displayName(), template.id()),
                    result
            );
        }

        mobService.destroy(instance.instanceId());
        return results;
    }

    private @NotNull List<AstPlayer> resolveDropRecipients(@NotNull MobInstance instance, @Nullable Player killer) {
        Map<UUID, AstPlayer> recipients = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : instance.threatTable().snapshot().entrySet()) {
            if (entry.getValue() <= 0.0D) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            addRecipient(recipients, player);
        }

        if (killer == null || !killer.isOnline()) {
            return List.copyOf(recipients.values());
        }

        Party party = partyService.findParty(killer.getUniqueId());
        if (party == null) {
            addRecipient(recipients, killer);
            return List.copyOf(recipients.values());
        }

        double rangeSq = 60.0D * 60.0D;
        for (UUID memberId : party.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline()) {
                continue;
            }
            if (member.getWorld() != killer.getWorld()) {
                continue;
            }
            if (member.getLocation().distanceSquared(killer.getLocation()) > rangeSq) {
                continue;
            }
            addRecipient(recipients, member);
        }
        return List.copyOf(recipients.values());
    }

    private void addRecipient(@NotNull Map<UUID, AstPlayer> recipients, @Nullable Player player) {
        if (player == null || !player.isOnline() || isPlayerDead(player)) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        recipients.putIfAbsent(player.getUniqueId(), astPlayer);
    }

    private void applyExperienceAndSkillPoints(@NotNull AstPlayer recipient, @NotNull MobDropResult result) {
        if (result.exp() <= 0) {
            return;
        }
        try {
            AccountExperienceResult progress = accountService.grantExperienceCached(
                recipient.getAccount(),
                result.exp(),
                recipient.getUser().getUuid()
            );
            ClassExperienceResult classProgress = playerClassService.grantClassExperience(recipient, result.exp());
            applyExperienceAndSkillPointsResult(recipient, progress, classProgress);
        } catch (RuntimeException ex) {
            Logger.error(LogId.E_5155, ex, recipient.getAccount().getUuid(), result.exp());
        }
    }

    private void applyExperienceAndSkillPointsResult(
            @NotNull AstPlayer recipient,
            @NotNull AccountExperienceResult progress,
            @NotNull ClassExperienceResult classProgress
    ) {
        recipient.setAccount(progress.updatedAccount());
        if (!recipient.getBukkit().isOnline()) {
            return;
        }

        if (progress.leveledUp()) {
            PlayerMessageService.getInstance().send(
                recipient,
                PlayerMsgId.P_5835,
                progress.updatedAccount().getLevel(),
                progress.grantedExperience(),
                progress.levelUps()
            );
            playPlayerLevelUp(recipient.getBukkit());
            skillTreeService.refreshProgressDerivedState(recipient);
        }
        if (classProgress.getLeveledUp()) {
            PlayerMessageService.getInstance().send(
                recipient,
                PlayerMsgId.P_5847,
                recipient.getClassLevel(),
                classProgress.getGrantedExperience(),
                classProgress.getClassPointGains()
            );
            playClassLevelUp(recipient.getBukkit());
            skillTreeService.refreshProgressDerivedState(recipient);
        }
        if (progress.leveledUp() || classProgress.getLeveledUp()) {
            statusService.refreshStatus(recipient);
        }
    }

    private void playPlayerLevelUp(@NotNull Player player) {
        Location location = player.getLocation().add(0.0D, 1.0D, 0.0D);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.9F, 0.85F);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7F, 1.05F);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.45F, 1.35F);
        particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.PLAYER_LEVEL_UP_TOTEM);
        particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.PLAYER_LEVEL_UP_END_ROD);
    }

    private void playClassLevelUp(@NotNull Player player) {
        Location location = player.getLocation().add(0.0D, 0.9D, 0.0D);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.45F, 1.45F);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.4F, 1.75F);
        particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.CLASS_LEVEL_UP_DUST);
        particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.CLASS_LEVEL_UP_ENCHANT);
    }

    private double resolvePlayerDefense(@NotNull Player target, @NotNull DamageType type) {
        // プレイヤー側 AstPlayer / StatusSnapshot からの取得は将来統合する。
        // 当面は固定値 0 を返す。
        StatusType ignored = type == DamageType.PHYSICAL ? StatusType.DEFENSE : StatusType.MAGIC_DEFENSE;
        // ignored 変数は forward-compat のため意図的に保持
        return ignored == null ? 0.0 : 0.0;
    }

    private DamageType damageTypeOf(@NotNull CombatStyle style) {
        return style == CombatStyle.MAGIC ? DamageType.MAGIC : DamageType.PHYSICAL;
    }

    private boolean isPlayerDead(@NotNull Player player) {
        return playerDeathService != null && playerDeathService.isDead(player.getUniqueId());
    }

    private void applyDamageToPlayer(@NotNull Player target, double damage, @NotNull DamageType type) {
        // プレイヤー側の独自ダメージ適用 API（feature/player）が未整備のため、当面は Bukkit Player.damage を呼ぶ。
        // 将来的に AstPlayer / StatusSnapshot 経由で適用する（[[12_9.00-未決事項]] 参照）。
        target.damage(damage);
    }

    @Nullable
    private Player nearest(@NotNull List<Player> candidates, @NotNull Location origin) {
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Player player : candidates) {
            double sq = player.getLocation().distanceSquared(origin);
            if (sq < bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    @Nullable
    private Player lowestHp(@NotNull List<Player> candidates) {
        Player best = null;
        double bestHp = Double.MAX_VALUE;
        for (Player player : candidates) {
            double hp = player.getHealth();
            if (hp < bestHp) {
                bestHp = hp;
                best = player;
            }
        }
        return best;
    }

    /**
     * フィールド参照の保持のため、未使用変数の警告を抑制する内部メソッド。
     */
    @SuppressWarnings("unused")
    private static void retainEnums() {
        TargetStrategy ts = TargetStrategy.NEAREST;
        DamageType dt = DamageType.PHYSICAL;
    }
}
