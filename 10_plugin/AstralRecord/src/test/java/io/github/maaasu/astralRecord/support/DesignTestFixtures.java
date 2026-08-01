package io.github.maaasu.astralRecord.support;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 設計書ベースの機能間テストで使う最小 fixture 群です。
 */
public final class DesignTestFixtures {

    private DesignTestFixtures() {
    }

    public static AstPlayer astPlayer(PlayerMock player, AccountMode mode) {
        return astPlayer(player, mode, 0);
    }

    public static AstPlayer astPlayer(PlayerMock player, AccountMode mode, int permission) {
        return astPlayer(player, mode, permission, 1);
    }

    public static AstPlayer astPlayer(PlayerMock player, AccountMode mode, int permission, int level) {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID systemId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UserModel user = new UserModel(
            userId,
            player.getName(),
            now,
            now,
            "127.0.0.1",
            accountId,
            false,
            null,
            false,
            permission,
            now,
            now,
            systemId,
            systemId,
            false
        );
        AccountModel account = new AccountModel(
            accountId,
            userId,
            "test-account",
            0,
            true,
            mode,
            "{}",
            now,
            now,
            systemId,
            systemId,
            false,
            level
        );
        return new AstPlayer(player, user, account);
    }

    public static InventoryModel inventory(UUID accountId, InventoryType type) {
        Integer slotCapacity = null;
        if (type.isSlotted() && type != InventoryType.BAG) {
            slotCapacity = 27;
        }
        return inventory(accountId, type, slotCapacity);
    }

    public static InventoryModel inventory(UUID accountId, InventoryType type, Integer slotCapacity) {
        LocalDateTime now = LocalDateTime.now();
        UUID actor = UUID.randomUUID();
        return new InventoryModel(
            UUID.randomUUID(),
            accountId,
            type,
            InventoryProfile.GAME.getCode(),
            slotCapacity,
            true,
            null,
            now,
            now,
            actor,
            actor,
            false
        );
    }

    public static ItemModel item(String id, ItemCategory category, int maxStack) {
        return new ItemModel(
            1,
            id,
            category.getApiValue(),
            id,
            "PAPER",
            "common",
            maxStack,
            0,
            null,
            null,
            List.of(),
            false,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static ItemModel equipmentItem(String id, String status, ItemEquipmentStatType type) {
        ItemEquipment equipment = new ItemEquipment(
            ItemEquipmentSlot.WEAPON,
            io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType.ONE,
            null,
            0,
            List.of(),
            null,
            List.of(new ItemEquipmentStat(status, type, 0.0D, 0.0D)),
            null,
            null,
            null,
            null,
            List.of()
        );
        return new ItemModel(
            1,
            id,
            ItemCategory.EQUIPMENT.getApiValue(),
            id,
            "IRON_SWORD",
            "common",
            1,
            0,
            null,
            null,
            List.of(),
            false,
            false,
            null,
            null,
            equipment,
            null,
            null,
            null,
            null
        );
    }

    public static EquipmentInstance equipmentInstance(
        UUID instanceId,
        UUID accountId,
        String itemId,
        String status,
        String min,
        String max
    ) {
        String now = LocalDateTime.now().toString();
        return new EquipmentInstance(
            instanceId.toString(),
            accountId.toString(),
            itemId,
            0,
            0,
            0,
            0,
            0,
            now,
            now,
            List.of(new EquipmentStatRoll(UUID.randomUUID().toString(), status, min, max, 0)),
            List.of(),
            List.of(),
            List.of()
        );
    }

    public static RuneInstance runeInstance(UUID instanceId, UUID accountId, String itemId) {
        String now = LocalDateTime.now().toString();
        return new RuneInstance(
            instanceId.toString(),
            accountId.toString(),
            itemId,
            now,
            now,
            List.of()
        );
    }

    public static StatusSnapshot statusSnapshot(Map<StatusType, Double> values, double hp, double mp, double energy) {
        EnumMap<StatusType, StatusValue> snapshotValues = new EnumMap<>(StatusType.class);
        values.forEach((type, value) -> snapshotValues.put(type, new StatusValue(value, 0.0D)));
        return new StatusSnapshot(snapshotValues, hp, mp, energy, 0.0D, 0L, LocalDateTime.now());
    }

    public static MobInstance mobInstance(double maxHealth, double defense, double magicDefense) {
        return mobInstance(1, maxHealth, defense, magicDefense, MobShieldConfig.EMPTY);
    }

    public static MobInstance mobInstance(
        double maxHealth,
        double defense,
        double magicDefense,
        MobShieldConfig shield
    ) {
        return mobInstance(1, maxHealth, defense, magicDefense, shield);
    }

    public static MobInstance mobInstance(
        int level,
        double maxHealth,
        double defense,
        double magicDefense,
        MobShieldConfig shield
    ) {
        MobTemplate template = new MobTemplate(
            1,
            "test_mob",
            MobCategory.ENEMY,
            "Test Mob",
            null,
            level,
            EntityType.ZOMBIE,
            false,
            "ZOMBIE_HEAD",
            List.of(),
            List.of(),
            null,
            MobEquipmentConfig.EMPTY,
            List.of(
                new MobBaseStat(StatusType.MAX_HEALTH.name(), maxHealth),
                new MobBaseStat(StatusType.DEFENSE.name(), defense),
                new MobBaseStat(StatusType.MAGIC_DEFENSE.name(), magicDefense)
            ),
            shield,
            MobIdleConfig.defaults(),
            false,
            MobInteractionsConfig.EMPTY,
            null,
            null,
            null
        );
        return new MobInstance(UUID.randomUUID(), template, new Location(null, 0.0D, 0.0D, 0.0D));
    }
}
