package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** ファントムの発動単位を有限時間だけ保持し、転職・再読込時の旧効果を無効化します。 */
public final class PhantomArcherCastLifecycle {
    private static final Map<UUID, Set<Cast>> CASTS = new ConcurrentHashMap<>();

    private PhantomArcherCastLifecycle() {
    }

    /**
     * 発射済みの弾が終了するまで有効な発動状態を作成します。メインスレッド専用です。
     * @param context 発動者と共有taskサービス
     * @param lifetimeTicks 全射撃と弾の飛翔を含む最大継続tick
     * @return 取消後の命中を拒否する発動状態
     */
    static @NotNull Cast begin(@NotNull PlayerActiveSkillContext context, long lifetimeTicks) {
        Cast cast = new Cast(context);
        CASTS.computeIfAbsent(cast.owner, ignored -> ConcurrentHashMap.newKeySet()).add(cast);
        context.services().tasks().repeat(cast.owner, cast.expiryScope, Math.max(1L, lifetimeTicks),
                1L, 1, ignored -> { }, cast::close);
        return cast;
    }

    /**
     * 転職した所有者のファントム効果だけを即時終了します。メインスレッド専用です。
     * @param owner 発動者UUID
     */
    public static void clearOwner(@NotNull UUID owner) {
        Set<Cast> casts = CASTS.remove(owner);
        if (casts != null) List.copyOf(casts).forEach(Cast::close);
    }

    /** 成功したマスタ公開後に旧定義の全ファントム効果を終了します。メインスレッド専用です。 */
    public static void clearAll() {
        List.copyOf(CASTS.keySet()).forEach(PhantomArcherCastLifecycle::clearOwner);
    }

    /** task解除・表示解除・発射済み弾の無効化を共有する発動状態です。 */
    static final class Cast {
        private final PlayerActiveSkillContext context;
        private final UUID owner;
        private final String expiryScope = "phantom-archer-expiry:" + UUID.randomUUID();
        private final List<Runnable> cleanup = new ArrayList<>();
        private boolean closed;

        /** 発動者と終了時刻を追跡するscopeを保持します。 */
        private Cast(PlayerActiveSkillContext context) {
            this.context = context;
            this.owner = context.caster().casterId();
        }

        /** 発動が取り消されていない場合だけtrueを返します。 */
        boolean active() {
            return !closed;
        }

        /** 発動を破棄するときだけ、同じ発動の反復taskを停止します。 */
        void trackScope(@NotNull String scope) {
            onClose(() -> context.services().tasks().cancel(owner, scope));
        }

        /** 発動終了時の表示・一時効果の解放処理を1回だけ登録します。 */
        void onClose(@NotNull Runnable action) {
            if (closed) action.run();
            else cleanup.add(action);
        }

        /** 満了・明示取消・共通lifecycle解除のいずれでも、状態と表示を一度だけ破棄します。 */
        void close() {
            if (closed) return;
            closed = true;
            Set<Cast> casts = CASTS.get(owner);
            if (casts != null) {
                casts.remove(this);
                if (casts.isEmpty()) CASTS.remove(owner, casts);
            }
            context.services().tasks().cancel(owner, expiryScope);
            List.copyOf(cleanup).forEach(Runnable::run);
            cleanup.clear();
        }
    }
}
