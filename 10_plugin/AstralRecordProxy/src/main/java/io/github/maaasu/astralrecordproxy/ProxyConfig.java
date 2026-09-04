package io.github.maaasu.astralrecordproxy;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record ProxyConfig(
    String lobbyServer,
    List<String> gameServers,
    Map<String, String> channelNames,
    Map<String, Integer> serverCapacities,
    long transferCooldownSeconds,
    long tabRefreshSeconds,
    long presenceHeartbeatSeconds,
    String apiBaseUrl,
    String apiKey,
    int apiTimeoutMillis,
    long discordPollMillis,
    boolean allowInsecureTls
) {
    static ProxyConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path path = dataDirectory.resolve("config.yml");
        if (Files.notExists(path)) {
            try (InputStream source = ProxyConfig.class.getResourceAsStream("/config.yml")) {
                if (source == null) {
                    throw new IOException("Bundled config.yml is missing");
                }
                Files.copy(source, path);
            }
        }

        Map<String, Object> root;
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(input);
            root = loaded instanceof Map<?, ?> map ? stringMap(map) : Map.of();
        }
        Map<String, Object> api = child(root, "api");
        Map<String, String> channels = new LinkedHashMap<>();
        child(root, "channelNames").forEach((key, value) -> channels.put(key, String.valueOf(value)));
        Map<String, Integer> capacities = new LinkedHashMap<>();
        child(root, "serverCapacities").forEach((key, value) -> {
            try {
                capacities.put(key, Math.max(0, Integer.parseInt(String.valueOf(value))));
            } catch (NumberFormatException ignored) {
                capacities.put(key, 0);
            }
        });
        List<String> games = new ArrayList<>();
        Object rawGames = root.get("gameServers");
        if (rawGames instanceof List<?> list) {
            list.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isEmpty()).forEach(games::add);
        }
        return new ProxyConfig(
            text(root, "lobbyServer", "lobby"),
            List.copyOf(games),
            Map.copyOf(channels),
            Map.copyOf(capacities),
            number(root, "transferCooldownSeconds", 30L),
            Math.max(1L, number(root, "tabRefreshSeconds", 2L)),
            Math.max(5L, number(root, "presenceHeartbeatSeconds", 10L)),
            text(api, "baseUrl", "http://127.0.0.1:5261"),
            text(api, "apiKey", ""),
            (int) number(api, "timeoutMillis", 3000L),
            Math.max(250L, number(api, "discordPollMillis", 500L)),
            bool(api, "allowInsecureTls", false)
        );
    }

    String channelName(String serverId) {
        return channelNames.getOrDefault(serverId, serverId);
    }

    boolean isGameServer(String serverId) {
        return gameServers.stream().anyMatch(value -> value.equalsIgnoreCase(serverId));
    }

    int capacity(String serverId) {
        return serverCapacities.getOrDefault(serverId, 0);
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Map<String, Object> child(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? stringMap(map) : Map.of();
    }

    private static String text(Map<String, Object> source, String key, String fallback) {
        Object value = source.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private static long number(Map<String, Object> source, String key, long fallback) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean bool(Map<String, Object> source, String key, boolean fallback) {
        Object value = source.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
    }
}
