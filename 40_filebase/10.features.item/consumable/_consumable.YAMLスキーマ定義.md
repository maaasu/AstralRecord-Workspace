# CONSUMABLE (消耗品) YAML スキーマ定義

消耗品の基本的なスキーマ定義。

## スキーマ定義

| キー                                 | 型       | 必須 | デフォルト | 説明                                                              |
|:-----------------------------------|:--------|:--:|-------|:----------------------------------------------------------------|
| `maxStack`                         | Integer | ×  | 64    | アイテムの最大スタック数                                                    |
| `consumable[].onUse[].sound`       | String  | ×  | Null  | 使用時に流れるサウンド（SoundKey想定。例: `entity.player.levelup`）              |
| `consumable[].onUse[].effect`      | String  | ×  | Null  | 使用時のパーティクル（実装側で解釈。例: `happy_villager`）                          |
| `consumable[].onUse[].amount`      | Integer | ×  | 1     | 消費する個数（スタックから減る数）                                               |
| `consumable[].effects[]`           | List    | ○  | -     | 使用時に適用する効果のリスト（後述）                                              |
| `consumable[].effects[].type`      | String  | ○  | -     | 効果種別（`RECOVER` / `BUFF`）                                        |
| `consumable[].effects[].rate`      | Double  | ×  | 100   | 発動確率（0〜100）                                                     |
| `consumable[].effects[].value`     | Double  | ×  | -     | 回復量（type=RECOVER時に使用）                                           |
| `consumable[].effects[].status`    | String  | ×  | -     | 回復対象（type=RECOVER時に使用。`HP` / `MP`）                              |
| `consumable[].effects[].isPercent` | Boolean | ×  | false | trueの場合、`value` を割合（%）として扱う（例: 0.10 = 最大値の10%回復）※type=RECOVER専用 |
| `consumable[].effects[].buffId`    | String  | ×  | -     | 付与するBuff（type=BUFF時に使用）※参照値                                     |


### consumable.effects[].type
以下のいずれかの値を指定します。
- `RECOVER` : HP/MPなどの回復
- `BUFF` : Buffの付与

### consumable.effects[].status
type=RECOVER の場合に指定します。
- `HP`
- `MP`


## YAML 例

```yaml
schemaVersion: 1
id: temple_consumable
name: &bテンプレ消耗品
icon: potion
rarity: COMMON
lore:
  - &7テストポーション
untradeable: false

consumable:
  onUse:
    sound: entity.player.levelup
    effect: happy_villager
    amount: 1
  effects:
    - type: RECOVER
      status: HP
      value: 50
      isPercent: false
    - type: BUFF
      buffId:
        ref: buff:haste_small
      rate: 100
```