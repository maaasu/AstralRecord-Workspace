# Shop YAML schema

Shop master data defines a named shop and the item entries shown by the plugin shop GUI.

## スキーマ定義

| Key | Type | Required | Description |
|:--|:--|:--:|:--|
| `schemaVersion` | Integer | yes | Schema version. Current value is `1`. |
| `id` | String | yes | Shop ID. |
| `type` | String | yes | Resource type. Use `SHOP`. |
| `name` | String | yes | GUI title. Color code `&` is allowed. |
| `mode` | String | no | `SHOP` / `EXCHANGE`. Default `SHOP`. `EXCHANGE` uses exchange wording in the shared shop GUI. |
| `access` | String | no | `PUBLIC` / `NPC_ONLY`. Default `PUBLIC`. `NPC_ONLY` is excluded from `/shop` lookup and tab completion. |
| `items[]` | List | yes | Sale item definitions. |
| `items[].id` | String | yes | Entry ID inside the shop. |
| `items[].itemId` | String or `{ ref }` | yes | Sold item ID. `ref: item:<id>` is allowed. |
| `items[].category` | String | yes | Item category used when loading the item. |
| `items[].amount` | Integer | no | Amount received per purchase unit. Default `1`. |
| `items[].page` | Integer | no | 1-based GUI page number. Default `1`. |
| `items[].slot` | Integer | no | Logical sale slot. Valid range is `0` to `27`. |
| `items[].row` | Integer | no | 1-based row in the sale area. Used with `column` when `slot` is absent. |
| `items[].column` | Integer | no | 1-based column in the sale area. Used with `row` when `slot` is absent. |
| `items[].priceGold` | Integer | no | Gold cost per purchase unit. Default `0`. |
| `items[].requiredItems[]` | List | no | Direct item or currency cost per purchase unit. |
| `items[].requiredItems[].itemId` | String or `{ ref }` | yes | Required item ID. |
| `items[].requiredItems[].category` | String | no | Required item category. Default `material`. `currency` consumes the matching CURRENCY balance. |
| `items[].requiredItems[].amount` | Integer | yes | Required amount per purchase unit. |
| `items[].recipeId` | String or `{ ref }` | no | Additional cost source. A `recipe` with `category: SHOP` may be referenced. |

## Slot rule

The plugin uses a 54-slot inventory and does not place sale items in edge columns. Logical slot `0` maps to row 1, column 1 of the inner sale area. Logical slots `0` to `27` are accepted. Row/column input is 1-based and converted to the same logical range.

`items[].page` is 1-based. When omitted, the entry is placed on page `1`. The GUI shows entries whose `page` matches the current page and uses slot `45` as the previous-page button and slot `53` as the next-page button.

## YAML 例

```yaml
schemaVersion: 1
id: weapon_shop
type: SHOP
name: "&6武器・ツールショップ"
items:
  - id: starter_pickaxe
    itemId:
      ref: item:starter_pickaxe
    category: equipment
    slot: 2
    page: 1
    priceGold: 0
```

## NPC 専用両替所

`mode: EXCHANGE` と `access: NPC_ONLY` を組み合わせると、ショップ GUI の操作系を再利用しつつ、NPC interaction からだけ開ける両替所を定義できます。交換元通貨は `requiredItems[].category: currency` で指定し、交換先通貨を通常の商品と同じ `itemId` / `amount` で定義します。
