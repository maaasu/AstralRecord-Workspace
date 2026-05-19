package io.github.maaasu.astralRecord.feature.mob.model;

import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * YAMLから読み込まれるMobテンプレート。
 *
 * @param schemaVersion スキーマバージョン
 * @param id            テンプレートID
 * @param category      Mobカテゴリ
 * @param name          表示名
 * @param title         肩書き
 * @param level         レベル
 * @param entityType    表示に使用するBukkit EntityType
 * @param nameVisible   ネームタグ表示有無
 * @param stats         基礎ステータス
 * @param idleConfig    待機行動設定
 */
public record MobTemplate(
        int schemaVersion,
        String id,
        MobCategory category,
        String name,
        String title,
        int level,
        EntityType entityType,
        boolean nameVisible,
        List<MobBaseStat> stats,
        MobIdleConfig idleConfig
) {
}
