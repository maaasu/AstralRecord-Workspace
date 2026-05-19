# レシピ API

## 概要

ファイルシステム上の recipe YAML を起動時にロードし、一覧取得または recipe ID をキーに取得します。

## エンドポイント

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | /api/recipe |
| 概要 | レシピ一覧取得（最小項目） |

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | /api/recipe/{recipeId} |
| 概要 | レシピ詳細取得 |

## 認証

すべてのリクエストに X-Api-Key ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| X-Api-Key | string | ✓ | API キー |

## リクエスト

### 一覧取得

```http
GET /api/recipe
X-Api-Key: <your-api-key>
```

### 詳細取得: パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| recipeId | string | ✓ | レシピ ID |

```http
GET /api/recipe/iron_sword_recipe
X-Api-Key: <your-api-key>
```

## レスポンス

### 200 OK（一覧取得）

```json
[
  {
    "id": "iron_sword_recipe",
    "type": "RECIPE",
    "category": "CRAFT",
    "name": "&f鉄の剣のレシピ"
  }
]
```

| フィールド | 型 | 説明 |
|---|---|---|
| id | string | レシピ ID |
| type | string | リソース種別 |
| category | string | レシピカテゴリ |
| name | string \| null | 表示名 |

### 200 OK（詳細取得）

```json
{
  "schemaVersion": 1,
  "id": "iron_sword_recipe",
  "type": "RECIPE",
  "category": "CRAFT",
  "name": "&f鉄の剣のレシピ",
  "lore": [
    "&7鉄インゴットを使って剣を作る。"
  ],
  "tags": [
    "blacksmith",
    "weapon"
  ],
  "result": {
    "itemId": "iron_sword",
    "amount": 1
  },
  "ingredients": [
    {
      "itemId": "iron_ingot",
      "amount": 3
    }
  ],
  "requiredLevel": 5,
  "requiredClasses": [],
  "requiredCurrency": 50,
  "stationId": "blacksmith_station",
  "successRate": 100,
  "failAction": "NONE",
  "cooldownTicks": 0,
  "onSuccess": {
    "sound": "block.anvil.use",
    "particle": "crit"
  },
  "onFail": null
}
```

| フィールド | 型 | 説明 |
|---|---|---|
| schemaVersion | int | YAML スキーマバージョン |
| id | string | レシピ ID |
| type | string | リソース種別 |
| category | string | レシピカテゴリ |
| name | string \| null | 表示名 |
| lore | string[] | 説明文 |
| tags | string[] | タグ |
| result | object \| null | 生成結果 |
| result.itemId | string | 生成アイテム ID |
| result.amount | int | 生成数 |
| ingredients | object[] | 必要素材 |
| ingredients[].itemId | string | 素材アイテム ID |
| ingredients[].amount | int | 必要数 |
| requiredLevel | int | 必要レベル |
| requiredClasses | string[] | 使用可能クラス |
| requiredCurrency | int | 必要通貨 |
| stationId | string \| null | 必要施設 ID |
| successRate | number | 成功率 |
| failAction | string | 失敗時の挙動 |
| cooldownTicks | long | クールダウン |
| onSuccess | object \| null | 成功時演出 |
| onSuccess.sound | string \| null | 成功時サウンド |
| onSuccess.particle | string \| null | 成功時パーティクル |
| onFail | object \| null | 失敗時演出 |
| onFail.sound | string \| null | 失敗時サウンド |
| onFail.particle | string \| null | 失敗時パーティクル |

### 401 Unauthorized

API キーが指定されていない、または無効。

### 404 Not Found

指定 recipe ID が存在しない。
