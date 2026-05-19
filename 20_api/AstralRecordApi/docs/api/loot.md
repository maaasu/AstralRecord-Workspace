# ルート API

## 概要

ファイルシステム上のルート YAML を起動時にロードし、プール ID またはテーブル ID をキーに取得します。

## エンドポイント

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/loot/pool` |
| 概要 | ルートプール一覧取得 |

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/loot/pool/{poolId}` |
| 概要 | ルートプール取得 |

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/loot/table` |
| 概要 | ルートテーブル一覧取得 |

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/loot/table/{tableId}` |
| 概要 | ルートテーブル取得 |

## 認証

すべてのリクエストに `X-Api-Key` ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| `X-Api-Key` | string | ✓ | API キー |

## リクエスト

### ルートプール一覧取得

```http
GET /api/loot/pool
X-Api-Key: <your-api-key>
```

### ルートプール取得: パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `poolId` | string | ✓ | プール ID |

```http
GET /api/loot/pool/iron_ingot_pool
X-Api-Key: <your-api-key>
```

### ルートテーブル一覧取得

```http
GET /api/loot/table
X-Api-Key: <your-api-key>
```

### ルートテーブル取得: パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `tableId` | string | ✓ | テーブル ID |

```http
GET /api/loot/table/chest_loot_basic
X-Api-Key: <your-api-key>
```

## レスポンス

### 200 OK — ルートプール

```json
{
	"schemaVersion": 1,
	"id": "iron_ingot_pool",
	"type": "LOOT_POOL",
	"pick": "1",
	"contents": [
		{
			"itemId": "iron_ingot",
			"rate": 100.0,
			"amount": "1~5"
		},
		{
			"itemId": "gold_ingot",
			"rate": 50.0,
			"amount": "1~3"
		}
	]
}
```

### 200 OK — ルートテーブル

```json
{
	"schemaVersion": 1,
	"id": "chest_loot_basic",
	"type": "LOOT_TABLE",
	"rolls": "1",
	"pools": [
		"iron_ingot_pool",
		"gold_ingot_pool"
	]
}
```

### 404 Not Found

指定した ID が存在しない場合。

## フィールド説明

### LootPool

| フィールド | 型 | 説明 |
|---|---|---|
| `schemaVersion` | integer | スキーマバージョン |
| `id` | string | プール ID |
| `type` | string | ルート種別（`LOOT_POOL`） |
| `pick` | string \| null | 抽選数。固定値または範囲（例: `"1"`, `"1~4"`）。YAML では `min/max` 形式（例: `pick: { min: 1, max: 4 }`）も指定可能。null の場合は contents の要素数 |
| `contents` | array | コンテンツの設定リスト |
| `contents[].itemId` | string | ドロップするアイテム ID |
| `contents[].rate` | number | ドロップ率（0～100） |
| `contents[].amount` | string \| null | ドロップ数。固定値または範囲（例: `"1"`, `"1~4"`）。YAML では `min/max` 形式（例: `amount: { min: 1, max: 4 }`）も指定可能。null の場合は 1 |

### LootTable

| フィールド | 型 | 説明 |
|---|---|---|
| `schemaVersion` | integer | スキーマバージョン |
| `id` | string | テーブル ID |
| `type` | string | ルート種別（`LOOT_TABLE`） |
| `rolls` | string \| null | 抽選回数。固定値または範囲（例: `"1"`, `"1~4"`）。YAML では `min/max` 形式（例: `rolls: { min: 1, max: 4 }`）も指定可能。null の場合は 1 |
| `pools` | array of string | 参照するプール ID のリスト |
