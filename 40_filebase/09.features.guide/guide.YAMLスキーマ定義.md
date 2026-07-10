# Guide YAML スキーマ定義

ゲーム内ガイド GUI に表示する読み物を定義します。ガイド本文はマスター定義に置き、GUI の物理スロット配置やページングは Plugin 側で制御します。

## スキーマ定義

```yaml
schemaVersion: 1
id: string
category: beginner
displayOrder: 10
title: string
iconMaterial: WRITABLE_BOOK
summary: string
lines:
  - string
```

| Key | Required | Description |
|---|---:|---|
| `schemaVersion` | yes | スキーマバージョン。現状は `1`。 |
| `id` | yes | ガイド ID。 |
| `category` | yes | ガイド分類。Plugin は `beginner` / `equipment` / `skill` / `world` / その他の順で表示する。 |
| `displayOrder` | yes | 同一カテゴリ内の表示順。小さい順に表示する。 |
| `title` | yes | GUI 一覧と詳細画面のタイトル。 |
| `iconMaterial` | no | Bukkit `Material` 名。未指定または不正な場合は `WRITABLE_BOOK` にフォールバックする。 |
| `summary` | no | 一覧 lore の短い説明。 |
| `lines[]` | no | 詳細画面の本文。GUI では行単位で表示する。 |

## 本文参照

本文では `{item:starter_sword}` のような参照プレースホルダーを使用できます。Plugin は表示時にロード済みマスター名へ解決し、未解決の場合は ID をそのまま表示します。

初期対応する参照種別:

| Prefix | Description |
|---|---|
| `item:` | item マスター表示名 |
| `class:` | class マスター表示名 |
| `world:` | world マスター表示名 |
| `menu:` | Plugin 側で定義するメニュー表示名 |

## YAML 例

```yaml
schemaVersion: 1
id: first_steps
category: beginner
displayOrder: 10
title: "&dはじめに"
iconMaterial: COMPASS
summary: "&7初参加時に最初に確認する手順。"
lines:
  - "&fまずメニューを開き、ガイドを確認してください。"
  - "&f支給品から {item:starter_weapon_bundle} と {item:starter_armor_bundle} を開封します。"
```
