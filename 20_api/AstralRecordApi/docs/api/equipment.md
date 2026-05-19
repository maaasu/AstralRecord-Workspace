# 装備インスタンス API

## エンドポイント一覧

| メソッド | パス | 概要 |
|---|---|---|
| POST | `/api/equipment/instances` | 装備インスタンス作成 |
| GET | `/api/equipment/instances/{instanceId}` | 装備インスタンス取得 |
| POST | `/api/equipment/enchant` | エンチャント実施 |
| DELETE | `/api/equipment/enchant` | エンチャント削除 |
| POST | `/api/equipment/enhance` | 強化実施 |
| POST | `/api/equipment/transcendence` | 状態変化実施 |
| POST | `/api/equipment/rune` | ルーン装着 |
| DELETE | `/api/equipment/rune` | ルーン脱着 |

## 認証

すべてのリクエストに `X-Api-Key` ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| `X-Api-Key` | string | ✓ | API キー |

---

## POST /api/equipment/instances

### 概要

YAML マスタデータをもとに装備の個別動的データを生成して保存します。  
ステータス乱数ロールは作成時に実値へ解決され、レスポンスには現在のエンチャント一覧も含まれます。  
存在しない accountId や equipmentId を指定した場合は 404 を返します。

### リクエスト

```http
POST /api/equipment/instances
Content-Type: application/json
X-Api-Key: <your-api-key>
```

```json
{
  "equipmentId": "sample_sword",
  "accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "source": "loot_drop",
  "createdBy": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

### 201 Created

```json
{
  "equipmentInstanceId": "f1e2d3c4-b5a6-7890-fedc-ba0987654321",
  "accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "itemId": "sample_sword",
  "enhanceLevel": 0,
  "runeMaxSlots": 2,
  "transcendenceRank": 0,
  "durabilityMax": 220,
  "durabilityValue": 220,
  "createdAt": "2026-04-19T12:00:00Z",
  "updatedAt": "2026-04-19T12:00:00Z",
  "statRolls": [
    {
      "statRollId": "a0b1c2d3-e4f5-6789-abcd-ef0123456789",
      "status": "ATTACK",
      "min": "27",
      "max": "38",
      "sortOrder": 0
    }
  ],
  "enchants": [],
  "runes": [],
  "enchantPools": [
    {
      "poolIndex": 0,
      "recipeId": null,
      "requiredMaterialItemId": "magic_crystal",
      "requiredMaterialAmount": 2,
      "requiredCurrency": 500
    }
  ]
}
```

### レスポンス主要項目

| フィールド | 型 | 説明 |
|---|---|---|
| `transcendenceRank` | int | 現在の状態変化ランクです。`0` は未状態変化を表し、`1` 以上になると YAML の `equipment.transcendence[].rank` に応じた段階が適用されます。 |
| `statRolls[].min` | string | 作成時に確定した乱数下限値、または固定値ステータスの値です。 |
| `statRolls[].max` | string | 作成時に確定した乱数上限値、または固定値ステータスの値です。 |
| `enchants` | array | 現在付与済みのエンチャント一覧 |
| `runes` | array | 現在装着中のルーン一覧 |
| `enchantPools` | array | 参照用プール構成一覧 |

> `transcendenceRank` は個体の進行状態を保持する値で、状態変化後の有効な強化上限・エンチャント枠・ルーン枠の判定に利用されます。

### enchants 要素

| フィールド | 型 | 説明 |
|---|---|---|
| `enchantId` | GUID | エンチャントレコード ID |
| `equipmentInstanceId` | GUID | 対象装備個体 ID |
| `slotIndex` | int | 実際に使用しているスロット番号 |
| `poolIndex` | int | どのエンチャントプールから付与されたか |
| `status` | string | 付与ステータス |
| `type` | string | 補正方式 |
| `value` | decimal | 実際に確定した付与値 |

### runes 要素

| フィールド | 型 | 説明 |
|---|---|---|
| `runeId` | GUID | ルーン装着レコード ID |
| `runeInstanceId` | GUID \| null | 紐づくルーン個体 ID |
| `equipmentInstanceId` | GUID | 対象装備個体 ID |
| `slotIndex` | int | ルーンスロット番号 |
| `itemId` | string | 装着中のルーン itemId |

---

## GET /api/equipment/instances/{instanceId}

装備インスタンスを取得します。レスポンス形式は作成 API と同一です。

---

## POST /api/equipment/enchant

### 概要

指定した `poolIndex` のプールから重み付き抽選で 1 エントリーを選び、装備へエンチャントを付与します。  
空きスロットがあればそこを使用し、上限到達時は既存エンチャントをランダムに 1 件上書きします。

```json
{
  "equipmentInstanceId": "f1e2d3c4-b5a6-7890-fedc-ba0987654321",
  "poolIndex": 0,
  "updatedBy": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

## DELETE /api/equipment/enchant

### 概要

指定した `poolIndex` の現在エンチャントを物理削除します。

```json
{
  "equipmentInstanceId": "f1e2d3c4-b5a6-7890-fedc-ba0987654321",
  "poolIndex": 0
}
```

---

## POST /api/equipment/enhance

### 概要

装備を 1 段階、または指定レベルまで強化します。

```json
{
  "equipmentInstanceId": "f1e2d3c4-b5a6-7890-fedc-ba0987654321",
  "targetLevel": 1,
  "updatedBy": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

## POST /api/equipment/transcendence

### 概要

装備を次の状態変化ランク、または指定ランクへ更新します。
`targetRank` には現在の `transcendenceRank` より大きい値を指定します。`0` は未状態変化で、ランク上昇後は YAML の `equipment.transcendence[].overrides` に基づいて有効な強化上限・エンチャント枠・ルーン枠の扱いが変化します。

```json
{
  "equipmentInstanceId": "f1e2d3c4-b5a6-7890-fedc-ba0987654321",
  "targetRank": 1,
  "updatedBy": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

## POST /api/equipment/rune

### 概要

ルーンを装着します。slotIndex 未指定時は空きスロットへ自動装着し、指定時はそのスロットを更新します。

```json
{
  "equipmentInstanceId": "f1e2d3c4-b5a6-7890-fedc-ba0987654321",
  "runeInstanceId": "11111111-2222-3333-4444-555555555555",
  "runeItemId": "rune_attack_small",
  "slotIndex": 0,
  "updatedBy": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

## DELETE /api/equipment/rune

### 概要

指定スロットのルーンを物理削除します。

```json
{
  "equipmentInstanceId": "f1e2d3c4-b5a6-7890-fedc-ba0987654321",
  "slotIndex": 0
}
```
