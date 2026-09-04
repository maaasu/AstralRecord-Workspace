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
    Map<String, ServerCapacity> serverCapacities,
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
        Map<String, ServerCapacity> capacities = new LinkedHashMap<>();
        child(root, "serverCapacities").forEach((key, value) ->
            capacities.put(key, serverCapacity(value)));
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

    ServerCapacity capacity(String serverId) {
        return serverCapacities.getOrDefault(serverId, ServerCapacity.NONE);
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

    /**
     * 新形式の権限別定員、または旧形式の単一定員を読み込む。
     *
     * @param value YAML上の定員設定
     * @return 0未満を補正した定員設定
     */
    private static ServerCapacity serverCapacity(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> values = stringMap(map);
            return new ServerCapacity(
                nonNegativeInt(values, "maxPlayers"),
                nonNegativeInt(values, "donorExtraPlayers"),
                nonNegativeInt(values, "adminExtraPlayers"));
        }
        try {
            return new ServerCapacity(Math.max(0, Integer.parseInt(String.valueOf(value))), 0, 0);
        } catch (NumberFormatException ignored) {
            return ServerCapacity.NONE;
        }
    }

    /**
     * YAMLの整数値を0以上へ補正して取得する。
     *
     * @param source 読み取り元
     * @param key キー
     * @return 0以上の整数。未定義または不正値は0
     */
    private static int nonNegativeInt(Map<String, Object> source, String key) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, number(source, key, 0L)));
    }

    record ServerCapacity(int maxPlayers, int donorExtraPlayers, int adminExtraPlayers) {
        private static final ServerCapacity NONE = new ServerCapacity(0, 0, 0);

        /**
         * 指定権限で利用できる接続上限を返す。
         *
         * @param permission APIユーザー権限
         * @return 一般枠と利用可能な追加枠の合計
         */
        int limitFor(int permission) {
            long limit = maxPlayers;
            if (permission >= 99) {
                limit += (long) donorExtraPlayers + adminExtraPlayers;
            } else if (permission >= 5) {
                limit += donorExtraPlayers;
            }
            return (int) Math.min(Integer.MAX_VALUE, limit);
        }

        /**
         * 全権限を含むサーバーの物理的な最大接続数を返す。
         *
         * @return 一般枠・寄付者枠・管理者枠の合計
         */
        int totalCapacity() {
            return (int) Math.min(Integer.MAX_VALUE,
                (long) maxPlayers + donorExtraPlayers + adminExtraPlayers);
        }
    }
}
