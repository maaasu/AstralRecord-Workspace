# CONSUMABLE (消耗品) YAML スキーマ定義

消耗品の基本的なスキーマ定義。

## スキーマ定義

| キー                                 | 型       | 必須 | デフォルト | 説明                                                              |
|:-----------------------------------|:--------|:--:|-------|:----------------------------------------------------------------|
| `maxStack`                         | Integer | ×  | 64    | アイテムの最大スタック数                                                    |
| `consumable.onUse.usingSound`      | String  | ×  | Null  | 使用待機の開始時と待機中に流れるサウンド（SoundKey想定。未指定時は `entity.generic.drink`） |
| `consumable.onUse.sound`           | String  | ×  | Null  | 効果適用後に流れるサウンド（SoundKey想定。例: `entity.player.levelup`）          |
| `consumable.onUse.effect`          | String  | ×  | Null  | 使用時のパーティクル（実装側で解釈。例: `happy_villager`）                          |
| `consumable.onUse.amount`          | Integer | ×  | 1     | 消費する個数（スタックから減る数）                                               |
| `consumable.onUse.useTimeTicks`    | Long    | ×  | 40    | 使用完了まで静止する必要がある時間（tick 単位。20 tick = 1 秒）                        |
| `consumable.onUse.cooldownTicks`  | Long    | ×  | 40    | 使用成功後のクールタイム（tick 単位。20 tick = 1 秒）                                |
| `consumable[].effects[]`           | List    | ○  | -     | 使用時に適用する効果のリスト（後述）                                              |
| `consumable[].effects[].type`      | String  | ○  | -     | 効果種別（`RECOVER` / `BUFF`）                                        |
| `consumable[].effects[].rate`      | Double  | ×  | 100   | 発動確率（0〜100）                                                     |
| `consumable[].effects[].value`     | Double  | ×  | -     | 回復量（type=RECOVER時に使用）                                           |
| `consumable[].effects[].status`    | String  | ×  | -     | 回復対象（type=RECOVER時に使用。`HP` / `MP` / `ENERGY`）                  |
| `consumable[].effects[].isPercent` | Boolean | ×  | false | trueの場合、`value` を割合（%）として扱う（例: 0.10 = 最大値の10%回復）※type=RECOVER専用 |
| `consumable[].effects[].buffId`    | String  | ×  | -     | 付与するBuff（type=BUFF時に使用）※参照値                                     |


### consumable.effects[].type
以下のいずれかの値を指定します。
- `RECOVER` : HP/MPなどの回復
- `HEAL` : `RECOVER` と同じ回復効果（互換エイリアス）
- `BUFF` : Buffの付与

### consumable.effects[].status
type=RECOVER の場合に指定します。
- `HP`
- `MP`
- `ENERGY`


## YAML 例

```yaml
schemaVersion: 1
id: temple_consumable
name: &bテンプレ消耗品
icon: potion
rarity: COMMON
appearance:
  color: "#E84D4D"
  potionType: HEALING
lore:
  - &7テストポーション
untradeable: false

consumable:
  onUse:
    usingSound: entity.generic.drink
    sound: entity.player.levelup
    effect: happy_villager
    amount: 1
    useTimeTicks: 40
    cooldownTicks: 40
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

`usingSound` は使用中、`sound` は使用完了後のサウンドを指定します。食料などは `usingSound: entity.generic.eat` を指定してください。

## アカウント特典券

`INSTANCE_PRIORITY` は `value` 回のインスタンス優先接続回数、`VIP_DONER` は `value` 日のDONER、`VIP_ASTRALDER` は `value` 日のASTRALDERを使用中アカウントへ追加します。効果は1件、`rate: 100`、`isPercent: false`、`onUse.amount: 1` とします。右クリックで即時に原子的API操作を開始し、通常ポーションの使用時間・演出処理には渡しません。

ASTRALDERを先に消化し、その追加日数だけ残存DONER期限を繰り越します。ASTRALDER中にDONERを追加した場合もASTRALDER終了後から開始します。日数は使用確定時から24時間単位で加算します。ASTRALDER日次特典は日本時間のログイン日ごとに1回、券1枚につき最大20回です。未ログイン日の遡及付与はしません。特典券はトレード・売却不可です。
