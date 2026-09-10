package io.github.maaasu.astralrecordgeyser;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Extension専用のAPI接続設定。APIキーをtoStringへ含めない。 */
final class HeadApiConfig {
    final URI endpoint;
    final String apiKey;
    final Duration timeout;
    final boolean allowInsecureTls;

    HeadApiConfig(URI endpoint, String apiKey, Duration timeout) {
        this(endpoint, apiKey, timeout, false);
    }

    HeadApiConfig(URI endpoint, String apiKey, Duration timeout, boolean allowInsecureTls) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.allowInsecureTls = allowInsecureTls;
    }

    /** 初回のみ設定を配置し、API接続先とキーを検証して読む。 */
    static HeadApiConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("config.yml");
        if (!Files.exists(path)) {
            try (InputStream source = HeadApiConfig.class.getResourceAsStream("/config.yml")) {
                if (source == null) throw new IOException("Missing bundled config.yml");
                Files.copy(source, path);
            }
        }
        try (var reader = Files.newBufferedReader(path)) {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("api") instanceof Map<?, ?> api)) {
                throw new IOException("config.yml requires an api mapping");
            }
            String base = value(api, "baseUrl").replaceAll("/+$", "");
            URI uri = URI.create(base);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("Invalid API baseUrl");
            }
            String key = value(api, "apiKey");
            if (key.isBlank()) {
                String variable = value(api, "apiKeyEnvironmentVariable");
                key = variable.isBlank() ? "" : System.getenv(variable);
            }
            if (key == null || key.isBlank()) throw new IOException("Configure api.apiKey or its environment variable");
            int seconds = Integer.parseInt(value(api, "timeoutSeconds"));
            if (seconds < 1 || seconds > 120) throw new IOException("timeoutSeconds must be between 1 and 120");
            boolean allowInsecureTls = bool(api, "allowInsecureTls", false);
            return new HeadApiConfig(URI.create(base + "/api/geyser/heads"), key, Duration.ofSeconds(seconds), allowInsecureTls);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid API configuration");
        }
    }

    private static String value(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static boolean bool(Map<?, ?> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value instanceof Boolean flag) return flag;
        return value == null ? fallback : Boolean.parseBoolean(value.toString().trim());
    }
}
