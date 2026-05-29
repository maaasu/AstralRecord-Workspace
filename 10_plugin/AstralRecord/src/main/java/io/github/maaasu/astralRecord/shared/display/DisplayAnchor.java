package io.github.maaasu.astralRecord.shared.display;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * TextDisplay の基準位置を解決する関数型インターフェースです。
 * <p>
 * 毎 tick メインスレッドから呼ばれ、固定座標・エンティティ追従・プレイヤー視線前方などを表現します。
 */
@FunctionalInterface
public interface DisplayAnchor {

    /**
     * 現在の表示基準位置を返します。
     *
     * @return 表示基準位置。無効な場合は null
     */
    @Nullable
    Location resolve();

    /**
     * TextDisplay を乗せる対象 Entity を返します。
     *
     * @return 乗せる対象 Entity。固定表示など、乗せない場合は null
     */
    default @Nullable Entity attachment() {
        return null;
    }

    /**
     * 乗せた TextDisplay に適用するローカル位置補正を返します。
     *
     * @return 対象 Entity 基準のローカル位置補正
     */
    default @NotNull Vector attachmentOffset() {
        return new Vector();
    }

    /**
     * 固定位置アンカーを生成します。
     *
     * @param location 固定表示位置
     * @return 固定位置アンカー
     */
    static @NotNull DisplayAnchor fixed(@NotNull Location location) {
        Objects.requireNonNull(location, "location");
        Location base = location.clone();
        return () -> base.clone();
    }

    /**
     * エンティティ追従アンカーを生成します。
     *
     * @param entity 追従対象
     * @param offset 基準位置からの加算オフセット
     * @return エンティティ追従アンカー
     */
    static @NotNull DisplayAnchor entity(@NotNull Entity entity, @NotNull Vector offset) {
        Objects.requireNonNull(entity, "entity");
        return entity(entity.getUniqueId(), offset);
    }

    /**
     * UUID 解決のエンティティ追従アンカーを生成します。
     *
     * @param entityId 追従対象エンティティ UUID
     * @param offset   基準位置からの加算オフセット
     * @return エンティティ追従アンカー
     */
    static @NotNull DisplayAnchor entity(@NotNull UUID entityId, @NotNull Vector offset) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(offset, "offset");
        Vector appliedOffset = offset.clone();
        return new DisplayAnchor() {
            @Override
            public @Nullable Location resolve() {
                Entity entity = attachment();
                if (entity == null) {
                    return null;
                }
                return entity.getLocation().add(appliedOffset);
            }

            @Override
            public @Nullable Entity attachment() {
                Entity entity = Bukkit.getEntity(entityId);
                return entity != null && entity.isValid() && !entity.isDead() ? entity : null;
            }

            @Override
            public @NotNull Vector attachmentOffset() {
                return appliedOffset.clone();
            }
        };
    }

    /**
     * プレイヤー視線前方に固定されるアンカーを生成します。
     *
     * @param player      対象プレイヤー
     * @param distance    目線前方への距離
     * @param localOffset 視線基準のローカルオフセット。X=右, Y=上, Z=前後
     * @return 視線前方アンカー
     */
    static @NotNull DisplayAnchor view(@NotNull Player player, double distance, @NotNull Vector localOffset) {
        Objects.requireNonNull(player, "player");
        return view(player.getUniqueId(), distance, localOffset);
    }

    /**
     * UUID 解決のプレイヤー視線前方アンカーを生成します。
     *
     * @param playerId    対象プレイヤー UUID
     * @param distance    目線前方への距離
     * @param localOffset 視線基準のローカルオフセット。X=右, Y=上, Z=前後
     * @return 視線前方アンカー
     */
    static @NotNull DisplayAnchor view(@NotNull UUID playerId, double distance, @NotNull Vector localOffset) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(localOffset, "localOffset");
        Vector appliedOffset = localOffset.clone();
        return () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return null;
            }

            Location eye = player.getEyeLocation();
            Vector forward = eye.getDirection().normalize();
            Vector worldUp = new Vector(0.0D, 1.0D, 0.0D);
            Vector right = forward.clone().crossProduct(worldUp);
            if (right.lengthSquared() < 1.0E-6D) {
                right = new Vector(1.0D, 0.0D, 0.0D);
            } else {
                right.normalize();
            }
            Vector up = right.clone().crossProduct(forward).normalize();

            Vector translated = forward.clone().multiply(distance)
                    .add(right.multiply(appliedOffset.getX()))
                    .add(up.multiply(appliedOffset.getY()))
                    .add(forward.clone().multiply(appliedOffset.getZ()));

            return eye.add(translated);
        };
    }
}
