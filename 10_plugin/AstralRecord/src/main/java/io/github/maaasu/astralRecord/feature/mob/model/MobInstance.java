package io.github.maaasu.astralRecord.feature.mob.model;

import org.bukkit.Location;

import java.util.UUID;

/**
 * サーバー上で生成された実体Mobインスタンス。
 *
 * @param instanceId 一意なインスタンスID
 * @param entityId   クライアントへ送る仮想Entity ID
 * @param template   元テンプレート
 * @param location   表示座標
 */
public record MobInstance(UUID instanceId, int entityId, MobTemplate template, Location location) {
}
