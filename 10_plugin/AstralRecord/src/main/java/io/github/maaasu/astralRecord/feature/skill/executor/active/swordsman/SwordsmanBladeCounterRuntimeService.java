package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DirectDamageModification;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ブレードカウンターのバフ、受付、反撃、白色ステンドグラス演出を管理します。 */
public final class SwordsmanBladeCounterRuntimeService {

    private static final long VISUAL_PERIOD_TICKS = 4L;
    private static final int DISPLAY_COUNT = 4;
    private static final double DISPLAY_RADIUS = 1.15D;
    private final DamageService damageService;
    private final SkillEffectService effects;
    private final Map<UUID, RuntimeEntry> entries = new HashMap<>();
    private final BukkitTask visualTask;
    private long visualFrame;

    /**
     * Plugin所有schedulerと戦闘・演出サービスで初期化します。
     *
     * @param plugin タスク所有Plugin
     * @param damageService custom combatサービス
     * @param effects 共通スキル演出サービス
     */
    public SwordsmanBladeCounterRuntimeService(
            @NotNull Plugin plugin,
            @NotNull DamageService damageService,
            @NotNull SkillEffectService effects
    ) {
        this.damageService = damageService;
        this.effects = effects;
        this.visualTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::updateVisuals,
                VISUAL_PERIOD_TICKS,
                VISUAL_PERIOD_TICKS
        );
    }

    /**
     * 詠唱完了時点の攻撃ステータスを保持してバフを開始します。
     * 既存バフがある場合は表示を破棄して新しい状態へ置換します。
     *
     * @param player 対象プレイヤー
     * @param counterAttacker 反撃に使う発動時点の攻撃者snapshot
     * @param durationTicks バフ持続tick
     * @param receptionTicks 通常攻撃後の受付tick
     * @param maximumCounters 最大反撃回数
     * @param counterDamageRatio 反撃倍率
     * @param damageReductionRate 軽減率（0から1）
     */
    public void activate(
            @NotNull AstPlayer player,
            @NotNull AstEntity counterAttacker,
            long durationTicks,
            long receptionTicks,
            int maximumCounters,
            double counterDamageRatio,
            double damageReductionRate
    ) {
        UUID playerId = player.getBukkit().getUniqueId();
        clear(playerId);
        long currentTick = Bukkit.getCurrentTick();
        RuntimeEntry entry = new RuntimeEntry(
                player,
                counterAttacker,
                new BladeCounterState(maximumCounters, currentTick + durationTicks),
                spawnDisplays(player.getBukkit()),
                receptionTicks,
                counterDamageRatio,
                damageReductionRate
        );
        entries.put(playerId, entry);
        effects.sound(player.getBukkit().getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 0.8F, 1.25F);
    }

    /**
     * 成立した通常攻撃の直後にカウンター受付を開始します。
     *
     * @param player 通常攻撃を行ったプレイヤー
     */
    public void onNormalAttack(@NotNull AstPlayer player) {
        RuntimeEntry entry = entries.get(player.getBukkit().getUniqueId());
        long currentTick = Bukkit.getCurrentTick();
        if (entry == null || !entry.state().isActive(currentTick)) {
            if (entry != null) {
                clear(player.getBukkit().getUniqueId());
            }
            return;
        }
        entry.state().openReception(currentTick, entry.receptionTicks());
        Location center = player.getBukkit().getLocation().add(0.0D, 1.0D, 0.0D);
        effects.ring(center, 1.05D, 12, SharedParticleDefinitions.SKILL_SWORD_GUARD_DUST);
        effects.sound(center, Sound.ITEM_SHIELD_BLOCK, 0.55F, 1.55F);
    }

    /**
     * 管理戦闘の直接攻撃を検査し、受付中なら回数を先に消費して倍率と反映完了後処理を返します。
     *
     * @param attacker 元攻撃者
     * @param victim 被弾者
     * @param attackType 元攻撃種別
     * @param source 元攻撃の発生元
     * @param calculated 元攻撃の計算結果
     * @return 元攻撃へ適用する倍率と反映完了後処理
     */
    public @NotNull DirectDamageModification modifyIncomingDirectDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType,
            @NotNull DamageSource source,
            @NotNull DamageResult calculated
    ) {
        if (attacker == null || !attacker.isMob() || !victim.isPlayer() || victim.player() == null) {
            return DirectDamageModification.none();
        }
        if (source != DamageSource.NORMAL_ATTACK && source != DamageSource.SKILL) {
            return DirectDamageModification.none();
        }
        RuntimeEntry entry = entries.get(victim.id());
        long currentTick = Bukkit.getCurrentTick();
        if (entry == null || !entry.state().consumeCounter(currentTick)) {
            if (entry != null && !entry.state().isActive(currentTick)) {
                clear(victim.id());
            }
            return DirectDamageModification.none();
        }

        if (calculated.evaded()) {
            if (entry.state().remainingCounters() <= 0) {
                clear(victim.id());
            }
            return DirectDamageModification.none();
        }

        return new DirectDamageModification(
                1.0D - entry.damageReductionRate(),
                () -> completeCounter(attacker, victim, entry)
        );
    }

    private void completeCounter(
            @NotNull AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull RuntimeEntry entry
    ) {
        if (entries.get(victim.id()) != entry) {
            return;
        }
        if (victim.currentHealth() <= 0.0D) {
            clear(victim.id());
            return;
        }

        Location guardLocation = victim.location().add(0.0D, 1.0D, 0.0D);
        effects.ring(guardLocation, 0.8D, 10, SharedParticleDefinitions.SKILL_SWORD_GUARD_DUST);
        effects.point(attacker.location().add(0.0D, 0.9D, 0.0D), SharedParticleDefinitions.SKILL_SWORD_SWEEP);
        effects.sound(guardLocation, Sound.ITEM_SHIELD_BLOCK, 1.0F, 0.9F);
        effects.sound(attacker.location(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9F, 1.25F);

        damageService.attack(
                entry.counterAttacker(),
                attacker,
                AttackType.MELEE,
                List.of(new DamageComponent(DamageElement.NONE, entry.counterDamageRatio())),
                DamageSource.SKILL
        );
        if (entry.state().remainingCounters() <= 0) {
            clear(victim.id());
        }
    }

    /**
     * 指定プレイヤーのバフと表示Entityを破棄します。
     *
     * @param playerId 対象UUID
     */
    public void clear(@NotNull UUID playerId) {
        RuntimeEntry removed = entries.remove(playerId);
        if (removed != null) {
            removed.displays().forEach(ItemDisplay::remove);
        }
    }

    /** 全プレイヤーの状態と表示を破棄し、更新タスクを停止します。 */
    public void stop() {
        visualTask.cancel();
        new ArrayList<>(entries.keySet()).forEach(this::clear);
    }

    int remainingCounters(@NotNull UUID playerId) {
        RuntimeEntry entry = entries.get(playerId);
        return entry == null ? 0 : entry.state().remainingCounters();
    }

    private @NotNull List<ItemDisplay> spawnDisplays(@NotNull Player player) {
        World world = player.getWorld();
        Location center = player.getLocation().add(0.0D, 1.0D, 0.0D);
        List<ItemDisplay> displays = new ArrayList<>(DISPLAY_COUNT);
        for (int index = 0; index < DISPLAY_COUNT; index++) {
            ItemDisplay display = world.spawn(center, ItemDisplay.class, entity -> {
                entity.setItemStack(new ItemStack(Material.WHITE_STAINED_GLASS));
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setViewRange(24.0F);
                entity.setTeleportDuration((int) VISUAL_PERIOD_TICKS);
            });
            displays.add(display);
        }
        return List.copyOf(displays);
    }

    private void updateVisuals() {
        long currentTick = Bukkit.getCurrentTick();
        visualFrame++;
        for (var iterator = entries.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, RuntimeEntry> mapEntry = iterator.next();
            RuntimeEntry entry = mapEntry.getValue();
            Player player = entry.player().getBukkit();
            if (!entry.state().isActive(currentTick) || !player.isOnline() || player.isDead()) {
                entry.displays().forEach(ItemDisplay::remove);
                iterator.remove();
                continue;
            }
            Location center = player.getLocation().add(0.0D, 1.0D, 0.0D);
            for (int index = 0; index < entry.displays().size(); index++) {
                ItemDisplay display = entry.displays().get(index);
                if (!display.isValid() || display.getWorld() != player.getWorld()) {
                    continue;
                }
                double angle = visualFrame * 0.30D + Math.PI * 2.0D * index / DISPLAY_COUNT;
                double height = index % 2 == 0 ? 0.28D : -0.18D;
                display.teleport(center.clone().add(
                        Math.cos(angle) * DISPLAY_RADIUS,
                        height,
                        Math.sin(angle) * DISPLAY_RADIUS
                ));
                display.setRotation((float) Math.toDegrees(-angle), 0.0F);
            }
        }
    }

    private record RuntimeEntry(
            @NotNull AstPlayer player,
            @NotNull AstEntity counterAttacker,
            @NotNull BladeCounterState state,
            @NotNull List<ItemDisplay> displays,
            long receptionTicks,
            double counterDamageRatio,
            double damageReductionRate
    ) {
    }
}
