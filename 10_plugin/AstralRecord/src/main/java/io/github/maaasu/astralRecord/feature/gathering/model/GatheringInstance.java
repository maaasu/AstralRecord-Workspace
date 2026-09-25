package io.github.maaasu.astralRecord.feature.gathering.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GatheringInstance {
    private final UUID instanceId;
    private final GatheringDefinition definition;
    private final Location location;
    private final String sourceSpawnerId;
    private int currentHealth;
    private final Set<UUID> activePlayerIds = new LinkedHashSet<>();
    private final Set<UUID> contributorPlayerIds = new LinkedHashSet<>();
    private final Map<UUID, String> contributorEquipmentInstanceIds = new LinkedHashMap<>();

    public GatheringInstance(
        @NotNull UUID instanceId,
        @NotNull GatheringDefinition definition,
        @NotNull Location location,
        @Nullable String sourceSpawnerId
    ) {
        this.instanceId = instanceId;
        this.definition = definition;
        this.location = location.clone();
        this.sourceSpawnerId = sourceSpawnerId;
        this.currentHealth = definition.maxHealth();
    }

    public @NotNull UUID instanceId() {
        return instanceId;
    }

    public @NotNull GatheringDefinition definition() {
        return definition;
    }

    public @NotNull Location location() {
        return location.clone();
    }

    /** この採集物を生成したスポナー ID。手動生成時は {@code null}。 */
    public @Nullable String sourceSpawnerId() {
        return sourceSpawnerId;
    }

    public int currentHealth() {
        return currentHealth;
    }

    /**
     * 採集へ寄与したプレイヤーを記録し、採集オブジェクトのHPを減らします。
     *
     * @param playerId 採集ダメージを与えたプレイヤーUUID
     * @param equipmentInstanceId 採集に使用した装備個体ID。永続装備でない場合はnull
     * @param amount 採集ダメージ。1未満は1へ補正します。
     */
    public void damage(@NotNull UUID playerId, @Nullable String equipmentInstanceId, int amount) {
        contributorPlayerIds.add(playerId);
        if (equipmentInstanceId == null || equipmentInstanceId.isBlank()) {
            contributorEquipmentInstanceIds.remove(playerId);
        } else {
            contributorEquipmentInstanceIds.put(playerId, equipmentInstanceId);
        }
        currentHealth = Math.max(0, currentHealth - Math.max(1, amount));
    }

    /** HP・参加者・貢献者を生成直後の状態へ戻します。 */
    public void resetHealth() {
        currentHealth = definition.maxHealth();
        activePlayerIds.clear();
        contributorPlayerIds.clear();
        contributorEquipmentInstanceIds.clear();
    }

    /**
     * 採集中のプレイヤーを追加します。
     *
     * @param playerId 採集を開始したプレイヤーUUID
     */
    public void addActivePlayer(@NotNull UUID playerId) {
        activePlayerIds.add(playerId);
    }

    /**
     * 採集中のプレイヤーを除外します。
     *
     * @param playerId 採集を終了したプレイヤーUUID
     */
    public void removeActivePlayer(@NotNull UUID playerId) {
        activePlayerIds.remove(playerId);
    }

    /** @return 指定プレイヤーが現在この採集オブジェクトを採集中ならtrue */
    public boolean hasActivePlayer(@NotNull UUID playerId) {
        return activePlayerIds.contains(playerId);
    }

    /** @return 現在この採集オブジェクトを採集中のプレイヤーUUID一覧 */
    public @NotNull Set<UUID> activePlayerIds() {
        return Set.copyOf(activePlayerIds);
    }

    /** @return リセット後に採集ダメージを与えたプレイヤーUUID一覧 */
    public @NotNull Set<UUID> contributorPlayerIds() {
        return Set.copyOf(contributorPlayerIds);
    }

    /**
     * 指定プレイヤーが最後に採集ダメージを与えた装備個体IDを返します。
     *
     * @param playerId 貢献者UUID
     * @return 装備個体ID。永続装備を使用していない場合はnull
     */
    public @Nullable String contributorEquipmentInstanceId(@NotNull UUID playerId) {
        return contributorEquipmentInstanceIds.get(playerId);
    }
}
