package io.github.maaasu.astralRecord.feature.inventory.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 不変の送信中本文と、後続の最新完成状態を一つのファイルで保持する記録です。 */
record PlayerStateJournal(String inFlight, String successor) {
    void validateAccount(java.util.UUID accountId) {
        validateAccount(inFlight, accountId);
        if (successor != null) validateAccount(successor, accountId);
    }

    private static void validateAccount(String payload, java.util.UUID accountId) {
        JsonObject body = JsonParser.parseString(payload).getAsJsonObject();
        if (!accountId.equals(java.util.UUID.fromString(body.get("accountId").getAsString())))
            throw new IllegalStateException("Player-state journal account mismatch");
    }

    String encode() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("format", 2);
        envelope.add("inFlight", JsonParser.parseString(inFlight));
        if (successor != null) envelope.add("successor", JsonParser.parseString(successor));
        return envelope.toString();
    }

    static PlayerStateJournal decode(String stored) {
        JsonObject envelope = JsonParser.parseString(stored).getAsJsonObject();
        // 初期の単一snapshotファイルはそのまま再送できる。
        if (envelope.has("snapshotId")) return new PlayerStateJournal(stored, null);
        if (envelope.get("format").getAsInt() != 2)
            throw new IllegalStateException("Unsupported player-state journal");
        return new PlayerStateJournal(envelope.getAsJsonObject("inFlight").toString(),
            envelope.has("successor") ? envelope.getAsJsonObject("successor").toString() : null);
    }

    static boolean sameState(String first, String second) {
        JsonObject left = JsonParser.parseString(first).getAsJsonObject();
        JsonObject right = JsonParser.parseString(second).getAsJsonObject();
        left.remove("snapshotId");
        right.remove("snapshotId");
        return left.equals(right);
    }

    /** 先行ACKから後続本文の期待版だけを更新する。数量・強化値・UUIDは再計算しない。 */
    String advance(JsonObject acknowledgement) {
        JsonObject previous = JsonParser.parseString(inFlight).getAsJsonObject();
        JsonObject next = JsonParser.parseString(successor).getAsJsonObject();
        Map<String, JsonObject> entries = indexed(acknowledgement.getAsJsonArray("entries"), "inventoryEntryId");
        Map<String, JsonObject> inventories = indexed(acknowledgement.getAsJsonArray("inventories"), "inventoryId");
        Map<String, JsonObject> previousInventories = indexed(previous.getAsJsonArray("inventories"), "inventoryId");
        for (JsonElement element : next.getAsJsonArray("inventories")) {
            JsonObject inventory = element.getAsJsonObject();
            String id = inventory.get("inventoryId").getAsString();
            updateTimestamp(inventory, inventories.get(id));
            JsonObject prior = previousInventories.get(id);
            if (prior != null) {
                JsonArray expected = new JsonArray();
                for (JsonElement row : prior.getAsJsonArray("entries")) {
                    String entryId = row.getAsJsonObject().get("inventoryEntryId").getAsString();
                    JsonObject value = new JsonObject();
                    value.addProperty("inventoryEntryId", entryId);
                    value.add("updatedAt", entries.get(entryId).get("updatedAt").deepCopy());
                    expected.add(value);
                }
                inventory.add("expectedEntries", expected);
            }
            for (JsonElement row : inventory.getAsJsonArray("entries")) {
                JsonObject entry = row.getAsJsonObject();
                JsonObject received = entries.get(entry.get("inventoryEntryId").getAsString());
                if (received != null) {
                    if (received.get("isDeleted").getAsBoolean()) entry.add("expectedUpdatedAt", com.google.gson.JsonNull.INSTANCE);
                    else updateTimestamp(entry, received);
                }
            }
        }
        rebaseTimestamps(next, acknowledgement, "equipment", "equipmentInstanceId");
        rebaseTimestamps(next, acknowledgement, "loadouts", "equipmentLoadoutId");
        if (next.has("learnedSkills") && acknowledgement.has("learnedSkills")) {
            JsonObject section = next.getAsJsonObject("learnedSkills");
            JsonObject ack = acknowledgement.getAsJsonObject("learnedSkills");
            Map<String, JsonObject> versions = indexed(ack.getAsJsonArray("entries"), "learnedSkillId");
            Set<String> active = new HashSet<>();
            for (JsonElement row : section.getAsJsonArray("skills")) {
                JsonObject skill = row.getAsJsonObject();
                String id = skill.get("learnedSkillId").getAsString();
                active.add(id);
                updateVersion(skill, versions.get(id), "expectedVersion", "version");
            }
            Set<String> alreadyDeleted = new HashSet<>();
            for (JsonElement id : ack.getAsJsonArray("deletedIds")) alreadyDeleted.add(id.getAsString());
            Map<String, JsonObject> deletes = indexed(section.getAsJsonArray("deletedSkills"), "learnedSkillId");
            alreadyDeleted.forEach(deletes::remove);
            versions.forEach((id, received) -> {
                if (!active.contains(id)) {
                    JsonObject removed = deletes.computeIfAbsent(id, ignored -> {
                        JsonObject value = new JsonObject();
                        value.addProperty("learnedSkillId", id);
                        return value;
                    });
                    updateVersion(removed, received, "expectedVersion", "version");
                }
            });
            JsonArray deleted = new JsonArray();
            deletes.keySet().stream().sorted().forEach(id -> deleted.add(deletes.get(id)));
            section.add("deletedSkills", deleted);
        }
        if (next.has("skillBindPresets") && acknowledgement.has("skillBindPresets")) {
            Map<String, JsonObject> versions = indexed(acknowledgement.getAsJsonObject("skillBindPresets")
                .getAsJsonArray("entries"), "presetIndex");
            for (JsonElement row : next.getAsJsonObject("skillBindPresets").getAsJsonArray("presets")) {
                JsonObject preset = row.getAsJsonObject();
                updateVersion(preset, versions.get(preset.get("presetIndex").getAsString()), "expectedVersion", "version");
            }
        }
        for (String name : new String[]{"skillTree", "accountProgress"}) {
            if (next.has(name) && acknowledgement.has(name))
                updateVersion(next.getAsJsonObject(name), acknowledgement.getAsJsonObject(name),
                    name.equals("skillTree") ? "expectedVersion" : "expectedProgressVersion",
                    name.equals("skillTree") ? "version" : "progressVersion");
        }
        return next.toString();
    }

    private static void rebaseTimestamps(JsonObject next, JsonObject ack, String array, String id) {
        Map<String, JsonObject> versions = indexed(ack.getAsJsonArray(array), id);
        for (JsonElement row : next.getAsJsonArray(array)) {
            JsonObject value = row.getAsJsonObject();
            updateTimestamp(value, versions.get(value.get(id).getAsString()));
        }
    }

    private static void updateTimestamp(JsonObject target, JsonObject received) {
        updateVersion(target, received, "expectedUpdatedAt", "updatedAt");
    }

    private static void updateVersion(JsonObject target, JsonObject received, String expected, String actual) {
        if (received != null) target.add(expected, received.get(actual).deepCopy());
    }

    private static Map<String, JsonObject> indexed(JsonArray rows, String key) {
        Map<String, JsonObject> result = new HashMap<>();
        for (JsonElement row : rows) {
            JsonObject value = row.getAsJsonObject();
            result.put(value.get(key).getAsString(), value);
        }
        return result;
    }
}
