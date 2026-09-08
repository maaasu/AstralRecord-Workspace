package io.github.maaasu.astralRecord.feature.inventory.state;

import com.google.gson.*;
import io.github.maaasu.astralRecord.feature.inventory.model.*;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateAcknowledgementException;
import io.github.maaasu.astralRecord.infrastructure.util.ApiDateTimeUtil;

import java.time.LocalDateTime;
import java.util.*;

/** stateロック下で捕捉し、通信中は変更しないプレイヤー完成状態です。 */
final class PlayerStateSnapshot {
    final UUID snapshotId = UUID.randomUUID();
    final UUID accountId;
    final List<InventoryModel> inventories;
    final Set<UUID> loadoutIds = new HashSet<>();
    final Map<UUID, List<InventoryEntryModel>> entries = new LinkedHashMap<>();
    final List<EquipmentInstance> equipment;
    final List<PlayerStateSection> sections;
    final String payload;

    PlayerStateSnapshot(PlayerInventoryState state, List<EquipmentInstance> equipment,
                        List<PlayerStateSection> sections, Map<UUID, Set<UUID>> persistedEntries,
                        Map<UUID, LocalDateTime> persistedVersions) {
        this(state, equipment, sections, persistedEntries, persistedVersions,
            state.snapshotInventories().stream().map(InventoryModel::getInventoryId).collect(java.util.stream.Collectors.toSet()),
            state.snapshotLoadouts(InventoryProfile.GAME).stream().map(EquipmentLoadoutModel::getEquipmentLoadoutId)
                .collect(java.util.stream.Collectors.toSet()), Set.of(), false);
    }

    PlayerStateSnapshot(PlayerInventoryState state, List<EquipmentInstance> equipment,
                        List<PlayerStateSection> sections, Map<UUID, Set<UUID>> persistedEntries,
                        Map<UUID, LocalDateTime> persistedVersions, boolean includePendingInventories) {
        this(state, equipment, sections, persistedEntries, persistedVersions,
            state.snapshotInventories().stream().map(InventoryModel::getInventoryId).collect(java.util.stream.Collectors.toSet()),
            state.snapshotLoadouts(InventoryProfile.GAME).stream().map(EquipmentLoadoutModel::getEquipmentLoadoutId)
                .collect(java.util.stream.Collectors.toSet()), Set.of(), includePendingInventories);
    }

    PlayerStateSnapshot(PlayerInventoryState state, List<EquipmentInstance> equipment,
                        List<PlayerStateSection> sections, Map<UUID, Set<UUID>> persistedEntries,
                        Map<UUID, LocalDateTime> persistedVersions, Set<String> pendingEquipmentCreationIds,
                        boolean includePendingInventories) {
        this(state, equipment, sections, persistedEntries, persistedVersions,
            state.snapshotInventories().stream().map(InventoryModel::getInventoryId).collect(java.util.stream.Collectors.toSet()),
            state.snapshotLoadouts(InventoryProfile.GAME).stream().map(EquipmentLoadoutModel::getEquipmentLoadoutId)
                .collect(java.util.stream.Collectors.toSet()), pendingEquipmentCreationIds, includePendingInventories);
    }

    PlayerStateSnapshot(PlayerInventoryState state, List<EquipmentInstance> equipment,
                        List<PlayerStateSection> sections, Map<UUID, Set<UUID>> persistedEntries,
                        Map<UUID, LocalDateTime> persistedVersions,
                        Set<UUID> persistedInventoryIds, Set<UUID> persistedLoadoutIds,
                        Set<String> pendingEquipmentCreationIds,
                        boolean includePendingInventories) {
        this.accountId = state.getAccountId();
        this.equipment = List.copyOf(equipment);
        this.sections = List.copyOf(sections);
        boolean inventoryDirty = state.isDirty() || includePendingInventories;
        this.inventories = inventoryDirty ? state.snapshotInventories().stream()
            .filter(value -> value.isEnabled() && !value.isDeleted()).toList() : List.of();
        JsonObject body = new JsonObject();
        body.addProperty("snapshotId", snapshotId.toString());
        body.addProperty("accountId", accountId.toString());
        body.addProperty("updatedBy", accountId.toString());
        JsonArray inventoryArray = new JsonArray();
        Set<UUID> persistedEntryIds = new HashSet<>();
        persistedEntries.values().forEach(persistedEntryIds::addAll);
        Set<UUID> metadataDirty = new HashSet<>();
        state.snapshotDirtyMetadataInventories().forEach(value -> metadataDirty.add(value.getInventoryId()));
        for (InventoryModel inventory : inventories) {
            boolean isNew = !persistedInventoryIds.contains(inventory.getInventoryId());
            JsonObject object = new JsonObject();
            object.addProperty("inventoryId", inventory.getInventoryId().toString());
            object.addProperty("isNew", isNew);
            if (isNew) {
                object.add("expectedUpdatedAt", JsonNull.INSTANCE);
                object.addProperty("inventoryType", inventory.getInventoryType().getCode());
                object.addProperty("inventoryProfile", inventory.getInventoryProfile());
                if (inventory.getSlotCapacity() == null) object.add("slotCapacity", JsonNull.INSTANCE);
                else object.addProperty("slotCapacity", inventory.getSlotCapacity());
                object.addProperty("isEnabled", inventory.isEnabled());
            } else {
                object.addProperty("expectedUpdatedAt", inventory.getUpdatedAt().toString());
            }
            object.addProperty("metadataDirty", !isNew && metadataDirty.contains(inventory.getInventoryId()));
            object.addProperty("metadataJson", inventory.getMetadataJson());
            JsonArray expectedRows = new JsonArray();
            persistedEntries.getOrDefault(inventory.getInventoryId(), Set.of()).stream().sorted()
                .forEach(id -> {
                    JsonObject expected = new JsonObject();
                    expected.addProperty("inventoryEntryId", id.toString());
                    expected.addProperty("updatedAt", Objects.requireNonNull(persistedVersions.get(id), "Missing entry baseline version").toString());
                    expectedRows.add(expected);
                });
            object.add("expectedEntries", expectedRows);
            List<InventoryEntryModel> captured = state.snapshotEntries(inventory.getInventoryId()).stream()
                .filter(value -> !value.isDeleted()).toList();
            entries.put(inventory.getInventoryId(), captured);
            JsonArray entryArray = new JsonArray();
            for (InventoryEntryModel entry : captured) {
                JsonObject row = new JsonObject();
                row.addProperty("inventoryEntryId", entry.getInventoryEntryId().toString());
                row.addProperty("expectedUpdatedAt", persistedEntryIds.contains(entry.getInventoryEntryId())
                    ? entry.getUpdatedAt().toString() : null);
                row.addProperty("slotIndex", entry.getSlotIndex());
                row.addProperty("itemCategory", entry.getItemCategory());
                row.addProperty("itemId", entry.getItemId());
                row.addProperty("instanceType", entry.getInstanceType());
                row.addProperty("instanceId", entry.getInstanceId() == null ? null : entry.getInstanceId().toString());
                row.addProperty("quantity", entry.getQuantity());
                row.addProperty("metadataJson", entry.getMetadataJson());
                entryArray.add(row);
            }
            object.add("entries", entryArray);
            inventoryArray.add(object);
        }
        body.add("inventories", inventoryArray);
        JsonArray loadouts = new JsonArray();
        for (EquipmentLoadoutModel loadout : inventoryDirty ? state.snapshotLoadouts(InventoryProfile.GAME) : List.<EquipmentLoadoutModel>of()) {
            if (loadout.isDeleted()) continue;
            loadoutIds.add(loadout.getEquipmentLoadoutId());
            boolean isNew = !persistedLoadoutIds.contains(loadout.getEquipmentLoadoutId());
            JsonObject object = new JsonObject();
            object.addProperty("equipmentLoadoutId", loadout.getEquipmentLoadoutId().toString());
            object.addProperty("isNew", isNew);
            if (isNew) {
                object.add("expectedUpdatedAt", JsonNull.INSTANCE);
                object.addProperty("loadoutProfile", loadout.getLoadoutProfile());
                object.addProperty("loadoutName", loadout.getLoadoutName());
                object.addProperty("sortOrder", loadout.getSortOrder());
                object.addProperty("isActive", loadout.isActive());
                object.addProperty("metadataJson", loadout.getMetadataJson());
            } else {
                object.addProperty("expectedUpdatedAt", loadout.getUpdatedAt().toString());
            }
            JsonArray slots = new JsonArray();
            for (EquipmentLoadoutSlotModel slot : loadout.getSlots()) {
                if (slot.isDeleted()) continue;
                JsonObject row = new JsonObject();
                row.addProperty("slotType", slot.getSlotType());
                row.addProperty("slotIndex", slot.getSlotIndex());
                row.addProperty("equipmentInstanceId", slot.getEquipmentInstanceId().toString());
                slots.add(row);
            }
            object.add("slots", slots);
            loadouts.add(object);
        }
        body.add("loadouts", loadouts);
        JsonArray equipmentArray = new JsonArray();
        Gson gson = new Gson();
        for (EquipmentInstance instance : equipment) {
            boolean isNew = pendingEquipmentCreationIds.contains(
                instance.getEquipmentInstanceId().trim().toLowerCase(Locale.ROOT));
            JsonObject object = new JsonObject();
            object.addProperty("equipmentInstanceId", instance.getEquipmentInstanceId());
            object.addProperty("isNew", isNew);
            object.addProperty("itemId", isNew ? instance.getItemId() : null);
            object.addProperty("expectedUpdatedAt", isNew ? null : instance.getUpdatedAt());
            object.addProperty("enhanceLevel", instance.getEnhanceLevel());
            object.addProperty("runeMaxSlots", instance.getRuneMaxSlots());
            object.addProperty("transcendenceRank", instance.getTranscendenceRank());
            boolean withoutDurability = instance.getDurabilityMax() == 0 && instance.getDurabilityValue() == 0;
            object.addProperty("durabilityMax", withoutDurability ? null : Integer.valueOf(instance.getDurabilityMax()));
            object.addProperty("durabilityValue", withoutDurability ? null : Integer.valueOf(instance.getDurabilityValue()));
            object.add("statRolls", isNew ? gson.toJsonTree(instance.getStatRolls()) : new JsonArray());
            object.add("enchants", gson.toJsonTree(instance.getEnchants()));
            object.add("runes", gson.toJsonTree(instance.getRunes()));
            equipmentArray.add(object);
        }
        body.add("equipment", equipmentArray);
        for (PlayerStateSection section : sections) {
            if (body.has(section.name())) throw new IllegalArgumentException("Duplicate player state section: " + section.name());
            body.add(section.name(), section.payload());
        }
        payload = body.toString();
    }

    void validateAck(JsonObject ack) {
        validatePayloadAck(payload, ack);
        if (!snapshotId.toString().equals(ack.get("snapshotId").getAsString())) {
            throw new IllegalStateException("Snapshot acknowledgement ID mismatch");
        }
        Map<UUID, LocalDateTime> versions = versions(ack, "entries", "inventoryEntryId");
        for (List<InventoryEntryModel> list : entries.values()) {
            for (InventoryEntryModel entry : list) {
                if (!versions.containsKey(entry.getInventoryEntryId())) {
                    throw new IllegalStateException("Missing inventory entry acknowledgement");
                }
            }
        }
        Set<UUID> inventoryVersions = versions(ack, "inventories", "inventoryId").keySet();
        for (InventoryModel inventory : inventories) {
            if (!inventoryVersions.contains(inventory.getInventoryId())) {
                throw new IllegalStateException("Missing inventory acknowledgement");
            }
        }
        if (!versions(ack, "loadouts", "equipmentLoadoutId").keySet().containsAll(loadoutIds)) {
            throw new IllegalStateException("Missing loadout acknowledgement");
        }
        Set<UUID> equipmentVersions = versions(ack, "equipment", "equipmentInstanceId").keySet();
        for (EquipmentInstance instance : equipment) {
            if (!equipmentVersions.contains(UUID.fromString(instance.getEquipmentInstanceId()))) {
                throw new IllegalStateException("Missing equipment acknowledgement");
            }
        }
        for (PlayerStateSection section : sections) {
            if (!ack.has(section.name())) throw new IllegalStateException("Missing player section acknowledgement");
        }
    }

    /** 再起動時も送信本文そのものを基準に、全明細のACKを検証します。 */
    static void validatePayloadAck(String payload, JsonObject ack) {
        try {
            JsonObject request = JsonParser.parseString(payload).getAsJsonObject();
            requireEqual(request.get("snapshotId"), ack.get("snapshotId"));
            requireEqual(request.get("accountId"), ack.get("accountId"));
            requireRows(request.getAsJsonArray("inventories"), ack, "inventories", "inventoryId");
            requireRows(request.getAsJsonArray("loadouts"), ack, "loadouts", "equipmentLoadoutId");
            requireRows(request.getAsJsonArray("equipment"), ack, "equipment", "equipmentInstanceId");
            Set<String> activeIds = new HashSet<>();
            Set<String> expectedIds = new HashSet<>();
            for (JsonElement inventory : request.getAsJsonArray("inventories")) {
                JsonObject row = inventory.getAsJsonObject();
                for (JsonElement entry : row.getAsJsonArray("entries"))
                    activeIds.add(entry.getAsJsonObject().get("inventoryEntryId").getAsString());
                for (JsonElement entry : row.getAsJsonArray("expectedEntries"))
                    expectedIds.add(entry.getAsJsonObject().get("inventoryEntryId").getAsString());
            }
            expectedIds.addAll(activeIds);
            Map<UUID, LocalDateTime> entryVersions = versions(ack, "entries", "inventoryEntryId");
            Set<UUID> expectedEntryIds = expectedIds.stream().map(UUID::fromString).collect(
                java.util.stream.Collectors.toSet());
            if (!entryVersions.keySet().equals(expectedEntryIds)) {
                throw new IllegalStateException("Inventory entry acknowledgement key mismatch");
            }
            Map<String, Boolean> deleted = new HashMap<>();
            for (JsonElement entry : ack.getAsJsonArray("entries")) {
                JsonObject row = entry.getAsJsonObject();
                deleted.put(row.get("inventoryEntryId").getAsString(), row.get("isDeleted").getAsBoolean());
            }
            for (String id : expectedIds) {
                if (!entryVersions.containsKey(UUID.fromString(id))
                    || !Objects.equals(deleted.get(id), !activeIds.contains(id)))
                    throw new IllegalStateException("Missing or inconsistent entry acknowledgement");
            }
            for (String name : List.of(
                "learnedSkills", "skillBindPresets", "skillTree", "accountProgress", "waystones",
                "questState", "loginBonusClaims", "guideProgress", "adventureRecords", "playerSettings",
                "mailClaim", "mailDelete"
            )) {
                if (!request.has(name)) continue;
                JsonObject section = request.getAsJsonObject(name);
                JsonObject received = ack.getAsJsonObject(name);
                requireEqual(section.get("clientRevision"), received.get("clientRevision"));
                switch (name) {
                    case "learnedSkills" -> {
                        requireSectionVersions(section.getAsJsonArray("skills"), received, "learnedSkillId");
                        requireApiDateTimes(received.getAsJsonArray("entries"), "updatedAt");
                        Set<UUID> requestedDeletedIds = new HashSet<>();
                        for (JsonElement row : section.getAsJsonArray("deletedSkills")) {
                            if (!requestedDeletedIds.add(UUID.fromString(
                                row.getAsJsonObject().get("learnedSkillId").getAsString()))) {
                                throw new IllegalStateException("Duplicate deleted skill request");
                            }
                        }
                        Set<UUID> receivedDeletedIds = new HashSet<>();
                        for (JsonElement id : received.getAsJsonArray("deletedIds")) {
                            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()
                                || !receivedDeletedIds.add(UUID.fromString(id.getAsString()))) {
                                throw new IllegalStateException("Invalid deleted skill acknowledgement");
                            }
                        }
                        if (!requestedDeletedIds.equals(receivedDeletedIds)) {
                            throw new IllegalStateException("Deleted skill acknowledgement mismatch");
                        }
                    }
                    case "skillBindPresets" -> requireSectionVersions(section.getAsJsonArray("presets"), received, "presetIndex");
                    case "skillTree" -> requireVersion(section, received, "expectedVersion", "version");
                    case "accountProgress" -> requireVersion(section, received, "expectedProgressVersion", "progressVersion");
                    case "waystones" -> {
                        requireEqual(section.get("unlockedWaystoneIds"), received.get("unlockedWaystoneIds"));
                    }
                    case "questState" -> requireVersion(section, received, "expectedVersion", "version");
                    case "loginBonusClaims" -> {
                        Set<String> requestedDates = new HashSet<>();
                        for (JsonElement date : section.getAsJsonArray("claimDates")) {
                            requestedDates.add(date.getAsString());
                        }
                        Set<String> receivedDates = new HashSet<>();
                        for (JsonElement claim : received.getAsJsonArray("claims")) {
                            receivedDates.add(claim.getAsJsonObject().get("claimDate").getAsString());
                        }
                        if (!requestedDates.equals(receivedDates)) {
                            throw new IllegalStateException("Login bonus acknowledgement mismatch");
                        }
                    }
                    case "guideProgress" -> requireCompositeKeyRows(
                        section.getAsJsonArray("completedStepKeys"), received.getAsJsonArray("completedStepKeys"),
                        "guideId", "stepId");
                    case "adventureRecords" -> {
                        requireCumulativeRows(section.getAsJsonArray("mobDefeatDeltas"),
                            received.getAsJsonArray("mobDefeats"), "mobId", "delta", "defeatCount");
                        requireCumulativeRows(section.getAsJsonArray("dungeonClearDeltas"),
                            received.getAsJsonArray("dungeonClears"), "dungeonId", "delta", "clearCount");
                    }
                    case "playerSettings" -> requirePlayerSettingVersions(
                        section.getAsJsonArray("settings"), received.getAsJsonArray("settings"));
                    case "mailClaim" -> {
                        requireEqual(section.get("mailId"), received.get("mailId"));
                        if (!received.has("version") || received.get("version").getAsInt() < 2
                            || !received.has("readAt") || received.get("readAt").isJsonNull()) {
                            throw new IllegalStateException("Mail claim acknowledgement mismatch");
                        }
                    }
                    case "mailDelete" -> {
                        requireEqual(section.get("mailId"), received.get("mailId"));
                        if (!received.has("version") || received.get("version").getAsInt() < 2
                            || !received.has("deletedAt") || received.get("deletedAt").isJsonNull()) {
                            throw new IllegalStateException("Mail delete acknowledgement mismatch");
                        }
                    }
                    default -> throw new IllegalStateException("Unsupported section");
                }
            }
        } catch (RuntimeException invalid) {
            throw new PlayerStateAcknowledgementException(invalid);
        }
    }

    private static void requireEqual(JsonElement expected, JsonElement actual) {
        if (expected == null || actual == null || !expected.equals(actual))
            throw new IllegalStateException("Acknowledgement identity mismatch");
    }

    private static void requireRows(JsonArray requested, JsonObject ack, String array, String id) {
        Set<UUID> received = versions(ack, array, id).keySet();
        Set<UUID> expected = new HashSet<>();
        for (JsonElement row : requested) {
            if (!expected.add(UUID.fromString(row.getAsJsonObject().get(id).getAsString()))) {
                throw new IllegalStateException("Duplicate " + array + " request");
            }
        }
        if (!expected.equals(received)) {
            throw new IllegalStateException(array + " acknowledgement key mismatch");
        }
    }

    private static void requireSectionVersions(JsonArray requested, JsonObject ack, String id) {
        Map<String, JsonObject> received = new HashMap<>();
        for (JsonElement row : ack.getAsJsonArray("entries")) {
            JsonObject value = row.getAsJsonObject();
            if (received.put(value.get(id).getAsString(), value) != null)
                throw new IllegalStateException("Duplicate section acknowledgement");
        }
        Set<String> requestedIds = new HashSet<>();
        for (JsonElement row : requested) {
            JsonObject value = row.getAsJsonObject();
            String requestedId = value.get(id).getAsString();
            if (!requestedIds.add(requestedId)) {
                throw new IllegalStateException("Duplicate section request");
            }
            requireVersion(value, received.get(requestedId), "expectedVersion", "version");
        }
        if (!requestedIds.equals(received.keySet())) {
            throw new IllegalStateException("Section acknowledgement key mismatch");
        }
    }

    private static void requireApiDateTimes(JsonArray rows, String field) {
        for (JsonElement element : rows) {
            JsonElement value = element.getAsJsonObject().get(field);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException("Missing section acknowledgement timestamp");
            }
            ApiDateTimeUtil.parseLocalDateTime(value.getAsString());
        }
    }

    private static void requireCompositeKeyRows(
        JsonArray requested,
        JsonArray acknowledged,
        String firstKey,
        String secondKey
    ) {
        Set<String> expected = textCompositeKeys(requested, firstKey, secondKey);
        Set<String> received = textCompositeKeys(acknowledged, firstKey, secondKey);
        if (!expected.equals(received)) {
            throw new IllegalStateException("Section acknowledgement key mismatch");
        }
    }

    private static Set<String> textCompositeKeys(JsonArray rows, String firstKey, String secondKey) {
        Set<String> result = new HashSet<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            String key = row.get(firstKey).getAsString() + '\u001f' + row.get(secondKey).getAsString();
            if (!result.add(key)) {
                throw new IllegalStateException("Duplicate section acknowledgement key");
            }
        }
        return result;
    }

    private static void requireCumulativeRows(
        JsonArray requested,
        JsonArray acknowledged,
        String keyName,
        String deltaName,
        String countName
    ) {
        Map<String, Long> expected = longValues(requested, keyName, deltaName);
        Map<String, Long> received = longValues(acknowledged, keyName, countName);
        if (!expected.keySet().equals(received.keySet())) {
            throw new IllegalStateException("Section acknowledgement key mismatch");
        }
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            if (received.get(entry.getKey()) < entry.getValue()) {
                throw new IllegalStateException("Section acknowledgement counter is stale");
            }
        }
    }

    private static Map<String, Long> longValues(JsonArray rows, String keyName, String valueName) {
        Map<String, Long> result = new HashMap<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            String key = row.get(keyName).getAsString().toLowerCase(Locale.ROOT);
            JsonElement rawValue = row.get(valueName);
            if (rawValue == null || !rawValue.isJsonPrimitive()
                || !rawValue.getAsJsonPrimitive().isNumber()) {
                throw new IllegalStateException("Section acknowledgement counter is missing");
            }
            long value = rawValue.getAsBigDecimal().longValueExact();
            if (result.put(key, value) != null) {
                throw new IllegalStateException("Duplicate section acknowledgement key");
            }
        }
        return result;
    }

    private static void requirePlayerSettingVersions(JsonArray requested, JsonArray acknowledged) {
        Map<String, JsonObject> received = new HashMap<>();
        for (JsonElement element : acknowledged) {
            JsonObject row = element.getAsJsonObject();
            if (received.put(row.get("userSettingId").getAsString(), row) != null) {
                throw new IllegalStateException("Duplicate player setting acknowledgement");
            }
        }
        if (received.size() != requested.size()) {
            throw new IllegalStateException("Player setting acknowledgement size mismatch");
        }
        for (JsonElement element : requested) {
            JsonObject row = element.getAsJsonObject();
            JsonObject ack = received.get(row.get("userSettingId").getAsString());
            requireEqual(row.get("settingKey"), ack == null ? null : ack.get("settingKey"));
            requireVersion(row, ack, "expectedVersion", "version");
        }
    }

    private static void requireVersion(JsonObject request, JsonObject ack, String expectedKey, String versionKey) {
        JsonElement expected = request.get(expectedKey);
        int minimum = expected == null || expected.isJsonNull() ? 0 : expected.getAsInt();
        if (ack == null || ack.get(versionKey).getAsInt() <= minimum)
            throw new IllegalStateException("Missing or stale section version");
    }

    InventoryPersistence.PersistedInventoryBaseline baseline(JsonObject ack) {
        Map<UUID, LocalDateTime> versions = versions(ack, "entries", "inventoryEntryId");
        Map<UUID, List<InventoryEntryModel>> result = new LinkedHashMap<>();
        entries.forEach((inventoryId, captured) -> result.put(inventoryId, captured.stream().map(entry ->
            new InventoryEntryModel(entry.getInventoryEntryId(), entry.getInventoryId(), entry.getSlotIndex(),
                entry.getItemCategory(), entry.getItemId(), entry.getInstanceType(), entry.getInstanceId(),
                entry.getQuantity(), entry.getMetadataJson(), entry.getCreatedAt(),
                versions.get(entry.getInventoryEntryId()), entry.getCreatedBy(), entry.getUpdatedBy(), false)).toList()));
        return new InventoryPersistence.PersistedInventoryBaseline(accountId, result);
    }

    static Map<UUID, LocalDateTime> versions(JsonObject ack, String array, String id) {
        Map<UUID, LocalDateTime> result = new HashMap<>();
        for (JsonElement element : ack.getAsJsonArray(array)) {
            JsonObject row = element.getAsJsonObject();
            String text = row.get("updatedAt").getAsString();
            LocalDateTime time = ApiDateTimeUtil.parseLocalDateTime(text);
            if (result.put(UUID.fromString(row.get(id).getAsString()), time) != null)
                throw new IllegalStateException("Duplicate acknowledgement row");
        }
        return result;
    }
}
