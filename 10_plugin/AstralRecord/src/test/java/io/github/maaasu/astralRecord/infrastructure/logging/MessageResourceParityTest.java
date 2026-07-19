package io.github.maaasu.astralRecord.infrastructure.logging;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MessageResourceParityTest {

    @Test
    void enumNamesMatchConstructedResourceIds() {
        assertIdsMatchEnumNames(LogId.values(), LogId::getId);
        assertIdsMatchEnumNames(PlayerMsgId.values(), PlayerMsgId::getId);
        assertIdsMatchEnumNames(PlayerSettingMsgId.values(), PlayerSettingMsgId::getId);
    }

    @Test
    void everyLogIdHasExactlyOneLoggerProperty() throws IOException {
        assertResourceParity(
            "logger.properties",
            Arrays.stream(LogId.values()).map(LogId::getId).collect(Collectors.toSet())
        );
    }

    @Test
    void everyPlayerMessageIdHasExactlyOnePlayerProperty() throws IOException {
        assertResourceParity(
            "player.properties",
            Arrays.stream(PlayerMsgId.values()).map(PlayerMsgId::getId).collect(Collectors.toSet())
        );
    }

    @Test
    void playerSettingMessageIdsAreDefinedByPlayerMessageIds() {
        Set<String> playerMessageIds = Arrays.stream(PlayerMsgId.values())
            .map(PlayerMsgId::getId)
            .collect(Collectors.toSet());
        Set<String> playerSettingMessageIds = Arrays.stream(PlayerSettingMsgId.values())
            .map(PlayerSettingMsgId::getId)
            .collect(Collectors.toSet());

        assertEquals(
            Set.of(),
            difference(playerSettingMessageIds, playerMessageIds),
            "PlayerSettingMsgId must be a subset of PlayerMsgId"
        );
    }

    @Test
    void duplicateParserRecognizesEqualsColonAndWhitespaceSeparators() throws IOException {
        String source = "P_1=first\n P_1 : second\nP_2 third\n\tP_2:fourth\n";

        assertEquals(Set.of("P_1", "P_2"), findDuplicatePropertyKeys(source));
    }

    private void assertResourceParity(String resourceName, Set<String> expectedIds) throws IOException {
        String source;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(stream, resourceName + " must exist on the test classpath");
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertEquals(
            Set.of(),
            findDuplicatePropertyKeys(source),
            resourceName + " contains duplicate raw property keys"
        );
        Properties properties = new Properties();
        properties.load(new StringReader(source));
        Set<String> actualIds = properties.stringPropertyNames();
        assertEquals(
            expectedIds,
            actualIds,
            () -> "resource parity mismatch: missing=" + difference(expectedIds, actualIds)
                + ", undefined=" + difference(actualIds, expectedIds)
        );
    }

    private Set<String> findDuplicatePropertyKeys(String source) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        for (String key : readRawPropertyKeys(source)) {
            counts.merge(key, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private List<String> readRawPropertyKeys(String source) throws IOException {
        List<String> keys = new ArrayList<>();
        StringBuilder logicalProperty = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new StringReader(source))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.stripLeading();
                if (logicalProperty.isEmpty()
                    && (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!"))) {
                    continue;
                }
                logicalProperty.append(line).append('\n');
                if (hasContinuation(line)) {
                    continue;
                }
                appendLogicalPropertyKey(keys, logicalProperty.toString());
                logicalProperty.setLength(0);
            }
        }
        if (!logicalProperty.isEmpty()) {
            appendLogicalPropertyKey(keys, logicalProperty.toString());
        }
        return keys;
    }

    private boolean hasContinuation(String line) {
        int trailingBackslashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            trailingBackslashes++;
        }
        return trailingBackslashes % 2 == 1;
    }

    private void appendLogicalPropertyKey(List<String> keys, String logicalProperty) throws IOException {
        Properties singleProperty = new Properties();
        singleProperty.load(new StringReader(logicalProperty));
        keys.addAll(singleProperty.stringPropertyNames());
    }

    private <E extends Enum<E>> void assertIdsMatchEnumNames(E[] values, Function<E, String> idAccessor) {
        for (E value : values) {
            assertEquals(
                value.name(),
                idAccessor.apply(value),
                () -> value.getDeclaringClass().getSimpleName() + " constructor ID must match enum name"
            );
        }
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        return left.stream()
            .filter(id -> !right.contains(id))
            .collect(Collectors.toCollection(java.util.TreeSet::new));
    }
}
