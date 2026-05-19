# アイテム取得 API

## 概要

ファイルシステム上のアイテム YAML を起動時にロードし、アイテム ID をキーに取得します。

## エンドポイント

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/item` |

| 項目 | 値 |
|---|---|
| メソッド | GET |
| パス | `/api/item/{itemId}` |

## 認証

すべてのリクエストに `X-Api-Key` ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| `X-Api-Key` | string | ✓ | API キー |

## リクエスト

### 一覧取得

```http
GET /api/item
X-Api-Key: <your-api-key>
```

### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `itemId` | string | ✓ | アイテム ID |

## レスポンス

### 200 OK（一覧取得）

最小限の識別情報として `id` と `category` のみ返却します。

```json
[
	{
		"id": "iron_ingot",
		"category": "material"
	}
]
```

| フィールド | 型 | 説明 |
|---|---|---|
| `id` | string | アイテム ID |
| `category` | string | アイテムカテゴリ |

### 200 OK — material

```http
GET /api/item/iron_ingot
X-Api-Key: <your-api-key>
```

```json
{
	"schemaVersion": 1,
	"id": "iron_ingot",
	"category": "material",
	"name": "鉄インゴット",
	"icon": "IRON_INGOT",
	"rarity": "COMMON",
	"saleValue": 10,
	"customModelData": null,
	"lore": [
		"基本的な素材として使用される鉄のインゴット。",
		"武器や防具の作成に欠かせない。"
	],
	"unTradeable": false,
	"unSellable": false
}
```

### 200 OK — consumable

```http
GET /api/item/haste_potion_small
X-Api-Key: <your-api-key>
```

```json
{
	"schemaVersion": 1,
	"id": "haste_potion_small",
	"category": "consumable",
	"name": "迅速ポーション(小)",
	"icon": "POTION",
	"rarity": "COMMON",
	"saleValue": 20,
	"customModelData": null,
	"lore": [
		"飲むと一定時間、移動速度が上昇する。",
		"素早さが10%上昇する。"
	],
	"unTradeable": false,
	"unSellable": false,
	"consumable": {
		"onUse": {
			"sound": "entity.generic.drink",
			"effect": "happy_villager",
			"amount": 1
		},
		"effects": [
			{
				"type": "BUFF",
				"rate": 100,
				"value": null,
				"status": null,
				"isPercent": false,
				"buffId": "haste_small"
			}
		]
	}
}
```

### 200 OK — equipment

```http
GET /api/item/bronze_sword
X-Api-Key: <your-api-key>
```

```json
{
	"schemaVersion": 1,
	"id": "bronze_sword",
	"category": "equipment",
	"name": "ブロンズソード",
	"icon": "IRON_SWORD",
	"rarity": "COMMON",
	"saleValue": 50,
	"customModelData": null,
	"lore": [
		"初心者向けの片手剣。"
	],
	"unTradeable": false,
	"unSellable": false,
	"equipment": {
		"slot": "WEAPON",
		"handType": "ONE",
		"tag": "SWORD",
		"requiredLevel": 3,
		"requiredClasses": [],
		"stats": [
			{
				"status": "ATTACK_POWER",
				"type": "FLAT",
				"value": "12",
				"random": null
			},
			{
				"status": "CRITICAL_CHANCE",
				"type": "FLAT",
				"value": "0.03",
				"random": null
			}
		],
		"durability": {
			"max": 120,
			"consume": 1
		},
		"onUse": {
			"leftClickCooldownTicks": 80,
			"leftClickSkillId": "lefthand_slash",
			"rightClickCooldownTicks": null,
			"rightClickSkillId": null
		},
		"skills": [],
		"enhance": {
			"maxLevel": 3,
			"levels": [
				{
					"level": 1,
					"statIncrease": [
						{
							"status": "ATTACK",
							"type": "FLAT",
							"value": "3"
						}
					],
					"durabilityBonus": 10,
					"recipeId": null,
					"requiredMaterials": [
						{
							"itemId": "iron_ingot",
							"amount": 2
						}
					],
					"requiredCurrency": 100,
					"successRate": 1.0,
					"failAction": "NONE"
				}
			]
		},
		"enchant": null,
		"rune": null,
		"transcendence": []
	}
}
```

### 200 OK — currency

```http
GET /api/item/boss_proof_golem
X-Api-Key: <your-api-key>
```

```json
{
	"schemaVersion": 1,
	"id": "boss_proof_golem",
	"category": "currency",
	"name": "ゴーレム討伐の証",
	"icon": "AMETHYST_SHARD",
	"rarity": "EPIC",
	"saleValue": 0,
	"customModelData": null,
	"maxStack": 64,
	"lore": [
		"巨像を打ち倒した者に与えられる証。"
	],
	"unTradeable": false,
	"unSellable": true,
	"currency": {
		"type": "BOSS_PROOF",
		"group": "golem",
		"expiresAt": "2026-02-01T00:00:00+09:00"
	}
}
```

### 200 OK — bundle

```http
GET /api/item/magic_iron_ingot_bundle
X-Api-Key: <your-api-key>
```

```json
{
	"schemaVersion": 1,
	"id": "magic_iron_ingot_bundle",
	"category": "bundle",
	"name": "魔法のパケット",
	"icon": "chest",
	"rarity": "UNCOMMON",
	"saleValue": 0,
	"customModelData": null,
	"maxStack": 64,
	"lore": [
		"魔力を帯びた珍しいパケット。"
	],
	"unTradeable": false,
	"unSellable": false,
	"bundle": {
		"lootTableId": "magic_iron_ingot",
		"items": [],
		"onUse": {
			"sound": "block.anvil.land",
			"particle": "block_break"
		}
	}
}
```

### 200 OK — rune

```http
GET /api/item/rune_attack_small
X-Api-Key: <your-api-key>
```

```json
{
	"schemaVersion": 1,
	"id": "rune_attack_small",
	"category": "rune",
	"name": "攻撃のルーン【小】",
	"icon": "BRICK",
	"rarity": "COMMON",
	"saleValue": 0,
	"customModelData": null,
	"maxStack": 1,
	"lore": [
		"装備に嵌め込むことで攻撃力を高める。"
	],
	"unTradeable": false,
	"unSellable": false,
	"rune": {
		"targetSlots": ["WEAPON"],
		"requiredEnhanceLevel": 0,
		"stats": [
			{
				"status": "ATTACK",
				"type": "FLAT",
				"value": "5",
				"random": null
			}
		],
		"skills": []
	}
}
```

### 共通フィールド

| フィールド | 型 | 説明 |
|---|---|---|
| `schemaVersion` | int | YAML スキーマバージョン |
| `id` | string | アイテム ID |
| `category` | string | アイテムカテゴリ |
| `name` | string | アイテム名 |
| `icon` | string | Minecraft マテリアル名 |
| `rarity` | string | レアリティ |
| `saleValue` | int | 売却価格 |
| `customModelData` | int \| null | カスタムモデルデータ |
| `maxStack` | int | 最大スタック数 |
| `lore` | string[] | 説明文 |
| `unTradeable` | bool | 取引不可フラグ |
| `unSellable` | bool | 売却不可フラグ |

### equipment フィールド

| フィールド | 型 | 説明 |
|---|---|---|
| `equipment.slot` | string | 装備スロット（WEAPON / SUBWEAPON / HEAD / CHEST / LEGS / FEET / ACCESSORY / TOOL） |
| `equipment.handType` | string | 手持ち装備の手数（ONE / TWO）。slot=WEAPON の場合に使用 |
| `equipment.tag` | string \| null | ツール種別・アクセサリ種別などの補助情報 |
| `equipment.requiredLevel` | int | 装備に必要なプレイヤーレベル（0 で制限なし） |
| `equipment.requiredClasses` | string[] | 装備可能クラス ID リスト（空の場合は全クラス可） |
| `equipment.stats[].status` | string \| null | ステータス名（StatusType） |
| `equipment.stats[].type` | string \| null | 補正方式（FLAT / SCALAR） |
| `equipment.stats[].value` | object \| null | ステータスの下限値/上限値 |
| `equipment.stats[].value.min` | string \| null | 下限値。固定値または範囲（例: "10", "10~20"） |
| `equipment.stats[].value.max` | string \| null | 上限値。固定値または範囲（例: "21", "21~50"） |
| `equipment.durability.max` | int \| null | 最大耐久値 |
| `equipment.durability.consume` | int | 1 回の使用で減る耐久値 |
| `equipment.onUse.leftClickCooldownTicks` | int \| null | 左クリック使用時クールタイム |
| `equipment.onUse.leftClickSkillId` | string \| null | 左クリック時に発動するスキル ID |
| `equipment.onUse.rightClickCooldownTicks` | int \| null | 右クリック使用時クールタイム |
| `equipment.onUse.rightClickSkillId` | string \| null | 右クリック時に発動するスキル ID |
| `equipment.skills` | string[] | 装備中に適用されるスキル ID リスト |
| `equipment.enhance.maxLevel` | int | 強化最大レベル |
| `equipment.enhance.levels[].level` | int | 強化レベル |
| `equipment.enhance.levels[].statIncrease[].status` | string | ステータス名 |
| `equipment.enhance.levels[].statIncrease[].type` | string | 補正方式（FLAT / SCALAR） |
| `equipment.enhance.levels[].statIncrease[].value` | string | 上昇幅 |
| `equipment.enhance.levels[].durabilityBonus` | int \| null | このレベルで加算される最大耐久値 |
| `equipment.enhance.levels[].recipeId` | string \| null | 強化レシピ ID |
| `equipment.enhance.levels[].requiredMaterials[].itemId` | string | 素材アイテム ID |
| `equipment.enhance.levels[].requiredMaterials[].amount` | int | 必要数 |
| `equipment.enhance.levels[].requiredCurrency` | int \| null | 必要通貨量 |
| `equipment.enhance.levels[].successRate` | float | 強化成功率（0.0 〜 1.0） |
| `equipment.enhance.levels[].failAction` | string | 失敗時挙動（NONE / DOWNGRADE / DESTROY） |
| `equipment.enchant.maxSlots` | int | エンチャント最大スロット数 |
| `equipment.enchant.pools[].poolIndex` | int | API レスポンス上で自動採番されたプール番号（0 始まり） |
| `equipment.enchant.pools[].id` | string \| null | 任意のプール識別子 |
| `equipment.enchant.pools[].recipeId` | string \| null | エンチャントプール使用レシピ ID |
| `equipment.enchant.pools[].requiredMaterial.itemId` | string | プール発動に必要な素材アイテム ID |
| `equipment.enchant.pools[].requiredMaterial.amount` | int | 必要な素材個数 |
| `equipment.enchant.pools[].requiredCurrency` | int | エンチャント実行に必要な通貨量 |
| `equipment.enchant.pools[].entries[].status` | string \| null | 付与されるステータス名 |
| `equipment.enchant.pools[].entries[].type` | string \| null | 補正方式（FLAT / SCALAR） |
| `equipment.enchant.pools[].entries[].value` | string \| null | 付与値（固定値または範囲） |
| `equipment.enchant.pools[].entries[].weight` | int | 抽選重み（値が大きいほど選ばれやすい） |
| `equipment.rune.maxSlots` | string | 最大ルーンスロット数。固定値に加えて YAML の random 指定も "1~3" 形式で返却 |
| `equipment.rune.allowedRuneIds` | string[] | 装着を許可するルーン ID リスト（空の場合は全ルーン許可） |
| `equipment.transcendence[].name` | string \| null | 状態変化の名称（例: "進化"） |
| `equipment.transcendence[].rank` | int | 状態変化の強さ指標 |
| `equipment.transcendence[].recipeId` | string \| null | 状態変化レシピ ID |
| `equipment.transcendence[].requiredMaterials[].itemId` | string | 必要素材アイテム ID |
| `equipment.transcendence[].requiredMaterials[].amount` | int | 必要数 |
| `equipment.transcendence[].requiredCurrency` | int | 必要通貨量 |
| `equipment.transcendence[].overrides.name` | string \| null | 状態変化後のアイテム名称 |
| `equipment.transcendence[].overrides.enhance.maxLevel` | int | 状態変化後の強化最大レベル |
| `equipment.transcendence[].overrides.enchant.maxSlots` | int | 状態変化後のエンチャント最大スロット数 |

### rune フィールド

| フィールド | 型 | 説明 |
|---|---|---|
| `rune.targetSlots` | string[] | 装着可能な装備スロット種別（WEAPON / HEAD / CHEST など。ANY で全スロット対応） |
| `rune.requiredEnhanceLevel` | int | 装備側に必要な最低強化レベル（0 で制限なし） |
| `rune.stats[].status` | string \| null | 付与されるステータス名（StatusType） |
| `rune.stats[].type` | string \| null | 補正方式（FLAT / SCALAR） |
| `rune.stats[].value` | string \| null | 補正値（固定値または範囲 例: "3~8"） |
| `rune.stats[].random` | string \| null | 装備作成時のランダム変動幅 |
| `rune.skills` | string[] | ルーン装着中に付与されるスキル ID リスト |

### bundle フィールド

| フィールド | 型 | 説明 |
|---|---|---|
| `bundle.lootTableId` | string \| null | LootTable ID |
| `bundle.items[].itemId` | string | 付与するアイテム ID |
| `bundle.items[].amount` | string | 付与数（固定値または範囲 例: "1~3"） |
| `bundle.items[].rate` | float | 付与確率（0.00 〜 100.00） |
| `bundle.items[].luckAffected` | bool | 幸運ステータスによる確率補正を受けるか |
| `bundle.items[].hidden` | bool | 図鑑などに表示しない秘密ドロップか |
| `bundle.onUse.sound` | string \| null | 使用時サウンド |
| `bundle.onUse.particle` | string \| null | 使用時パーティクル |

### 400 Bad Request

（非推奨）該当エンドポイントは返しません。

### 401 Unauthorized

API キーが指定されていない、または無効。

### 404 Not Found

対象アイテムが存在しない。
