package io.github.maaasu.astralRecord.feature.inventory.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * オンライン中のプレイヤーに対する {@link PlayerInventoryState} を集約するレジストリ。
 * <p>
 * プレイヤー join 時に {@link InventoryPersistence#load(UUID)} で構築した state を登録し、
 * quit 時に削除します。オートセーブタスクはここから全プレイヤーの state を反復します。
 */
public final class PlayerInventoryStateRegistry {

    private final ConcurrentHashMap<UUID, PlayerInventoryState> byAccountId = new ConcurrentHashMap<>();

    /**
     * 指定アカウントの state を登録します。同じアカウントの既存 state は上書きされます。
     *
     * @param state 登録する state
     */
    public void put(@NotNull PlayerInventoryState state) {
        byAccountId.put(state.getAccountId(), state);
    }

    /**
     * 指定アカウントの state を取得します。未登録ならば null を返します。
     *
     * @param accountId 対象アカウントID
     * @return state または null
     */
    public @Nullable PlayerInventoryState get(@NotNull UUID accountId) {
        return byAccountId.get(accountId);
    }

    /**
     * 指定アカウントの state をレジストリから取り除きます。
     *
     * @param accountId 対象アカウントID
     * @return 取り除いた state。未登録なら null
     */
    public @Nullable PlayerInventoryState remove(@NotNull UUID accountId) {
        return byAccountId.remove(accountId);
    }

    /**
     * 現在登録されている全 state のスナップショットを返します。
     *
     * @return state コレクション
     */
    public @NotNull Collection<PlayerInventoryState> all() {
        return byAccountId.values();
    }

    /**
     * 全 state を削除します。プラグイン停止時のクリーンアップに使用してください。
     */
    public void clear() {
        byAccountId.clear();
    }
}
