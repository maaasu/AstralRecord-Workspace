package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YamlDbConfigUtilTest {

    @Test
    void preparedConfigIsVisibleOnlyInsideScopedAction() {
        YamlDbConfig previous = YamlDbConfigLoader.INSTANCE.getCachedConfig();
        YamlDbConfig published = config(1);
        YamlDbConfig prepared = config(2);
        YamlDbConfigLoader.INSTANCE.replaceCachedConfig(published);
        try {
            YamlDbConfig observed = YamlDbConfigUtil.INSTANCE.withSnapshot(
                    prepared,
                    YamlDbConfigUtil.INSTANCE::getConfig
            );

            assertSame(prepared, observed);
            assertSame(published, YamlDbConfigUtil.INSTANCE.getConfig());
        } finally {
            restore(previous);
        }
    }

    @Test
    void preparedConfigIsRemovedWhenScopedActionFails() {
        YamlDbConfig previous = YamlDbConfigLoader.INSTANCE.getCachedConfig();
        YamlDbConfig published = config(1);
        YamlDbConfig prepared = config(2);
        YamlDbConfigLoader.INSTANCE.replaceCachedConfig(published);
        try {
            assertThrows(IllegalStateException.class, () ->
                    YamlDbConfigUtil.INSTANCE.withSnapshot(prepared, () -> {
                        throw new IllegalStateException("test-failure");
                    })
            );

            assertSame(published, YamlDbConfigUtil.INSTANCE.getConfig());
        } finally {
            restore(previous);
        }
    }

    private YamlDbConfig config(int schemaVersion) {
        return new YamlDbConfig(
                schemaVersion,
                List.of(),
                List.of(),
                new RulesConfig("^[a-z0-9_]+$", "{schemaVersion}.{id}.yml")
        );
    }

    private void restore(YamlDbConfig previous) {
        if (previous == null) {
            YamlDbConfigLoader.INSTANCE.clearCache();
        } else {
            YamlDbConfigLoader.INSTANCE.replaceCachedConfig(previous);
        }
    }
}
