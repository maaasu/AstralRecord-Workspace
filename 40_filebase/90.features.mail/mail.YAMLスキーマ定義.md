# mail YAML スキーマ定義

メール本文、表示アイコン、公開期間、既読時に受け取る報酬を定義する。
プレイヤー別の既読・削除状態は SQL Server の `player_mail_state` で管理し、このマスタデータ自体は削除しない。

```yaml
schemaVersion: 1
id: example_mail
icon: PAPER
title: "メール題名"
body: "本文。複数行は \\n で区切る。"
publishFrom: "2026-06-01T00:00:00"
publishTo: "2026-07-01T00:00:00" # null 可
receiveOnRead: true
rewards:
  - itemId: iron_ingot
    category: material
    amount: 1
```

## フィールド

| Field | Type | Required | Description |
|:--|:--|:--|:--|
| `schemaVersion` | number | yes | スキーマバージョン。現行は `1`。 |
| `id` | string | yes | メール ID。ファイル名 `v1.<id>.yml` と対応する。 |
| `icon` | string | yes | Bukkit `Material` 名。無効な場合はプラグインで `PAPER` 表示にフォールバックする。 |
| `title` | string | yes | メール題名。 |
| `body` | string | yes | メール本文。改行は `\n`。 |
| `publishFrom` | ISO-8601 datetime | yes | 公開開始日時。 |
| `publishTo` | ISO-8601 datetime/null | no | 公開終了日時。null の場合は無期限。 |
| `receiveOnRead` | boolean | yes | 未読メールをクリックして既読化したときに `rewards` を付与するか。 |
| `rewards` | array | no | 報酬アイテム一覧。 |

## rewards

| Field | Type | Required | Description |
|:--|:--|:--|:--|
| `itemId` | string | yes | アイテムマスタ ID。 |
| `category` | string | yes | アイテムカテゴリ。例: `material`, `currency`, `equipment`, `rune`。 |
| `amount` | number | yes | 付与数。 |
