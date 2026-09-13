package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** ガーディアンプロテクトの対象・肩代わり率・有効期限を管理します。 */
public final class PaladinGuardianProtectRuntimeService {
    private final Map<UUID, Protection> protections = new ConcurrentHashMap<>();

    /** 対象の既存プロテクトを置き換えて、発動者による保護を開始します。 */
    public void protect(
            @NotNull Player protector,
            @NotNull Player target,
            long durationTicks,
            double redirectRatio
    ) {
        long expiresAtMs = System.currentTimeMillis() + Math.max(1L, durationTicks) * 50L;
        protections.put(target.getUniqueId(), new Protection(
                protector.getUniqueId(), expiresAtMs, Math.clamp(redirectRatio, 0.0D, 1.0D)
        ));
    }

    /** 指定した発動者による対象のプロテクトだけを解除します。 */
    public void clear(@NotNull UUID targetId, @NotNull UUID protectorId) {
        protections.computeIfPresent(targetId, (ignored, current) ->
                current.protectorId.equals(protectorId) ? null : current);
    }

    /** 対象が指定発動者から現在プロテクトされているかを返します。 */
    public boolean isActive(@NotNull UUID targetId, @NotNull UUID protectorId) {
        Protection protection = activeProtection(targetId);
        return protection != null && protection.protectorId.equals(protectorId);
    }

    /** 最終HPダメージの10%を対象、90%をプロテクターへ分割します。 */
    public @Nullable DamageShare share(@NotNull AstPlayer target, double finalHealthDamage) {
        if (!(finalHealthDamage > 0.0D)) {
            return null;
        }
        UUID targetId = target.getBukkit().getUniqueId();
        Protection protection = activeProtection(targetId);
        if (protection == null || protection.protectorId.equals(targetId)) {
            return null;
        }
        Player protectorPlayer = Bukkit.getPlayer(protection.protectorId);
        AstPlayer protector = protectorPlayer == null ? null : AstPlayerCache.get(protectorPlayer);
        if (protector == null || !protectorPlayer.isOnline() || protectorPlayer.isDead()
                || protectorPlayer.getWorld() != target.getBukkit().getWorld()) {
            protections.remove(targetId, protection);
            return null;
        }
        double redirected = finalHealthDamage * protection.redirectRatio;
        return new DamageShare(protector, finalHealthDamage - redirected, redirected);
    }

    private @Nullable Protection activeProtection(@NotNull UUID targetId) {
        Protection protection = protections.get(targetId);
        if (protection == null) {
            return null;
        }
        if (protection.expiresAtMs <= System.currentTimeMillis()) {
            protections.remove(targetId, protection);
            return null;
        }
        return protection;
    }

    /** ダメージ分割結果です。 */
    public record DamageShare(@NotNull AstPlayer protector, double targetDamage, double protectorDamage) {
    }

    private record Protection(@NotNull UUID protectorId, long expiresAtMs, double redirectRatio) {
    }
}
