package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProxyConfigTest {
    @TempDir
    Path dataDirectory;

    @Test
    void allowInsecureTlsDefaultsToFalse() throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), "api:\n  baseUrl: https://localhost:7296\n", StandardCharsets.UTF_8);

        ProxyConfig config = ProxyConfig.load(dataDirectory);

        assertFalse(config.allowInsecureTls());
    }

    @Test
    void allowInsecureTlsCanBeEnabledExplicitly() throws Exception {
        Files.writeString(
            dataDirectory.resolve("config.yml"),
            "api:\n  baseUrl: https://localhost:7296\n  allowInsecureTls: true\n",
            StandardCharsets.UTF_8);

        ProxyConfig config = ProxyConfig.load(dataDirectory);

        assertTrue(config.allowInsecureTls());
    }

    @Test
    void discordExcludedSourceServersDefaultToEmpty() throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), "api:\n  baseUrl: https://localhost:7296\n", StandardCharsets.UTF_8);

        ProxyConfig config = ProxyConfig.load(dataDirectory);

        assertTrue(config.discordExcludedSourceServers().isEmpty());
        assertFalse(config.isDiscordSourceServerExcluded("dev"));
    }

    @Test
    void discordExcludedSourceServersTrimAndMatchCaseInsensitively() throws Exception {
        Files.writeString(
            dataDirectory.resolve("config.yml"),
            "discord:\n  excludedSourceServers:\n    - ' DEV '\n    - ''\n    - '   '\n    - ch1\n",
            StandardCharsets.UTF_8);

        ProxyConfig config = ProxyConfig.load(dataDirectory);

        assertEquals(List.of("DEV", "ch1"), config.discordExcludedSourceServers());
        assertTrue(config.isDiscordSourceServerExcluded("dev"));
        assertTrue(config.isDiscordSourceServerExcluded("CH1"));
        assertFalse(config.isDiscordSourceServerExcluded("ch2"));
        assertFalse(config.isDiscordSourceServerExcluded(null));
    }

    @Test
    void roleSpecificCapacityIsLoadedAndCalculated() throws Exception {
        Files.writeString(
            dataDirectory.resolve("config.yml"),
            "serverCapacities:\n  ch1:\n    maxPlayers: 40\n    donorExtraPlayers: 5\n    adminExtraPlayers: 1\n",
            StandardCharsets.UTF_8);

        ProxyConfig.ServerCapacity capacity = ProxyConfig.load(dataDirectory).capacity("ch1");

        assertEquals(40, capacity.limitFor(0));
        assertEquals(45, capacity.limitFor(5));
        assertEquals(46, capacity.limitFor(99));
        assertEquals(46, capacity.totalCapacity());
    }

    @Test
    void legacyScalarCapacityRemainsSupported() throws Exception {
        Files.writeString(
            dataDirectory.resolve("config.yml"),
            "serverCapacities:\n  ch1: 40\n",
            StandardCharsets.UTF_8);

        ProxyConfig.ServerCapacity capacity = ProxyConfig.load(dataDirectory).capacity("ch1");

        assertEquals(40, capacity.limitFor(0));
        assertEquals(40, capacity.limitFor(99));
    }

    @Test
    void authorityUsersIgnoreInvalidValuesAndMatchUuid() throws Exception {
        UUID authority = UUID.randomUUID();
        Files.writeString(
            dataDirectory.resolve("config.yml"),
            "serverAuthorityUsers:\n  - ' " + authority + " '\n  - invalid\n  - ''\n",
            StandardCharsets.UTF_8);

        ProxyConfig config = ProxyConfig.load(dataDirectory);

        assertEquals(1, config.serverAuthorityUsers().size());
        assertTrue(config.isServerAuthority(authority));
        assertFalse(config.isServerAuthority(UUID.randomUUID()));
    }

    @Test
    void authoritySyncKeyIsLoadedSeparatelyFromSharedApiKey() throws Exception {
        Files.writeString(
            dataDirectory.resolve("config.yml"),
            "api:\n  apiKey: shared\n  authoritySyncKey: proxy-only\n",
            StandardCharsets.UTF_8);

        ProxyConfig config = ProxyConfig.load(dataDirectory);

        assertEquals("shared", config.apiKey());
        assertEquals("proxy-only", config.authoritySyncKey());
    }

    @Test
    void managedSettingsUsesChannelIdsForRoutingAndDiscordControl() {
        UUID authority = UUID.randomUUID();
        ManagedNetworkSettings settings = ManagedNetworkSettings.fromJson(JsonParser.parseString("""
            {"revision":4,"lobbyServerId":"lobby","transferCooldownSeconds":45,
             "tabRefreshSeconds":3,"presenceHeartbeatSeconds":12,"authorityUsers":["%s"],
             "channels":[
              {"serverId":"lobby","displayName":"ロビー","isGame":false,"maxPlayers":100,"discordEnabled":true,"whitelistEnabled":false,"debugUsers":[],"whitelistUsers":[]},
              {"serverId":"ch1","displayName":"第一","isGame":true,"maxPlayers":30,"donorExtraPlayers":5,"adminExtraPlayers":1,"discordEnabled":false,"whitelistEnabled":true,"debugUsers":[],"whitelistUsers":[]}
             ]}
            """.formatted(authority)).getAsJsonObject());

        assertEquals(List.of("ch1"), settings.gameServers());
        assertEquals("第一", settings.channelName("CH1"));
        assertTrue(settings.isServerAuthority(authority));
        assertTrue(settings.isDiscordSourceServerExcluded("ch1"));
        assertEquals(36, settings.capacity("ch1").limitFor(99));
    }

    @Test
    void oneSecondHeartbeatSurvivesLegacyImportAndManagedRead() throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), "presenceHeartbeatSeconds: 1\n", StandardCharsets.UTF_8);
        assertEquals(1L, ProxyConfig.load(dataDirectory).presenceHeartbeatSeconds());
        ManagedNetworkSettings settings = ManagedNetworkSettings.fromJson(JsonParser.parseString("""
            {"revision":1,"lobbyServerId":"lobby","presenceHeartbeatSeconds":1,
             "channels":[{"serverId":"lobby","displayName":"Lobby","isGame":false}]}
            """).getAsJsonObject());
        assertEquals(1L, settings.presenceHeartbeatSeconds());
    }
}
