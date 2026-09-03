package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.CombatTimingCalculator;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.CombatStyle;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCombatConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobNormalAttackConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassExperienceResult;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
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
import java.util.function.BiConsumer;

/**
 * Mob の戦闘ロジックを担うサービス。
 *
 * <p>Mob のターゲット選定・攻撃間隔・報酬処理を管理する。
 * ダメージの命中判定・計算・HP反映は {@link DamageService} に委譲する。</p>
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
    private QuestService questService;
    private DamageService damageService;
    private AfkService afkService;
    private BiConsumer<AstPlayer, String> mobDefeatedListener;
    private BiConsumer<AstPlayer, MobDefeated> mobDefeatedLevelListener;

    /**
     * コンストラクタ。
     *
     * @param mobService Mob サービス
     * @param dropService ドロップ抽選サービス
     * @param dropPresentationService ドロップ表示サービス
     * @param partyService パーティーサービス
     * @param adventureRecordService 冒険記録サービス
     * @param accountService アカウントサービス
     * @param playerClassService プレイヤークラスサービス
     * @param statusService ステータスサービス
     * @param skillTreeService スキルツリーサービス
     * @param particleDisplayService パーティクル表示サービス
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
     * Mob の通常攻撃に使う統合ダメージサービスを設定します。
     *
     * @param damageService 統合ダメージサービス
     */
    public void setDamageService(@NotNull DamageService damageService) {
        this.damageService = damageService;
    }

    public void setQuestService(@Nullable QuestService questService) {
        this.questService = questService;
    }

    /**
     * AFK中のMob経験値付与を抑止する状態サービスを設定します。
     *
     * @param afkService AFK状態サービス
     */
    public void setAfkService(@NotNull AfkService afkService) {
        this.afkService = afkService;
    }

    /** Mob 討伐を外部機能へ通知するリスナーを設定します。 */
    public void setMobDefeatedListener(@Nullable BiConsumer<AstPlayer, String> mobDefeatedListener) {
        this.mobDefeatedListener = mobDefeatedListener;
    }

    /** レベルを含む Mob 討伐イベントを外部機能へ通知するリスナーを設定します。 */
    public void setMobDefeatedLevelListener(
            @Nullable BiConsumer<AstPlayer, MobDefeated> mobDefeatedLevelListener
    ) {
        this.mobDefeatedLevelListener = mobDefeatedLevelListener;
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
        if (targeting.retaliateOnly() && instance.targetId() == null) {
            return null;
        }

        double aggroSq = targeting.aggroRange() * targeting.aggroRange();
        Location loc = instance.currentLocation();
        if (targeting.retaliateOnly()) {
            UUID topId = instance.threatTable().top();
            Player top = topId == null ? null : Bukkit.getPlayer(topId);
            if (top == null || !isGameplayTargetPlayer(top) || isPlayerDead(top) || top.getWorld() != loc.getWorld()
                    || top.getLocation().distanceSquared(loc) > aggroSq) {
                instance.targetId(null);
                return null;
            }
            instance.targetId(top.getUniqueId());
            return top;
        }
        List<Player> candidates = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != loc.getWorld()) continue;
            if (!isGameplayTargetPlayer(player)) continue;
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
        if (target == null || !target.isOnline() || !isGameplayTargetPlayer(target) || isPlayerDead(target)) {
            instance.targetId(null);
            instance.state(MobState.AGGRO);
            return;
        }

        MobCombatConfig combat = instance.template().combat();
        if (combat == null) {
            instance.state(MobState.IDLE);
            return;
        }
        MobNormalAttackConfig normalAttack = combat.normalAttack();
        if (normalAttack == null) {
            return;
        }

        long attackIntervalTicks = CombatTimingCalculator.resolveAttackIntervalTicks(
                normalAttack.intervalTicks(),
                AstEntity.mob(instance).statValue(StatusType.ATTACK_SPEED)
        );
        if (serverTick - instance.lastAttackTick() < attackIntervalTicks) {
            return;
        }

        // ターゲット距離チェック
        double normalAttackRangeSq = normalAttack.range() * normalAttack.range();
        if (target.getLocation().distanceSquared(instance.currentLocation()) > normalAttackRangeSq + 4.0) {
            // 範囲外。AGGRO に戻る
            instance.state(MobState.AGGRO);
            return;
        }

        AstPlayer astTarget = AstPlayerCache.get(target);
        if (damageService == null || astTarget == null) {
            return;
        }
        damageService.attack(
                AstEntity.mob(instance),
                AstEntity.player(astTarget),
                attackType(combat.style()),
                List.of(DamageComponent.defaultComponent()),
                DamageSource.NORMAL_ATTACK
        );
        instance.lastAttackTick(serverTick);
    }

    private @NotNull AttackType attackType(@NotNull CombatStyle style) {
        return switch (style) {
            case MELEE -> AttackType.MELEE;
            case RANGED -> AttackType.RANGED;
            case MAGIC -> AttackType.MAGIC;
        };
    }

    /**
     * 死亡確定 Mob のドロップ・破棄処理を実行します。
     *
     * @param instance 死亡 Mob
     * @return 配布対象プレイヤーごとのドロップ結果
     */
    @NotNull
    public List<MobDropResult> handleDeath(@NotNull MobInstance instance) {
        UUID killerId = instance.lastAttackerUuid();
        if (killerId == null) {
            killerId = instance.threatTable().top();
        }
        Player killer = killerId == null ? null : Bukkit.getPlayer(killerId);
        Logger.log(LogId.D_5703, instance.template().id(), killerId);
        return handleDeathWithRecipients(instance, resolveDropRecipients(instance, killer));
    }

    /**
     * 呼び出し元が確定した受取人へ Mob 撃破報酬を配布します。
     * ボス挑戦など、通常のヘイト・現在パーティー判定を使用しない場合に利用します。
     *
     * @param instance 死亡 Mob
     * @param recipients 固定済みの報酬受取人
     * @return 配布対象プレイヤーごとのドロップ結果
     */
    public @NotNull List<MobDropResult> handleDeath(
            @NotNull MobInstance instance,
            @NotNull List<AstPlayer> recipients
    ) {
        Logger.log(LogId.D_5703, instance.template().id(), instance.lastAttackerUuid());
        Map<UUID, AstPlayer> uniqueRecipients = new LinkedHashMap<>();
        for (AstPlayer recipient : recipients) {
            if (recipient != null && recipient.getBukkit().isOnline()) {
                uniqueRecipients.putIfAbsent(recipient.getBukkit().getUniqueId(), recipient);
            }
        }
        return handleDeathWithRecipients(instance, List.copyOf(uniqueRecipients.values()));
    }

    private @NotNull List<MobDropResult> handleDeathWithRecipients(
            @NotNull MobInstance instance,
            @NotNull List<AstPlayer> recipients
    ) {
        MobTemplate template = instance.template();
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
            if (questService != null) {
                questService.recordMobKill(recipient, template.id(), template.level());
            }
            if (mobDefeatedListener != null) {
                mobDefeatedListener.accept(recipient, template.id());
            }
            if (mobDefeatedLevelListener != null) {
                mobDefeatedLevelListener.accept(recipient, new MobDefeated(template.id(), template.level()));
            }
            applyExperienceAndSkillPoints(recipient, result);
            adventureRecordService.recordDefeatAsync(recipient, template);
            dropPresentationService.presentAndGrant(
                    recipient,
                    instance.currentLocation(),
                    ColorCodeUtil.toLegacyText(template.displayName(), template.id()),
                    result,
                    template.category()
            );
        }

        mobService.destroy(instance.instanceId());
        return results;
    }

    /** Mob 討伐時点の ID と実効レベルです。 */
    public record MobDefeated(@NotNull String mobId, int level) {
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
        if (astPlayer == null || !AccountModeGuard.isGameplayPlayer(astPlayer)) {
            return;
        }
        recipients.putIfAbsent(player.getUniqueId(), astPlayer);
    }

    private void applyExperienceAndSkillPoints(@NotNull AstPlayer recipient, @NotNull MobDropResult result) {
        if (result.exp() <= 0 || (afkService != null && afkService.isAfk(recipient))) {
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
            Logger.error(LogId.E_5158, ex, recipient.getAccount().getUuid(), result.exp());
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
        }
        if (progress.leveledUp() || classProgress.getLeveledUp()) {
            skillTreeService.refreshProgressDerivedState(recipient);
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

    private boolean isPlayerDead(@NotNull Player player) {
        return playerDeathService != null && playerDeathService.isDead(player.getUniqueId());
    }

    private boolean isGameplayTargetPlayer(@NotNull Player player) {
        return player.getUniqueId() != null && AccountModeGuard.isGameplayPlayer(player);
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
    }
}
