# WorldMasterData YAML スキーマ定義

World システムで参照する静的ワールド定義です。インスタンス生成時は `baseWorldPath` を複製元、`instanceRootPath` を複製先ルートとして扱います。生成ワールド名はプラグイン側で `{worldId}_{instanceType}_{ownerId}_{shortHash}` の形式で一意に生成する想定です。

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
description: string
```

## 項目

| Key | Required | Description |
|---|---:|---|
| `schemaVersion` | yes | YAML スキーマバージョン。現行は `1`。 |
| `id` | yes | ワールド定義 ID。 |
| `displayName` | yes | 管理表示用の名称。 |
| `worldType` | yes | ワールド種別。 |
| `baseWorldPath` | yes | 複製元ワールドのパス。通常ワールドでも将来の複製元として保持する。 |
| `instanceRootPath` | yes | インスタンス生成先ルート。直下に一意な生成ワールド名を作る。 |
| `autoLoad` | yes | 起動時の自動ロード対象か。今回は定義保持のみ。 |
| `instanceEnabled` | yes | インスタンス化対象か。今回は定義保持のみ。 |
| `maxPlayers` | yes | 同一ワールドまたはインスタンスの想定最大人数。 |
| `allowBlockBreak` | yes | ブロック破壊許可。 |
| `allowBlockPlace` | yes | ブロック設置許可。 |
| `allowMobSpawn` | yes | Mob スポーン許可。 |
| `description` | yes | 管理者向け説明。 |
