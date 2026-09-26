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
| `consumable[].effects[].type`      | String  | ○  | -     | 回復・Buff・アカウント特典・チャンネルブーストの効果種別（下記） |
| `consumable[].effects[].rate`      | Double  | ×  | 100   | 発動確率（0〜100）                                                     |
| `consumable[].effects[].value`     | Double  | ×  | -     | 回復量（type=RECOVER時に使用）                                           |
| `consumable[].effects[].status`    | String  | ×  | -     | 回復対象（type=RECOVER時に使用。`HP` / `MP` / `ENERGY`）                  |
| `consumable[].effects[].isPercent` | Boolean | ×  | false | trueの場合、`value` を割合（%）として扱う（例: 0.10 = 最大値の10%回復）※type=RECOVER専用 |
| `consumable[].effects[].buffId`    | String  | ×  | -     | 付与するBuff（type=BUFF時に使用）※参照値                                     |
| `consumable.effects[].durationSeconds` | Integer | × | - | チャンネルブーストの有効秒数。ブースト効果では必須 |


### consumable.effects[].type
以下のいずれかの値を指定します。
- `RECOVER` : HP/MPなどの回復
- `HEAL` : `RECOVER` と同じ回復効果（互換エイリアス）
- `BUFF` : Buffの付与
- `INSTANCE_PRIORITY` / `VIP_DONER` / `VIP_ASTRALDER` : アカウント特典券
- `CHANNEL_EXP_BOOST` / `CHANNEL_DROP_BOOST` / `CHANNEL_SPECIAL_BOOST` : チャンネルブースト券

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

## チャンネルブースト券

`CHANNEL_EXP_BOOST` は `EXPERIENCE_GAIN_RATE`、`CHANNEL_DROP_BOOST` は `DROP_RATE_INCREASE` の最終値を `value` 倍にします。`CHANNEL_SPECIAL_BOOST` は両方へ同じ倍率を適用します。`value` は割合の加算値ではなく倍率（例: `1.1`）、`durationSeconds` は有効秒数です。現在は `3600` 秒のみを受け付け、販売する11種類もすべて1時間です。

効果は1件、`rate: 100`、`isPercent: false`、`onUse.amount: 1` とし、トレード・売却不可にします。ゲーム内では現在のチャンネルへ適用し、チケット1個の消費と発動を同時に確定します。同じ種類のブーストが有効な間は使用できず、拒否時には消費しません。EXPとドロップは別々に共存でき、スペシャルは両方が無効な場合だけ使用できます。

ブーストはチャンネル単位で独立し、そのチャンネルにいるプレイヤー全員へ適用します。使用確定から実時間で期限を進め、再起動で期限を延長しません。発動者・対象チャンネル・有効なブーストを全チャンネルへ通知し、`/server-info` とTABで現在の状態を確認できます。

Webの有償ショップではチケットを配送せず、選択したチャンネルへ購入時に直接発動します。重複条件はゲーム内使用と同じです。VIP・優先接続権もWeb購入時は現在のアカウントへ直接適用します。
