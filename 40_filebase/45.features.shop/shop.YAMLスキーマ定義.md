# Shop YAML schema

Shop master data defines a named shop and the item entries shown by the plugin shop GUI.

## Schema

| Key | Type | Required | Description |
|:--|:--|:--:|:--|
| `schemaVersion` | Integer | yes | Schema version. Current value is `1`. |
| `id` | String | yes | Shop ID. |
| `type` | String | yes | Resource type. Use `SHOP`. |
| `name` | String | yes | GUI title. Color code `&` is allowed. |
| `items[]` | List | yes | Sale item definitions. |
| `items[].id` | String | yes | Entry ID inside the shop. |
| `items[].itemId` | String or `{ ref }` | yes | Sold item ID. `ref: item:<id>` is allowed. |
| `items[].category` | String | yes | Item category used when loading the item. |
| `items[].amount` | Integer | no | Amount received per purchase unit. Default `1`. |
| `items[].slot` | Integer | no | Logical sale slot. Valid range is `0` to `26`. |
| `items[].row` | Integer | no | 1-based row in the sale area. Used with `column` when `slot` is absent. |
| `items[].column` | Integer | no | 1-based column in the sale area. Used with `row` when `slot` is absent. |
| `items[].priceGold` | Integer | no | Gold cost per purchase unit. Default `0`. |
| `items[].requiredItems[]` | List | no | Direct material cost per purchase unit. |
| `items[].requiredItems[].itemId` | String or `{ ref }` | yes | Required item ID. |
| `items[].requiredItems[].category` | String | no | Required item category. Default `material`. |
| `items[].requiredItems[].amount` | Integer | yes | Required amount per purchase unit. |
| `items[].recipeId` | String or `{ ref }` | no | Additional cost source. A `recipe` with `category: SHOP` may be referenced. |

## Slot rule

The plugin uses a 54-slot inventory and does not place sale items in edge columns. Logical slot `0` maps to row 1, column 1 of the inner sale area. Logical slots `0` to `26` are accepted. Row/column input is 1-based and converted to the same logical range.

## Example

```yaml
schemaVersion: 1
id: starter_shop
type: SHOP
name: "&6Starter Shop"
items:
  - id: apple_entry
    itemId:
      ref: item:healing_potion_small
    category: consumable
    slot: 2
    priceGold: 100
    requiredItems:
      - itemId:
          ref: item:magic_crystal
        category: material
        amount: 1
```
