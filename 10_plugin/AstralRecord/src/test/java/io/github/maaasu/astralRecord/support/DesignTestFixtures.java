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
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.List;
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
            false
        );
        return new AstPlayer(player, user, account);
    }

    public static InventoryModel inventory(UUID accountId, InventoryType type) {
        LocalDateTime now = LocalDateTime.now();
        UUID actor = UUID.randomUUID();
        return new InventoryModel(
            UUID.randomUUID(),
            accountId,
            type,
            InventoryProfile.GAME.getCode(),
            type.isSlotted() ? 27 : null,
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
            List.of(),
            false,
            false,
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
            0,
            List.of(),
            null,
            List.of(new ItemEquipmentStat(status, type, 0.0D, 0.0D)),
            null,
            null,
            List.of(),
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
            List.of(),
            false,
            false,
            null,
            null,
            equipment,
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
}
