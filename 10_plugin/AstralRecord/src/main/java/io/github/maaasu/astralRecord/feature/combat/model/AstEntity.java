package io.github.maaasu.astralRecord.feature.combat.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * ダメージ処理で扱うプレイヤー / Mob / Bukkit エンティティの共通モデルです。
 * <p>
 * {@link AstPlayer} と {@link MobInstance} の差異をこのモデルで吸収し、
 * {@link io.github.maaasu.astralRecord.feature.combat.service.DamageService} が
 * 攻撃者・被弾者を同じ型で受け取れるようにします。
 */
public final class AstEntity {

    private final AstEntityType type;
    private final AstPlayer player;
    private final MobInstance mob;
    private final Entity bukkitEntity;

    private AstEntity(
            @NotNull AstEntityType type,
            @Nullable AstPlayer player,
            @Nullable MobInstance mob,
            @Nullable Entity bukkitEntity
    ) {
        this.type = type;
        this.player = player;
        this.mob = mob;
        this.bukkitEntity = bukkitEntity;
    }

    /**
     * プレイヤーをダメージ処理用エンティティへ変換します。
     *
     * @param player AstralRecord プレイヤー
     * @return 変換後のエンティティ
     */
    public static @NotNull AstEntity player(@NotNull AstPlayer player) {
        return new AstEntity(AstEntityType.PLAYER, player, null, player.getBukkit());
    }

    /**
     * Mob インスタンスをダメージ処理用エンティティへ変換します。
     *
     * @param mob Mob インスタンス
     * @return 変換後のエンティティ
     */
    public static @NotNull AstEntity mob(@NotNull MobInstance mob) {
        return new AstEntity(AstEntityType.MOB, null, mob, null);
    }

    /**
     * Bukkit エンティティをダメージ処理用エンティティへ変換します。
     *
     * @param entity Bukkit エンティティ
     * @return 変換後のエンティティ
     */
    public static @NotNull AstEntity bukkit(@NotNull Entity entity) {
        return new AstEntity(AstEntityType.BUKKIT, null, null, entity);
    }

    /**
     * エンティティ種別を返します。
     *
     * @return エンティティ種別
     */
    public @NotNull AstEntityType type() {
        return type;
    }

    /**
     * 管理対象エンティティかどうかを返します。
     *
     * @return AstPlayer または MobInstance の場合は true
     */
    public boolean isManaged() {
        return type == AstEntityType.PLAYER || type == AstEntityType.MOB;
    }

    /**
     * プレイヤーかどうかを返します。
     *
     * @return プレイヤーの場合は true
     */
    public boolean isPlayer() {
        return type == AstEntityType.PLAYER;
    }

    /**
     * Mob かどうかを返します。
     *
     * @return Mob の場合は true
     */
    public boolean isMob() {
        return type == AstEntityType.MOB;
    }

    /**
     * レベル差計算に使用するレベルを返します。
     *
     * @return プレイヤーはアカウントレベル、Mob はテンプレートレベル、その他は 1
     */
    public int level() {
        return switch (type) {
            case PLAYER -> Math.max(1, player.getAccount().getLevel());
            case MOB -> Math.max(1, mob.template().level());
            case BUKKIT -> 1;
        };
    }

    /**
     * 一意な ID を返します。
     *
     * @return プレイヤー UUID、Mob インスタンス ID、または Bukkit エンティティ UUID
     */
    public @NotNull UUID id() {
        return switch (type) {
            case PLAYER -> player.getBukkit().getUniqueId();
            case MOB -> mob.instanceId();
            case BUKKIT -> bukkitEntity.getUniqueId();
        };
    }

    /**
     * 表示・ログ向けの名前を返します。
     *
     * @return エンティティ名
     */
    public @NotNull String name() {
        return switch (type) {
            case PLAYER -> player.getBukkit().getName();
            case MOB -> ColorCodeUtil.toLegacyText(mob.template().displayName(), mob.template().id());
            case BUKKIT -> bukkitEntity.getName();
        };
    }

    /**
     * 現在位置を返します。
     *
     * @return エンティティの現在位置
     */
    public @NotNull Location location() {
        return switch (type) {
            case PLAYER -> player.getBukkit().getLocation();
            case MOB -> mob.currentLocation();
            case BUKKIT -> bukkitEntity.getLocation();
        };
    }

    /**
     * 指定ステータスの合計値を返します。
     *
     * @param statusType 参照するステータス種別
     * @return ステータス値。未定義の場合は 0
     */
    public double statValue(@NotNull StatusType statusType) {
        return switch (type) {
            case PLAYER -> {
                yield player.getStatusSnapshot().rollValue(statusType);
            }
            case MOB -> mob.template().statValue(statusType.name(), 0.0D);
            case BUKKIT -> 0.0D;
        };
    }

    /**
     * 現在 HP を返します。
     *
     * @return 現在 HP。取得できない場合は 0
     */
    public double currentHealth() {
        return switch (type) {
            case PLAYER -> player.getStatusSnapshot().getCurrentHp();
            case MOB -> mob.currentHealth();
            case BUKKIT -> bukkitEntity instanceof Damageable damageable ? damageable.getHealth() : 0.0D;
        };
    }

    /**
     * 最大 HP を返します。
     *
     * @return 最大 HP。取得できない場合は 0
     */
    public double maxHealth() {
        return switch (type) {
            case PLAYER -> player.getStatusSnapshot().getMaxValue(StatusType.MAX_HEALTH);
            case MOB -> mob.template().statValue(StatusType.MAX_HEALTH.name(), 0.0D);
            case BUKKIT -> {
                if (bukkitEntity instanceof LivingEntity livingEntity
                        && livingEntity.getAttribute(Attribute.MAX_HEALTH) != null) {
                    yield Objects.requireNonNull(livingEntity.getAttribute(Attribute.MAX_HEALTH)).getValue();
                }
                yield 0.0D;
            }
        };
    }

    /**
     * プレイヤーとして取得します。
     *
     * @return プレイヤー。種別が異なる場合は null
     */
    public @Nullable AstPlayer player() {
        return player;
    }

    /**
     * Mob として取得します。
     *
     * @return Mob。種別が異なる場合は null
     */
    public @Nullable MobInstance mob() {
        return mob;
    }

    /**
     * Bukkit エンティティとして取得します。
     *
     * @return Bukkit エンティティ。Mob の場合は null
     */
    public @Nullable Entity bukkitEntity() {
        return bukkitEntity;
    }
}
