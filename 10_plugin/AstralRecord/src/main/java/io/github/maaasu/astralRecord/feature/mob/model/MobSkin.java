package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の外見スキン設定。{@code entityType = PLAYER} の場合に主に使用する。
 *
 * @param texture   Base64 エンコードされたスキンテクスチャ値
 * @param signature テクスチャの署名値
 */
public record MobSkin(String texture, String signature) {
}
