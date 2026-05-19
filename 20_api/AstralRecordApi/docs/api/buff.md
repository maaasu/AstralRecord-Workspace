# バフ API

## 概要

ファイルシステム上の buff YAML を起動時にロードし、一覧取得または buff ID をキーに取得します。

## エンドポイント

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/buff` |
| 概要 | バフ一覧取得（主要項目のみ） |

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/buff/{buffId}` |
| 概要 | バフ詳細取得 |

## 認証

すべてのリクエストに `X-Api-Key` ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| `X-Api-Key` | string | ✓ | API キー |

## リクエスト

### 一覧取得

```http
GET /api/buff
X-Api-Key: <your-api-key>
```

### 詳細取得: パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `buffId` | string | ✓ | バフ ID |

```http
GET /api/buff/haste_small
X-Api-Key: <your-api-key>
```

## レスポンス

### 200 OK（一覧取得）

レスポンス肥大化を避けるため、主要項目のみ返却します。

```json
[
	{
		"id": "haste_small",
		"type": "BUFF",
		"name": "&e迅速(小)",
		"icon": null,
		"durationTicks": 600,
		"isDebuff": false
	}
]
```

| フィールド | 型 | 説明 |
|---|---|---|
| `id` | string | バフ ID |
| `type` | string | バフ種別（`BUFF` / `DEBUFF`） |
| `name` | string | バフ名（Minecraft カラーコード含む） |
| `icon` | string \| null | アイコン（Minecraft マテリアル名） |
| `durationTicks` | int | 効果時間（tick） |
| `isDebuff` | bool | デバフフラグ |

### 200 OK（詳細取得）

バフ取得成功。

```json
{
	"schemaVersion": 1,
	"id": "haste_small",
	"type": "BUFF",
	"name": "&e迅速(小)",
	"icon": null,
	"lore": [
		"&7一定時間、移動速度が上昇する。"
	],
	"durationTicks": 600,
	"isDebuff": false,
	"modifiers": [
		{
			"status": "MOVEMENT_SPEED",
			"type": "SCALAR",
			"value": 0.1
		}
	]
}
```

| フィールド | 型 | 説明 |
|---|---|---|
| `schemaVersion` | int | YAML スキーマバージョン |
| `id` | string | バフ ID |
| `type` | string | バフ種別（`BUFF` / `DEBUFF`） |
| `name` | string | バフ名（Minecraft カラーコード含む） |
| `icon` | string \| null | アイコン（Minecraft マテリアル名） |
| `lore` | string[] | 説明文 |
| `durationTicks` | int | 効果時間（tick） |
| `isDebuff` | bool | デバフフラグ |
| `modifiers` | object[] | ステータス変動リスト |
| `modifiers[].status` | string | 対象ステータス |
| `modifiers[].type` | string | 変動タイプ（`SCALAR` / `FLAT`） |
| `modifiers[].value` | number | 変動量 |

### 401 Unauthorized

API キーが指定されていない、または無効。

### 404 Not Found

指定 buff ID が存在しない。
