# スキル取得 API

## 概要

ファイルシステム上のスキル YAML を起動時にロードし、スキル ID をキーに取得します。

## エンドポイント

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/skill` |

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/skill/{skillId}` |

## 認証

すべてのリクエストに `X-Api-Key` ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| `X-Api-Key` | string | ✓ | API キー |

## リクエスト

### 一覧取得

```http
GET /api/skill
X-Api-Key: <your-api-key>
```

### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `skillId` | string | ✓ | スキル ID |

## レスポンス

### 200 OK（一覧取得）

最小限の識別情報として `id`・`name`・`implementationId` のみ返却します。

```json
[
	{
		"id": "slash",
		"name": "スラッシュ",
		"implementationId": "slash"
	}
]
```

| フィールド | 型 | 説明 |
|---|---|---|
| `id` | string | スキル ID |
| `name` | string | スキル名 |
| `implementationId` | string | プラグイン実装クラスの紐付けキー |

### 200 OK（個別取得）

```http
GET /api/skill/slash
X-Api-Key: <your-api-key>
```

```json
{
	"schemaVersion": 1,
	"id": "slash",
	"type": "SKILL",
	"implementationId": "slash",
	"name": "スラッシュ",
	"description": "前方の敵に斬撃を与える基本攻撃スキル。",
	"icon": "IRON_SWORD",
	"lore": [],
	"cooldownTicks": 40,
	"manaCost": 5,
	"castTimeTicks": 0,
	"requiredLevel": 1,
	"onCast": {
		"sound": "entity.player.attack.sweep"
	},
	"params": {
		"damage": 20,
		"scalingStatus": "ATTACK",
		"scalingFactor": 1.2
	},
	"tags": [
		"melee",
		"physical"
	]
}
```

### フィールド

| フィールド | 型 | 説明 |
|---|---|---|
| `schemaVersion` | int | YAML スキーマバージョン |
| `id` | string | スキル ID |
| `type` | string | 種別（SKILL） |
| `implementationId` | string | プラグイン実装クラスの紐付けキー |
| `name` | string | スキル名 |
| `description` | string \| null | スキル説明文 |
| `icon` | string \| null | 表示用アイコン |
| `lore` | string[] | 説明文（色コード使用可） |
| `cooldownTicks` | long | クールダウン時間（tick）。20 tick = 1 秒 |
| `manaCost` | double | 使用時の MP 消費量 |
| `castTimeTicks` | long | 詠唱時間（tick）。0 で即時発動 |
| `requiredLevel` | int | 習得に必要なプレイヤーレベル |
| `onCast.sound` | string \| null | 発動時サウンド |
| `params` | object | プラグイン実装が読み取るカスタムパラメータ（自由形式 Map） |
| `tags` | string[] | 検索・分類用タグ |

### 401 Unauthorized

API キーが指定されていない、または無効。

### 404 Not Found

指定 ID のスキルが存在しない。
