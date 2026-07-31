package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YamlDbConfigUtilTest {

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Filebase YAML 設定スナップショット
     * 検証契約: prepared configをwithSnapshot action内だけ優先し共有cacheを変更せず終了後復元する。
     */
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

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Filebase YAML 設定スナップショット
     * 検証契約: scoped action例外時もfinallyでprepared snapshotを除去し共有状態を復元する。
     */
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
