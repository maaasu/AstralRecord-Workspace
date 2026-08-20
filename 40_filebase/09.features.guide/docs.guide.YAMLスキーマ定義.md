# Guide YAML スキーマ定義

ゲーム内ガイドGUIに表示する順序付き手順と達成条件を定義します。GUIの物理スロット配置、条件判定、プレイヤーごとの達成状態はPlugin側で制御します。

## スキーマ定義

```yaml
schemaVersion: 2
id: string
category: beginner
displayOrder: 10
title: string
iconMaterial: WRITABLE_BOOK
summary: string
steps:
  - id: string
    text: string
    condition:
      type: ACTION_RING_OPENED
      targetId: string
```

| Key | Required | Description |
|---|---:|---|
| `schemaVersion` | yes | スキーマバージョン。現状は `2`。 |
| `id` | yes | ガイド ID。 |
| `category` | yes | ガイド分類。Plugin は `beginner` / `equipment` / `skill` / `world` / その他の順で表示する。 |
| `displayOrder` | yes | 同一カテゴリ内の表示順。小さい順に表示する。 |
| `title` | yes | GUI 一覧と詳細画面のタイトル。 |
| `iconMaterial` | no | Bukkit `Material` 名。未指定または不正な場合は `WRITABLE_BOOK` にフォールバックする。 |
| `summary` | no | 一覧 lore の短い説明。 |
| `steps[]` | yes | 記載順に達成する手順。1件以上必要。 |
| `steps[].id` | yes | Guide内で一意な手順ID。公開後は意味を変更せず、別手順には新しいIDを使用する。 |
| `steps[].text` | yes | 詳細画面と達成通知に表示する説明。 |
| `steps[].condition.type` | yes | Pluginが解釈する達成条件種別。 |
| `steps[].condition.targetId` | no | 条件対象ID。未指定時は同じtypeの全対象に一致する。 |

## 達成条件種別

| type | targetId | 達成タイミング |
|:--|:--|:--|
| `PLAYER_LOGGED_IN` | 使用しない | ゲームプレイ状態でログインしたとき |
| `LOGIN_BONUS_CLAIMED` | 使用しない | ログインボーナスを受け取ったとき |
| `MAIL_RECEIVED` | mail ID | メールを既読化し、報酬受取が成功したとき |
| `BUNDLE_OPENED` | bundle item ID | bundle を開封して報酬付与が成功したとき |
| `SHOP_PURCHASED` | shop entry ID | ショップの商品購入または交換が成功したとき |
| `SKILLTREE_NODE_UNLOCKED` | node ID | スキルツリーのノード解放が成功したとき |
| `SKILL_ENHANCED` | skill ID | スキルの強化が成功したとき |
| `ACTION_RING_OPENED` | 使用しない | アクションリングの表示に成功したとき |
| `SKILL_CAST` | 任意のskill ID | プレイヤーのスキル実行が成功したとき。未指定なら任意のスキル |
| `MOB_DEFEATED` | mob master ID | 敵Mobを討伐したとき |
| `GATHERING_COMPLETED` | gathering spawner ID | 指定スポナーに属する採集を完了したとき |

Pluginは各guideの先頭の未達成stepだけを評価します。後続stepと同じイベントを先に実行しても達成にはなりません。

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
schemaVersion: 2
id: action_ring_skill_cast
category: skill
displayOrder: 10
title: "&bアクションリングでスキル発動"
iconMaterial: ENCHANTED_BOOK
summary: "&7アクションリングを開き、スキルを発動します。"
steps:
  - id: open_action_ring
    text: "&fアクションリングを開きます。"
    condition:
      type: ACTION_RING_OPENED
  - id: cast_skill
    text: "&fアクションリングからスキルを発動します。"
    condition:
      type: SKILL_CAST
```
