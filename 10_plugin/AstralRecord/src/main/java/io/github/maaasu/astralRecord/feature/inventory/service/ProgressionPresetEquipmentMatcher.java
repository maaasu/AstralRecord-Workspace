package io.github.maaasu.astralRecord.feature.inventory.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** 進行プリセット適用時に、装備中の個体を枠間で一度ずつ再利用します。 */
final class ProgressionPresetEquipmentMatcher {
    private ProgressionPresetEquipmentMatcher() { }

    static <T> Map<String, T> reuse(
        Map<String, T> current, Set<String> desiredSlots,
        BiPredicate<String, T> matches, Function<T, UUID> instanceId
    ) {
        Map<String, T> reused = new LinkedHashMap<>();
        Set<UUID> claimed = new HashSet<>();
        for (String slot : desiredSlots) {
            T candidate = current.get(slot);
            if (candidate != null && matches.test(slot, candidate)) {
                UUID id = instanceId.apply(candidate);
                if (id != null && claimed.add(id)) reused.put(slot, candidate);
            }
        }
        for (String slot : desiredSlots) {
            if (reused.containsKey(slot)) continue;
            for (T candidate : current.values()) {
                UUID id = instanceId.apply(candidate);
                if (id != null && !claimed.contains(id) && matches.test(slot, candidate)) {
                    reused.put(slot, candidate);
                    claimed.add(id);
                    break;
                }
            }
        }
        return reused;
    }
}
