# クラス取得 API

## 概要

ファイルシステム上のクラス YAML を起動時にロードし、クラス ID をキーに取得します。

## エンドポイント

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/class` |

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/class/{classId}` |

## 認証

すべてのリクエストに `X-Api-Key` ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| `X-Api-Key` | string | ✓ | API キー |

## リクエスト

### 一覧取得

```http
GET /api/class
X-Api-Key: <your-api-key>
```

### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `classId` | string | ✓ | クラス ID |

## レスポンス

### 200 OK（一覧取得）

最小限の識別情報として `id`・`name`・`role` のみ返却します。

```json
[
	{
		"id": "warrior",
		"name": "戦士",
		"role": "TANK"
	}
]
```

| フィールド | 型 | 説明 |
|---|---|---|
| `id` | string | クラス ID |
| `name` | string | クラス名 |
| `role` | string | ロール |

### 200 OK（個別取得）

```http
GET /api/class/warrior
X-Api-Key: <your-api-key>
```

```json
{
	"schemaVersion": 1,
	"id": "warrior",
	"type": "CLASS",
	"name": "戦士",
	"description": "近接戦闘を得意とする前衛職。",
	"icon": null,
	"role": "TANK",
	"unlockLevel": 1,
	"unlockClassLevel": [
		{
			"classId": "fighter",
			"level": 10
		},
		{
			"classId": "scout",
			"level": 10
		}
	],
	"baseStats": [
		{
			"status": "MAX_HEALTH",
			"value": 120
		},
		{
			"status": "MAX_MANA",
			"value": 50
		}
	],
	"growthPerLevel": [
		{
			"status": "MAX_HEALTH",
			"value": 8
		},
		{
			"status": "MAX_MANA",
			"value": 2
		}
	],
	"expRate": 130,
	"starterSkills": [
		"slash"
	],
	"levelSkills": [
		{
			"level": 10,
			"skill": "shield_bash"
		},
		{
			"level": 20,
			"skill": "taunt"
		}
	],
	"tags": [
		"melee",
		"front"
	]
}
```

### フィールド

| フィールド | 型 | 説明 |
|---|---|---|
| `schemaVersion` | int | YAML スキーマバージョン |
| `id` | string | クラス ID |
| `type` | string | 種別（CLASS） |
| `name` | string | クラス名 |
| `description` | string \| null | クラス説明文 |
| `icon` | string \| null | 表示アイコン |
| `role` | string | ロール（TANK / DEALER / HEALER / SUPPORT） |
| `unlockLevel` | int | 解放に必要な最低プレイヤーレベル |
| `unlockClassLevel[].classId` | string | 解放に必要な素材クラス ID |
| `unlockClassLevel[].level` | int | 解放に必要な素材クラスのレベル |
| `baseStats[].status` | string | ステータス名（StatusType） |
| `baseStats[].value` | double | 初期値 |
| `growthPerLevel[].status` | string | ステータス名（StatusType） |
| `growthPerLevel[].value` | double | 1 レベルあたりの増加量 |
| `expRate` | int | 必要経験値の倍率指標（基準値 100） |
| `starterSkills` | string[] | 初期習得スキル ID 一覧 |
| `levelSkills[].level` | int | スキルを習得するレベル |
| `levelSkills[].skill` | string | 習得するスキル ID |
| `tags` | string[] | 検索・分類用タグ |

### 401 Unauthorized

API キーが指定されていない、または無効。

### 404 Not Found

指定 ID のクラスが存在しない。
