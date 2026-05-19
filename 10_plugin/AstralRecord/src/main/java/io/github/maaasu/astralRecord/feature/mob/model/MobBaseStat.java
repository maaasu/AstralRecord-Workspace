package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mobテンプレートで扱う基礎ステータス。
 *
 * @param status ステータス識別子
 * @param value  ステータス値
 */
public record MobBaseStat(String status, double value) {
}
