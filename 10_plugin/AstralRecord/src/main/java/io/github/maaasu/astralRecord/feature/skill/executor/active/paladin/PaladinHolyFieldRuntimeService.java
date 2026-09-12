package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * パラディンのホーリーフィールドの中心・残り時間と範囲内ステータス補正を管理します。
 * <p>
 * 発動者ごとに1つだけフィールドを保持し、範囲外・パーティー離脱・終了時にはこのサービスが付与済み補正を
 * 即時に解除します。ハンマー通常攻撃は発動者が自身のフィールド内にいる場合だけ中心と持続時間を更新します。
 */
public final class PaladinHolyFieldRuntimeService {

    private static final long TEMPORARY_BUFF_DURATION_SECONDS = Integer.MAX_VALUE / 20L;
    private final StatusService statusService;
    private final PartyService partyService;
    private final Map<UUID, FieldState> fieldsByCaster = new HashMap<>();

    /**
     * 必要なステータス・パーティーサービスで初期化します。
     *
     * @param statusService ステータス補正の付与・解除サービス
     * @param partyService パーティー所属確認サービス
     */
    public PaladinHolyFieldRuntimeService(
            @NotNull StatusService statusService,
            @NotNull PartyService partyService
    ) {
        this.statusService = statusService;
        this.partyService = partyService;
    }

    /**
     * フィールドを開始し、既存の同一発動者フィールドがあれば補正を解除して置き換えます。
     *
     * @param caster 発動者
     * @param fieldId 発動単位の一意ID
     * @param center フィールド中心
     * @param radius 水平範囲半径
     * @param durationTicks フィールド持続tick
     * @param casterShieldBreak 発動者へのシールドブレイク補正
     * @param partyShieldBreak パーティーメンバーへのシールドブレイク補正
     */
    public void start(
            @NotNull AstPlayer caster,
            @NotNull String fieldId,
            @NotNull Location center,
            double radius,
            int durationTicks,
            double casterShieldBreak,
            double partyShieldBreak
    ) {
        UUID casterId = caster.getBukkit().getUniqueId();
        end(casterId);
        fieldsByCaster.put(casterId, new FieldState(
                caster,
                fieldId,
                center,
                Math.max(0.0D, radius),
                Math.max(1, durationTicks),
                casterShieldBreak,
                partyShieldBreak
        ));
    }

    /**
     * フィールドを1tick進め、範囲内のプレイヤー補正を同期します。
     *
     * @param casterId 発動者UUID
     * @return 次tickも継続する場合は true
     */
    public boolean advance(@NotNull UUID casterId) {
        FieldState state = fieldsByCaster.get(casterId);
        if (state == null) {
            return false;
        }
        synchronizeBuffs(state);
        state.remainingTicks--;
        return state.remainingTicks > 0;
    }

    /**
     * 現在のフィールド表示に使うスナップショットを返します。
     *
     * @param casterId 発動者UUID
     * @return 現在有効なフィールド。存在しない場合は null
     */
    public @Nullable FieldSnapshot snapshot(@NotNull UUID casterId) {
        FieldState state = fieldsByCaster.get(casterId);
        return state == null ? null : new FieldSnapshot(state.center.clone(), state.radius);
    }

    /**
     * 発動者によるハンマー通常攻撃を処理します。
     * 発動者が自身のフィールド内にいる場合だけ、中心を現在位置へ移し、持続時間を初期値へ戻します。
     *
     * @param caster 通常攻撃を発生させたプレイヤー
     */
    public void onHammerNormalAttack(@NotNull AstPlayer caster) {
        UUID casterId = caster.getBukkit().getUniqueId();
        FieldState state = fieldsByCaster.get(casterId);
        if (state == null || !isWithin(state, caster.getBukkit())) {
            return;
        }
        state.center = caster.getBukkit().getLocation().clone();
        state.remainingTicks = state.durationTicks;
    }

    /**
     * 指定発動者のフィールドと付与済み補正を破棄します。
     *
     * @param casterId 発動者UUID
     */
    public void end(@NotNull UUID casterId) {
        FieldState state = fieldsByCaster.remove(casterId);
        if (state != null) {
            clearAppliedBuffs(state);
        }
    }

    /** フィールドをすべて破棄します。 */
    public void clearAll() {
        for (UUID casterId : Set.copyOf(fieldsByCaster.keySet())) {
            end(casterId);
        }
    }

    private void synchronizeBuffs(@NotNull FieldState state) {
        Map<UUID, BuffTarget> desiredTargets = new HashMap<>();
        Player caster = state.caster.getBukkit();
        if (isWithin(state, caster)) {
            desiredTargets.put(caster.getUniqueId(), new BuffTarget(state.caster, state.casterShieldBreak));
        }
        Party party = partyService.findParty(caster.getUniqueId());
        if (party != null) {
            for (UUID memberId : party.members()) {
                if (memberId.equals(caster.getUniqueId())) {
                    continue;
                }
                Player member = Bukkit.getPlayer(memberId);
                AstPlayer astMember = member == null ? null : AstPlayerCache.get(member);
                if (astMember != null && isWithin(state, member)) {
                    desiredTargets.put(memberId, new BuffTarget(astMember, state.partyShieldBreak));
                }
            }
        }

        for (UUID playerId : Set.copyOf(state.appliedBuffs.keySet())) {
            if (!desiredTargets.containsKey(playerId)) {
                AppliedBuff applied = state.appliedBuffs.remove(playerId);
                statusService.removeBuff(applied.player, applied.buffId);
            }
        }
        for (Map.Entry<UUID, BuffTarget> entry : desiredTargets.entrySet()) {
            AppliedBuff existing = state.appliedBuffs.get(entry.getKey());
            BuffTarget desired = entry.getValue();
            if (existing != null && Double.compare(existing.value, desired.value) == 0) {
                continue;
            }
            if (existing != null) {
                statusService.removeBuff(existing.player, existing.buffId);
            }
            String buffId = state.fieldId + ":" + entry.getKey();
            statusService.applyTemporaryFlatBuff(
                    desired.player,
                    buffId,
                    "ホーリーフィールド",
                    StatusType.SHIELD_BREAK,
                    desired.value,
                    TEMPORARY_BUFF_DURATION_SECONDS
            );
            state.appliedBuffs.put(entry.getKey(), new AppliedBuff(desired.player, buffId, desired.value));
        }
    }

    private void clearAppliedBuffs(@NotNull FieldState state) {
        for (AppliedBuff applied : state.appliedBuffs.values()) {
            statusService.removeBuff(applied.player, applied.buffId);
        }
        state.appliedBuffs.clear();
    }

    private boolean isWithin(@NotNull FieldState state, @Nullable Player player) {
        if (player == null || !player.isOnline() || player.isDead() || state.center.getWorld() == null) {
            return false;
        }
        Location location = player.getLocation();
        if (location.getWorld() == null || !state.center.getWorld().equals(location.getWorld())) {
            return false;
        }
        double deltaX = location.getX() - state.center.getX();
        double deltaZ = location.getZ() - state.center.getZ();
        return deltaX * deltaX + deltaZ * deltaZ <= state.radius * state.radius;
    }

    /** 現在表示するフィールド形状です。 */
    public record FieldSnapshot(@NotNull Location center, double radius) {
    }

    private static final class FieldState {
        private final AstPlayer caster;
        private final String fieldId;
        private final double radius;
        private final int durationTicks;
        private final double casterShieldBreak;
        private final double partyShieldBreak;
        private final Map<UUID, AppliedBuff> appliedBuffs = new HashMap<>();
        private Location center;
        private int remainingTicks;

        private FieldState(
                @NotNull AstPlayer caster,
                @NotNull String fieldId,
                @NotNull Location center,
                double radius,
                int durationTicks,
                double casterShieldBreak,
                double partyShieldBreak
        ) {
            this.caster = caster;
            this.fieldId = fieldId;
            this.center = center.clone();
            this.radius = radius;
            this.durationTicks = durationTicks;
            this.remainingTicks = durationTicks;
            this.casterShieldBreak = casterShieldBreak;
            this.partyShieldBreak = partyShieldBreak;
        }
    }

    private record BuffTarget(@NotNull AstPlayer player, double value) {
    }

    private record AppliedBuff(@NotNull AstPlayer player, @NotNull String buffId, double value) {
    }
}
