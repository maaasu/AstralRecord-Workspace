# WorldMasterData YAMLスキーマ定義

World システムで参照する filebase マスタです。Plugin は API 経由でこの定義を取得し、`spawnLocation` を参加時スポーンと `/world tp` に利用し、`showSpawnParticle` でスポーン地点リング演出とスニーク導線 TextDisplay の表示有無を制御します。拠点ワールドからオーバーワールドへ移動する GUI では、`displayName` / `description` / `guiIconMaterial` / `adventureGuide` を表示に利用し、`overworldTeleportGui.slot` が指定されたワールドだけを指定スロットへ配置します。`requiredItemId` が指定されたワールドは、通貨インベントリに対象 Currency があるプレイヤーだけを GUI 転送の対象とします。

## スキーマ定義

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
showSpawnParticle: boolean
spawnLocation:
  x: number
  y: number
  z: number
  yaw: number
  pitch: number
description: string
requiredItemId:
  ref: "item:<id>"
guiIconMaterial: string?
adventureGuide:
  recommendedLevelMin: integer?
  recommendedLevelMax: integer?
  recommendedPartySizeMin: integer?
  recommendedPartySizeMax: integer?
  notes:
    - string
overworldTeleportGui:
  slot: integer?
```

| Key | Required | Description |
|---|---:|---|
| `schemaVersion` | yes | YAML スキーマバージョン。現状は `1`。 |
| `id` | yes | ワールドマスタ ID。 |
| `displayName` | yes | 表示名。 |
| `worldType` | yes | ワールド種別。 |
| `baseWorldPath` | yes | 元になるワールドフォルダのパス。 |
| `instanceRootPath` | yes | インスタンス生成先ルート。 |
| `autoLoad` | yes | 起動時と `/world reload` 時の自動ロード対象か。`false` の場合も、テレポート時は `baseWorldPath` からオンデマンドでロードされる。 |
| `instanceEnabled` | yes | インスタンス対応フラグ。 |
| `maxPlayers` | yes | 最大プレイヤー数。 |
| `allowBlockBreak` | yes | ブロック破壊許可。 |
| `allowBlockPlace` | yes | ブロック設置許可。 |
| `allowMobSpawn` | yes | 互換用フィールド。Plugin は RPG マップ保護を優先し、管理ワールドでは値にかかわらずバニラ Mob スポーンを抑止し、AstralRecord が生成した Mob 以外の Bukkit `Mob` を削除する。 |
| `showSpawnParticle` | yes | ワールドスポーン地点の常時リングパーティクルとスニーク導線 TextDisplay を表示するか。`false` の場合も `spawnLocation` 自体は維持し、演出のみ非表示にする。 |
| `spawnLocation` | yes | ワールド既定スポーン地点。`x` `y` `z` `yaw` `pitch` を持つ。 |
| `description` | yes | 説明。複数行は YAML のブロック形式（`|-` など）で記述でき、オーバーワールド転送 GUI では改行ごとに lore の別行として表示する。 |
| `requiredItemId` | no | 拠点から GUI でオーバーワールドへ転送する際に必要な Currency の参照。`{ ref: "item:<id>" }` 形式で指定し、参照先は `category: currency` の item でなければならない。未指定なら入場条件なし。 |
| `guiIconMaterial` | no | オーバーワールド転送 GUI に表示する `Material` 名。未指定または不正な場合は `GRASS_BLOCK` を使う。 |
| `adventureGuide.recommendedLevelMin` | no | 推奨レベル下限。 |
| `adventureGuide.recommendedLevelMax` | no | 推奨レベル上限。 |
| `adventureGuide.recommendedPartySizeMin` | no | 推奨人数下限。 |
| `adventureGuide.recommendedPartySizeMax` | no | 推奨人数上限。 |
| `adventureGuide.notes[]` | no | ワールド選択を補助する補足メモ。GUI ではそのまま lore として表示する。 |
| `overworldTeleportGui.slot` | no | 拠点から開くオーバーワールド転送 GUI の配置先。0 以上 44 以下の Bukkit スロット番号を指定する。オブジェクトまたは `slot` が未指定の場合は GUI に表示しない。範囲外は警告して非表示とし、重複時はワールド ID の昇順で先の1件だけを表示する。 |
