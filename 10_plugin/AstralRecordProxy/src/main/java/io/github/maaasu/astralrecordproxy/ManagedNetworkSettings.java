package io.github.maaasu.astralrecordproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Management DBのネットワーク設定スナップショットです。 */
record ManagedNetworkSettings(
    int revision,
    String lobbyServer,
    long transferCooldownSeconds,
    long tabRefreshSeconds,
    long presenceHeartbeatSeconds,
    Set<UUID> serverAuthorityUsers,
    Map<String, Channel> channels
) implements NetworkSettings {
    ManagedNetworkSettings {
        lobbyServer = requiredServerId(lobbyServer, "lobbyServerId");
        transferCooldownSeconds = Math.max(0L, transferCooldownSeconds);
        tabRefreshSeconds = Math.max(1L, tabRefreshSeconds);
        presenceHeartbeatSeconds = Math.max(1L, presenceHeartbeatSeconds);
        serverAuthorityUsers = Set.copyOf(serverAuthorityUsers);
        Map<String, Channel> normalizedChannels = Map.copyOf(channels);
        channels = normalizedChannels;
        String effectiveLobbyServer = lobbyServer;
        if (!normalizedChannels.keySet().stream().anyMatch(serverId -> serverId.equalsIgnoreCase(effectiveLobbyServer))) {
            throw new IllegalArgumentException("Managed settings does not contain the lobby channel");
        }
    }

    static ManagedNetworkSettings fromJson(JsonObject json) {
        if (json == null) throw new IllegalArgumentException("Managed settings response is empty");
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        JsonArray rawChannels = array(json, "channels");
        for (JsonElement element : rawChannels) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Managed channel is invalid");
            Channel channel = Channel.fromJson(element.getAsJsonObject());
            String key = channel.serverId().toLowerCase(Locale.ROOT);
            if (channelMap.putIfAbsent(key, channel) != null) {
                throw new IllegalArgumentException("Managed channel serverId is duplicated: " + channel.serverId());
            }
        }
        return new ManagedNetworkSettings(
            number(json, "revision", 0), text(json, "lobbyServerId"),
            number(json, "transferCooldownSeconds", 30), number(json, "tabRefreshSeconds", 2),
            number(json, "presenceHeartbeatSeconds", 10), uuidSet(array(json, "authorityUsers")), channelMap);
    }

    JsonObject toBootstrapJson() {
        JsonObject result = new JsonObject();
        result.addProperty("revision", revision);
        result.addProperty("lobbyServerId", lobbyServer);
        result.addProperty("transferCooldownSeconds", transferCooldownSeconds);
        result.addProperty("tabRefreshSeconds", tabRefreshSeconds);
        result.addProperty("presenceHeartbeatSeconds", presenceHeartbeatSeconds);
        JsonArray authorities = new JsonArray();
        serverAuthorityUsers.stream().map(UUID::toString).sorted().forEach(authorities::add);
        result.add("authorityUsers", authorities);
        JsonArray values = new JsonArray();
        channels.values().stream().sorted(java.util.Comparator.comparing(Channel::serverId, String.CASE_INSENSITIVE_ORDER))
            .forEach(channel -> values.add(channel.toJson()));
        result.add("channels", values);
        return result;
    }

    @Override
    public List<String> gameServers() {
        return channels.values().stream().filter(Channel::game).map(Channel::serverId).toList();
    }

    @Override
    public String channelName(String serverId) {
        Channel channel = channel(serverId);
        return channel == null ? serverId : channel.displayName();
    }

    @Override
    public boolean isGameServer(String serverId) {
        Channel channel = channel(serverId);
        return channel != null && channel.game();
    }

    @Override
    public boolean isDiscordSourceServerExcluded(String serverId) {
        Channel channel = channel(serverId);
        return channel == null || !channel.discordEnabled();
    }

    @Override
    public boolean isServerAuthority(UUID playerId) {
        return playerId != null && serverAuthorityUsers.contains(playerId);
    }

    @Override
    public ProxyConfig.ServerCapacity capacity(String serverId) {
        Channel channel = channel(serverId);
        return channel == null ? new ProxyConfig.ServerCapacity(0, 0, 0) : channel.capacity();
    }

    private Channel channel(String serverId) {
        if (serverId == null) return null;
        return channels.get(serverId.toLowerCase(Locale.ROOT));
    }

    private static JsonArray array(JsonObject source, String name) {
        JsonElement value = source.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String text(JsonObject source, String name) {
        JsonElement value = source.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private static int number(JsonObject source, String name, int fallback) {
        JsonElement value = source.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static Set<UUID> uuidSet(JsonArray values) {
        return values.asList().stream().filter(value -> !value.isJsonNull()).map(JsonElement::getAsString)
            .map(value -> {
                try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
            }).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private static String requiredServerId(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return trimmed;
    }

    record Channel(
        String serverId,
        String displayName,
        boolean game,
        ProxyConfig.ServerCapacity capacity,
        boolean discordEnabled,
        boolean whitelistEnabled,
        Set<UUID> debugUsers,
        Set<UUID> whitelistUsers
    ) {
        Channel {
            serverId = requiredServerId(serverId, "serverId");
            displayName = displayName == null || displayName.isBlank() ? serverId : displayName.trim();
            capacity = capacity == null ? new ProxyConfig.ServerCapacity(0, 0, 0) : capacity;
            debugUsers = Set.copyOf(debugUsers);
            whitelistUsers = Set.copyOf(whitelistUsers);
        }

        static Channel fromJson(JsonObject json) {
            return new Channel(text(json, "serverId"), text(json, "displayName"), bool(json, "isGame", false),
                new ProxyConfig.ServerCapacity(Math.max(0, number(json, "maxPlayers", 0)),
                    Math.max(0, number(json, "donorExtraPlayers", 0)),
                    Math.max(0, number(json, "adminExtraPlayers", 0))),
                bool(json, "discordEnabled", true), bool(json, "whitelistEnabled", false),
                uuidSet(array(json, "debugUsers")), uuidSet(array(json, "whitelistUsers")));
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("serverId", serverId);
            result.addProperty("displayName", displayName);
            result.addProperty("isGame", game);
            result.addProperty("maxPlayers", capacity.maxPlayers());
            result.addProperty("donorExtraPlayers", capacity.donorExtraPlayers());
            result.addProperty("adminExtraPlayers", capacity.adminExtraPlayers());
            result.addProperty("discordEnabled", discordEnabled);
            result.addProperty("whitelistEnabled", whitelistEnabled);
            result.add("debugUsers", uuidArray(debugUsers));
            result.add("whitelistUsers", uuidArray(whitelistUsers));
            return result;
        }

        private static boolean bool(JsonObject source, String name, boolean fallback) {
            JsonElement value = source.get(name);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        }

        private static JsonArray uuidArray(Set<UUID> values) {
            JsonArray result = new JsonArray();
            values.stream().map(UUID::toString).sorted().forEach(result::add);
            return result;
        }
    }
}
