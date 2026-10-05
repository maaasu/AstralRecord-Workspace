# Skill YAML スキーマ定義

`40_filebase/30.features.skill/*.yml` に配置するスキル定義の schema です。

スキル種別は plugin 側の `SkillExecutor.kind()` が正本です。filebase 側ではパッシブスキルの場合に `passive.bindRequired` でバインド必要可否を定義します。

発動スキルの主リソース種別と消費量は、共通項目 `resourceType` / `resourceCost` へ定義します。`params` は実装固有の拡張値だけに使用し、リソース、クールダウン、詠唱時間などの共通項目を重複して定義しません。ENGとMPを同時消費する例外だけは `resourceType: ENERGY` / `resourceCost` に加えて正の `manaCost` を副MP消費として定義します。`GUARD` はガードコンバートのバインド中だけ存在するパラディン専用リソースで、消費軽減を適用しません。

## 共通定義

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `schemaVersion` | Integer | 必須 | - | 現在は `1` |
| `id` | String | 必須 | - | スキル ID |
| `type` | String | 必須 | - | `SKILL` |
| `implementationId` | String | 必須 | - | plugin 側実装 ID |
| `name` | String | 必須 | - | 表示名 |
| `description` | String | 任意 | `null` | `&8【概要】 &7` で始める主目的の簡潔な一文。本文は目安40文字以内とし、効果詳細や数値は `lore` に記載する |
| `icon` | String | 任意 | `null` | Material 名 |
| `iconTexture` | String | 任意 | `null` | `icon: PLAYER_HEAD` 時だけ適用する Base64 の `textures` 値。復号JSONの`textures.SKIN.url`は公式 texture URL に限定する |
| `lore` | List<String> | 任意 | `[]` | 規定色の `【分類】` と白い本文による効果詳細。1行1要素を基本とし、共通書式とRPG説明チェックを適用する |
| `conditionalLore` | List | 任意 | `[]` | 指定スキルの使用許可を持つ閲覧者だけへ追加表示する説明行 |
| `conditionalLore[].requiredSkillId` | String | 必須 | - | 表示条件となる `skill:` 接頭辞付きスキル参照 |
| `conditionalLore[].lines` | List<String> | 任意 | `[]` | 条件を満たした閲覧者へ表示する説明行。`lore` と同じ共通書式を使う |
| `cooldownTicks` | Long | 任意 | `0` | クールダウン |
| `cooldownId` | String | 任意 | `id` | 同一プレイヤー内で共有するクールダウン ID。発動スキル自身のクールダウン時間を共有グループへ設定する |
| `resourceType` | String | 任意 | `MANA` | 消費リソース種別。`MANA` / `ENERGY` / `GUARD` |
| `resourceCost` | Double | 任意 | `0` | `resourceType` で指定したリソースの消費量。0以上を指定する |
| `manaCost` | Double | 任意 | `0` | 通常は旧定義との互換用MP消費量。`resourceType: ENERGY` と正数を併記した場合だけ、副MP消費として主ENGと同時に検証・消費する |
| `castTimeTicks` | Long | 任意 | `0` | 詠唱時間。冷気中は最終値が2倍 |
| `requiredLevel` | Integer | 任意 | `1` | 必要レベル |
| `onCast` | Map | 任意 | `null` | 発動時演出設定 |
| `onCast.sound` | String | 任意 | `null` | 再生する sound key |
| `passive` | Map | 任意 | `null` | パッシブ設定 |
| `passive.bindRequired` | Boolean | 任意 | `true` | `false` は所持のみで常時有効 |
| `maxLevel` | Integer | 任意 | `1` | 習得済み個体の最大レベル |
| `levels[]` | List | 任意 | `[]` | 対象レベルへ上がる際に前レベルから加算する差分 |
| `levels[].level` | Integer | 必須 | - | 差分を適用する到達レベル（2以上、`maxLevel` 以下） |
| `levels[].cooldownTicksDelta` | Long | 任意 | `0` | 基礎クールダウンへの加算 tick |
| `levels[].resourceCostDelta` | Double | 任意 | `0` | 基礎消費量への加算 |
| `levels[].castTimeTicksDelta` | Long | 任意 | `0` | 基礎詠唱時間への加算 tick |
| `levels[].paramDeltas` | Map<String, Double> | 任意 | `{}` | 数値 params への加算。配列要素は `damageRatios[0]` のように index を指定 |
| `levels[].statusModifiers[]` | List | 任意 | `[]` | このスキルの計算中だけ加算するステータス補正。共有カタログの既知status IDだけを指定 |
| `sigilSlotsByLevel[]` | List | 任意 | `[]` | 指定レベル以降のシジル装着可能数。現在レベル以下で最大の定義を採用 |
| `allowedSigilIds` | List<String> | 任意 | `[]` | このスキルへ合成可能なシジル ID |
| `learnRequiredItems[]` | List | 任意 | `[]` | 初回習得時に消費するアイテム。未指定なら無条件で習得可能 |
| `learnRequiredItems[].itemId` | String | 必須 | - | 消費する item ID |
| `learnRequiredItems[].amount` | Integer | 任意 | `1` | 消費個数。1以上 |
| `levelUpRequiredItems[]` | List | 任意 | `[]` | レベルアップ時に消費するアイテム。未指定なら無条件で可能 |
| `levelUpRequiredItems[].itemId` | String | 必須 | - | 消費する item ID |
| `levelUpRequiredItems[].amount` | Integer | 任意 | `1` | 消費個数。1以上 |
| `params` | Map<String, Any> | 任意 | `{}` | Executorと説明文で共有する実効値。共通項目は定義しない |
| `tags` | List<String> | 任意 | `[]` | `76.shared.tag/v1.tags.yml`の`SKILL`対象タグID |

### 継承の心得の `params.inheritanceBuffs[]`

`sharpshooter_inheritance_mastery` は、継承バフごとに次の項目を定義します。

`params.inheritedSkillDamageMultiplier` は継承によって発動する元スキルのダメージ倍率へ掛ける有限の正数です。現行の継承の心得では `0.5` を指定します。通常攻撃自身へ掛ける `params.normalAttackDamageMultiplier` とは別に適用します。

| キー | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `buffId` | String | 必須 | `buff:` 接頭辞付きの継承バフ参照 |
| `durationConsumptionTicks` | Long | 必須 | 通常攻撃1回で消費する継承バフの残り時間。1以上の整数 |
| `sourceSkillId` | String | `resourceType` / `resourceCost` 未指定時 | `skill:` 接頭辞付きの付与元スキル参照。付与元のレベル・シジル反映済み効果と、MPを含む複合リソース消費を参照する。`consumeSourceSkillResources: false` の場合は効果の参照だけを行い、元スキルのリソースは再消費しない |
| `consumeSourceSkillResources` | Boolean | 任意 | `sourceSkillId` 指定時、次の通常攻撃で元スキルの全消費リソースを再消費するか。既定は `true`。会心率補正や属性付与のように元スキルを再発動しない継承では `false` を指定する |
| `normalAttackExtraCount` | Integer | 任意 | 通常攻撃を追加発生させる回数。指定時は1～4、`normalAttackExtraIntervalTicks`と`consumeSourceSkillResources: false`が必須 |
| `normalAttackExtraIntervalTicks` | Integer | 任意 | 通常攻撃から各追撃までの間隔。1以上で、`normalAttackExtraCount`と併用する |
| `resourceType` / `resourceCost` | String / Double | `sourceSkillId` 未指定時 | 継承効果ごとの固定追加リソース消費。両方を指定し、`sourceSkillId` と併記しない |

`sourceSkillId` も固定リソース定義もない継承バフは、追加リソースを消費しません。

シャープシューターの追加4技（`sharpshooter_piercing_line` / `sharpshooter_thermal_break` / `sharpshooter_ricochet_arrow` / `sharpshooter_glacial_trap`）は、この既存契約で各専用継承バフへ対応付けます。`consumeSourceSkillResources: true`、`durationConsumptionTicks: 600` とし、バフの `durationTicks: 2980` を正本にします。各skillの `params.inheritanceDurationTicks` / `params.inheritanceConsumptionTicks` はLore表示用の同期値です。通常攻撃が管理対象MobのHPまたはShieldへ有効命中した場合だけ、元の解決済み効果・消費を使って追撃します。地形着弾・回避・無効ダメージでは消費しません。

`swordsman_bastion_strike` の `params.range: 6.0` と `params.damageRatio: 1.875` は、シールド破壊時の視線ライン反撃に使うexecutor固有の値です。`params.consumeAllCurrentMana: true` と `params.levelFiveRequiredManaRatio: 0.80` は、反撃成立時の現在MP全消費とLv.5の必要MP比率を定義します。`passive.bindRequired: true` により、使用許可だけでなくパッシブスロットへのバインドを要求します。`resourceType: MANA` と `resourceCost: 0` は共通消費を重ねないための定義であり、反撃成立時のMP全消費はexecutorが行います。

複合消費では `ENERGY_COST_REDUCTION` と `MANA_COST_REDUCTION` を各消費へ個別に適用します。片方でも残量不足なら発動前に全消費を拒否し、executor成功時だけ両方を消費します。GUIも主ENGと副MPを別行で表示します。

`GUARD` は `MANA_COST_REDUCTION` / `ENERGY_COST_REDUCTION` の対象外です。発動前と詠唱完了時に現在ガードを検証し、executor成功時だけ定義量を消費します。

## 説明文プレースホルダー

`description` はスキルの主目的を短く示すために使い、具体的な効果詳細、発動条件、対象数、持続時間、例外処理は `lore` に記載します。`lore` の各行は1つの効果または条件に分け、表示が横へ伸びないようにします。消費リソース、クールダウン、詠唱時間は top-level 項目と GUI の専用表示を正とし、説明上必要な場合だけ共通値用プレースホルダーで参照します。

`description` と `lore` では、解決済み `params` と top-level 共通値を次の形式で参照できます。

| 記法 | 内容 |
| --- | --- |
| `{skill.range}` | 数値を自動整形して表示 |
| `{skill.name}` | 色コードを除いたスキル表示名 |
| `{skill.damageRatio:percent}` | `1.25` を `125` として表示 |
| `{skill.damageRatios[0]:percent}` | 数値配列の指定要素を表示 |
| `{skill.damageRatios:percent}` | 数値配列を ` / ` 区切りで表示 |
| `{skill.durationTicks:seconds}` | tick値を20で割り、秒へ変換して表示 |
| `{skill.<param>:seconds}` | 数値パラメータを20で割り、秒へ変換して表示 |
| `{skill.cooldownTicks:seconds}` | top-level クールダウンtickを20で割り、秒へ変換して表示 |
| `{skill.level}` / `{skill.maxLevel}` | 習得レベル・最大レベル |
| `{skill.skillDamageIncrease}` | レベル・シジル由来の `SKILL_DAMAGE_INCREASE` 合計 |
| `{skill.effectiveDamageRatio:percent}` | `damageRatio × (1 + skillDamageIncrease / 100)` |
| `{skill.effectiveChainDamageRatio:percent}` | `chainDamageRatio × (1 + skillDamageIncrease / 100)` |
| `{skill.effectiveDamageRatios:percent}` | `damageRatios` の各要素へ同じ補正を適用した配列 |

説明文は、基礎定義を直接表示せず、`LearnedSkillResolver` がレベル差分と有効シジルを合成した値を使って展開します。top-level の `cooldownTicks` も解決済みスキルの値を参照します。未知のプレースホルダーは `?` として表示されるため、マスターデータ検証で修正します。

展開された数値プレースホルダー（数値配列を含む）は GUI 上で黄色＋太字にします。スキル名などの文字列プレースホルダーは本文側の色・装飾を引き継ぎます。強調は置換値の中だけに適用し、後続の単位や本文へ持ち越しません。数値は小数第2位を四捨五入して小数第1位まで表示し、末尾の0は表示しません（例: `120.75` → `120.8`）。

配列は、跳ね矢のように命中順で値が異なる場合に使用します。単純な「同じ倍率を4回」は配列ではなく、倍率と回数を別の数値 params として定義します。

## スキル説明の共通書式

アクティブ、パッシブ、管理者向け、Mob向け、通常攻撃を変更するスキルに同じ書式を使います。`conditionalLore[].lines` も詳細と同じ規則です。概要と詳細の両方がある場合、Plugin の共通表示処理が間に空行を挿入します。マスタへ区切り用の空行は書きません。

| 内容 | 書式・色 |
| --- | --- |
| 概要 | `&8【概要】 &7` に続けて主目的を一文で記載 |
| 効果、攻撃、参照、範囲、対象、移動、持続 | `&b【分類】 &f`：水色ラベル＋白い本文 |
| 回復、防御、強化 | `&a【分類】 &f`：緑ラベル＋白い本文 |
| 状態、弱体 | `&d【分類】 &f`：紫ラベル＋白い本文 |
| 条件、継承、制限 | `&6【分類】 &f`：橙ラベル＋白い本文 |
| 参照能力値、ダメージ属性 | 本文中で `&b魔法攻撃力&f`、`&b無属性&f` などと強調 |
| 状態異常・弱体の名称 | 本文中で `&d凍結&f`、`&d感電&f` などと強調 |
| 回復、シールド、ガード、剣気 | 本文中で `&a回復&f` などと強調 |
| 可変数値 | `{skill.…}` のみを記載。黄色・太字は共通表示処理が付与 |

分類名は表の語を使います。一行に複数種類の効果がある場合は行を分けるか、全体に合う `【効果】` を使い、単語の一致だけで分類を決めません。本文内の色指定は強調語の直後に `&f` を置いて白へ戻します。`&e` や `&l` をマスタへ直接記載せず、数値の強調を表示処理へ集約します。状態名と参照式は実装の意味を保持し、燃焼と炎上などの表記を推測で統合しません。

```yaml
description: "&8【概要】 &7氷球を放ち、着弾地点を氷晶で砕く魔法。"
lore:
  - "&b【範囲】 &f射程{skill.range}mの氷球を放つ。"
  - "&b【攻撃】 &f半径{skill.radius}mへ、&b魔法攻撃力&f{skill.effectiveDamageRatio:percent}%の&b氷属性&fダメージ。"
  - "&d【状態】 &f{skill.freezeChance}%で{skill.freezeDurationTicks:seconds}秒間&d凍結&fさせる。"
```

## RPGのスキル説明としての確認

- 概要だけで技の役割が分かり、詳細から対象・範囲・参照能力値・倍率・属性・状態異常・持続・発動条件を必要な範囲で判断できること。
- 自身が跳躍、後退、突進、瞬間移動する技では、その動作と必要な入力を残すこと。移動の成否が効果や消費へ影響する条件も残します。
- 敵を貫く、反射する、跳弾する、遅れて着弾する、設置後に持続するなど、技の選択・使い分けに関わる固有効果は短く記載します。
- 壁を貫通しない、遮蔽を確認する、弾の重力・速度・軌跡・消滅処理などの細かな実装説明をゲーム内の説明へ持ち込みません。粒子、音、描画API名、HUDの更新説明も不要です。必要な実装情報はPlugin設計書やYAMLコメントへ記載します。
- 攻撃間隔などプレイヤーに必要な時間は秒で表示し、tick値は `:seconds` で変換します。数値・参照式・条件を簡略化する際は実装を確認し、説明のためにゲーム効果を変更しません。
- 効果がない状態、再使用条件、重複上限、中断、追加消費など、プレイヤーの判断に必要な制約を削らず、同じ内容の繰り返しと演出だけの説明を省きます。

作成・修正後は `.codex/skills/astralrecord-skill-author/scripts/validate_skill_descriptions.py` で概要、詳細、条件付き説明の書式と参照を確認します。RPGとしての情報の必要性と分類の妥当性は、上記項目に沿って実装と照合してレビューします。

## 職業発動スキルの定義方針

- プレイヤー向け職業発動スキルは、`id` と `implementationId` を同じ値にします。
- ID は職業または共有職系の prefix と lowercase snake_case を組み合わせます。職業 prefix は `swordsman_` / `hunter_` / `mage_` などを使い、シャープシューターとファントムアーチャーの共用スキルには `archer_` を使います。
- スキルジェムは生成しません。初回習得とレベルアップで消費する素材は `learnRequiredItems` / `levelUpRequiredItems` に定義します。
- 当たり判定、攻撃種別、倍率、状態異常、演出の詳細は `implementationId` に対応する Plugin 実装が解釈しますが、プレイヤーへ表示する値とレベル・シジルで変動する値は `params` を実装と説明文の共通正本にします。
- `description` / `lore` の可変値は固定数値を直接記載せず、プレースホルダーで参照します。Executor側も同じ `params` を参照し、表示と実際の効果を一致させます。
- リソース種別、消費量、クールダウン、詠唱時間、必要レベル、共通発動音は、それぞれの top-level 項目へ定義します。

## 共通攻撃 executor の params

### ファントムアーチャーの `params`

専用アクティブ7種と専用パッシブ3種は、`phantom_archer_` で始まる固有の `implementationId` を使用する。既存パッシブ3種も同名の固有実装へ切り替え、`modifiers` のステータス補正を維持する。固有パッシブは `maxLevel: 1`、`passive.bindRequired: true`、成長差分なし・シジル枠なしを必須とし、固有倍率は公開済みLv1定義から解決する。以下は対応する実装だけが使う追加項目であり、他職の必須項目にはしない。

| キー | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `markDamageRatio` | Double | 対応実装のみ | `soul_pierce` / `eclipse_shot` で必要な有限正数。命中前の自分の霊印1層ごとに加算する間接攻撃倍率。レベル差分で加算可能 |
| `shotCount` | Integer | 対応実装のみ | `spectral_sentry` は1～16の射撃機会数、`echo_volley` は2～6の射撃数。対象不在の設置弓の機会も消費する |
| `chargeTicks` | Integer | 対応実装のみ | `eclipse_shot` の内部準備時間。1～40tick。詠唱とは別で、消費とCDは発動成功時に開始。中断時の返還なし |
| `markBonusRatio` | Double | 対応実装のみ | `curse_weaving` の有限正数。ソウルピアースの霊印1層の加算倍率へ加える。エクリプスショットには適用しない |
| `phantomDamageRatio` | Double | 対応実装のみ | `wraith_step` の有限正数。後退後の幻影矢に、発射元の基礎 `damageRatio` へ掛けて使う。霊印・弱体特効の追加倍率は含めない |
| `readyDurationTicks` | Integer | 対応実装のみ | `wraith_step` の幻影矢準備の有効tick。1～400。ミストリトリートで後退成功時だけ開始し、次の専用直射で1回消費する |

霊印は状態異常カタログを増やさず、発動者×Mobごとの専用ランタイム状態として扱う。上限3層、最後の付与から120tickという固定契約。ステッチ・ブルーム・セントリー・エコーの有効命中で1層増加し、スペクトラルアチューンメント有効中は弓・短弓・長弓の通常攻撃でも増加する。ピアース・エクリプスは命中前の層を倍率へ加算し、同じ命中がHPまたはShieldへ正のダメージを与えたときだけその対象の印を消費する。回避では消費しない。

ソウルピアースは `(damageRatio + 層数 × (markDamageRatio + 有効なカースのmarkBonusRatio)) × 弱体特効倍率`。エクリプスショットは `damageRatio + 層数 × markDamageRatio`。レイスステップの追加矢は `damageRatio × phantomDamageRatio` で、印の付与・消費・弱体特効を行わない。

ローカル編集サイトはこの表から `/params/markDamageRatio` 等の型と日本語説明を動的に読み、キーと実装IDを元の表記で保持する。共通YAML/APIの型や保存形式は変更しない。実装固有の範囲検証はPluginの `validateParams` を正本とする。

### ライムノヴァの `params`

`wizard_rime_nova` は発動地点に固定した球形範囲へ氷の波を広げる。`radius` は有限正数で10m以下、`maxTargets` は1～8の整数。各対象への攻撃判定は一発動につき一回で、回避・完全防御も対象数に含める。波は発動後1/3/5/7/9tickに最大半径の20/40/60/80/100%へ届き、その時点のMob実body boundsと遮蔽を共通サービスで判定する。発動者の移動で中心は移動しない。

以下の追加キーはライムノヴァ実装だけで使用する。未指定の旧マスタも40tickの凍結で動作し、Pluginの説明文プレースホルダーにも同じ既定値を使う。明示値があればそれを優先し、他スキルの必須項目にはしない。編集サイトは表から型・説明を動的に読み込む。実装固有の範囲と必須条件はPluginの `validateParams` を正本とする。

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `frozenTicks` | Integer | 任意 | 40 | `wizard_rime_nova` の凍結付与時間。1～60tick。未指定時40tick。`conditionChance` を付与確率に使用し、冷気とは別に凍結耐性を適用する |

`conditionTicks` は冷気の付与時間、`conditionChance` は凍結と冷気のそれぞれの基礎付与確率。HPまたはShieldへ正のダメージが入った対象にだけ両状態を付与する。ボスへの制御時間短縮・状態異常持続時間補正は既存の状態異常サービスに従う。燃焼中は `damageRatio × burningMultiplier` とし、燃焼は消費しない。

### 共通攻撃の `params`

既存の `normal_attack` など、1つの executor を複数マスタで共有する実装は次の戦闘パラメータを使用します。職業発動スキルにはこの一覧を機械的に複製しません。

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `attackType` | String | 任意 | `MELEE` | `MELEE` / `RANGED` / `MAGIC`。攻撃力・能力値・防御力の参照元を決める |
| `damageComponents` | List | 任意 | 無属性100% | 属性別ダメージ倍率。各成分を独立して属性補正後に合算する |
| `damageComponents[].element` | String | 必須 | - | `NONE` / `FIRE` / `ICE` / `LIGHTNING` |
| `damageComponents[].ratio` | Double | 必須 | - | 攻撃種別から求めた攻撃力に掛ける倍率。`0.8` は80% |
| `activationRange` | Double | 任意 | `hitRange` | Mob AI が発動を開始する最大距離 |
| `hitRange` | Double | 任意 | 攻撃種別ごとの既定値 | 近接判定またはprojectileの最大射程 |
| `hitRadius` | Double | 任意 | `0.75` | 近接判定またはprojectile衝突判定の半径 |
| `impactRadius` | Double | 任意 | `0` | projectile命中時に追加対象を探す半径。`0`は単体命中 |
| `maxTargets` | Integer | 任意 | 近接`8` / projectile`1` | 近接判定または着弾範囲が命中させる上限数 |
| `conditions` | List | 任意 | `[]` | 命中時に付与する状態異常定義 |
| `castSound` | String | 任意 | `null` | 詠唱開始 sound key |
| `castSoundVolume` | Double | 任意 | `1.0` | 詠唱開始音量 |
| `castSoundPitch` | Double | 任意 | `1.0` | 詠唱開始音ピッチ |

`damageComponents` を省略したスキルは `NONE` 1.0 として扱います。たとえば火0.6と、氷0.3＋雷0.3は属性補正前の総倍率がどちらも0.6なので、基礎DPSは同じです。

## conditions params

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `conditions[].type` | String | 必須 | - | `BURNING` / `FROZEN` / `CHILLED` / `SHOCKED` / `POISON` / `BLINDNESS` / `WEAKNESS` / `HEALING_INHIBITION` |
| `conditions[].chance` | Double | 任意 | `100` | 基礎付与確率（%） |
| `conditions[].durationTicks` | Long | 任意 | 種別既定 | 効果時間 |
| `conditions[].strength` | Double | 任意 | `1.0` | 同種重複時の強さ比較値 |
| `conditions[].basePower` | Double | 任意 | 種別既定 | 固定DoT値 |
| `conditions[].powerCoefficient` | Double | 任意 | 種別既定 | 付与元の基準能力値に対するDoT係数。炎上・感電は解決攻撃力、毒は `SUPPORT_POWER` を参照し、種別既定係数を上限とする |
| `conditions[].healthRate` | Double | 任意 | 種別既定 | 非DoT用のHP割合。DoTでは無視され、対象HPを参照しない |
| `conditions[].tickIntervalTicks` | Integer | 任意 | 種別既定 | DoT間隔。0以下はDoTなし |

同種状態異常は強い効果だけを保持し、終了時刻は既存より後になる場合だけ延長します。DoTは会心しません。状態異常の付与耐性とDoT耐性は独立し、DoT貫通はDoT耐性だけを相殺します。

## 状態異常既定値

| 種別 | 時間 | 既定効果 |
| --- | ---: | --- |
| `BURNING` | 100 tick | 20 tickごとに付与元の解決攻撃力×0.20 |
| `FROZEN` | 40 tick | 全行動不能 |
| `CHILLED` | 100 tick | 移動50%、詠唱時間2倍 |
| `SHOCKED` | 100 tick | 20 tickごとに付与元の解決攻撃力×0.10、16～32 tickごとに6 tick移動・ジャンプ不能 |
| `POISON` | 120 tick | 20 tickごとに付与元の `SUPPORT_POWER`×0.16、HP 0 まで適用 |
| `BLINDNESS` | 100 tick | バニラ盲目 |
| `WEAKNESS` | 100 tick | 与える最終ダメージ50% |
| `HEALING_INHIBITION` | 100 tick | 通常の全回復を無効化 |

## 職業発動スキルの例

```yaml
schemaVersion: 1
id: adventurer_smash
type: SKILL
implementationId: adventurer_smash
name: "&f半月斬り"
description: "&8【概要】 &7前方を半月状に薙ぎ払う近接技。"
icon: IRON_SWORD
cooldownTicks: 40
resourceType: ENERGY
resourceCost: 12
castTimeTicks: 0
requiredLevel: 1
maxLevel: 5
levels:
  - level: 2
    statusModifiers:
      - status: SKILL_DAMAGE_INCREASE
        value: 5
sigilSlotsByLevel:
  - { level: 1, slots: 1 }
  - { level: 3, slots: 2 }
allowedSigilIds:
  - 70a00001
learnRequiredItems:
  - itemId: 10a00019
    amount: 1
levelUpRequiredItems:
  - itemId: 10a00019
    amount: 1
onCast:
  sound: entity.player.attack.sweep
tags:
  - active
  - swordsman
  - melee
```

## 共通攻撃 executor の例

```yaml
params:
  attackType: MAGIC
  damageComponents:
    - element: FIRE
      ratio: 0.8
  hitRange: 8
  conditions:
    - type: BURNING
      chance: 35
      durationTicks: 100
```
