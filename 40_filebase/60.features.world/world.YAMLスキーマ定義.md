# WorldMasterData YAML スキーマ定義

World システムで参照する filebase マスタです。Plugin は API 経由でこの定義を取得し、`spawnLocation` を参加時スポーンと `/world tp` に利用します。

```yaml
schemaVersion: 1
id: string
displayName: string
worldType: HUB | BASE | OVERWORLD | DUNGEON | BOSS_FIELD
baseWorldPath: string
instanceRootPath: string
autoLoad: boolean
instanceEnabled: boolean
maxPlayers: integer
allowBlockBreak: boolean
allowBlockPlace: boolean
allowMobSpawn: boolean
spawnLocation:
  x: number
  y: number
  z: number
  yaw: number
  pitch: number
description: string
```

## 項目

| Key | Required | Description |
|---|---:|---|
| `schemaVersion` | yes | YAML スキーマバージョン。現時点では `1`。 |
| `id` | yes | ワールド定義 ID。 |
| `displayName` | yes | 表示名。 |
| `worldType` | yes | ワールド種別。 |
| `baseWorldPath` | yes | 元となるワールドフォルダのパス。 |
| `instanceRootPath` | yes | インスタンス生成先ルート。 |
| `autoLoad` | yes | 起動時と `/world reload` 時の自動ロード対象か。 |
| `instanceEnabled` | yes | インスタンス対応フラグ。 |
| `maxPlayers` | yes | 想定最大プレイヤー数。 |
| `allowBlockBreak` | yes | ブロック破壊許可。 |
| `allowBlockPlace` | yes | ブロック設置許可。 |
| `allowMobSpawn` | yes | 互換用フィールド。Plugin は RPG マップ保護を優先し、管理ワールドでは値にかかわらずバニラ Mob スポーンを抑止し、AstralRecord が生成した Mob 以外の Bukkit `Mob` を削除する。 |
| `spawnLocation` | yes | 既定スポーン地点。`x` `y` `z` `yaw` `pitch` を持つ。 |
| `description` | yes | 説明。 |
