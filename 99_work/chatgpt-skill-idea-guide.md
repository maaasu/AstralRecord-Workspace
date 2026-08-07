# ChatGPT用：AstralRecordのスキル案作成ガイド

このファイルは、Minecraft MMO RPG「AstralRecord」のスキル案を ChatGPT で考えるための参照案内です。
ChatGPT Project にこのファイルを追加し、必要に応じて下記の GitHub `develop` ブランチを参照させてください。

このファイル自体は、個別スキルの確定仕様ではありません。スキルの現在仕様、利用可能な項目、実装可能な挙動、数値の基準は、リンク先の正本を読み直して判断します。

## 1. リポジトリと参照ブランチ

| 項目 | URL・説明 |
|:--|:--|
| 公開リポジトリ | [maaasu/AstralRecord-Workspace](https://github.com/maaasu/AstralRecord-Workspace) |
| RPG 資料の入口 | [develop ブランチ](https://github.com/maaasu/AstralRecord-Workspace/tree/develop) |
| この案内ファイル | [99_work/chatgpt-skill-idea-guide.md](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/99_work/chatgpt-skill-idea-guide.md) |

この資料作成時点では、公開リポジトリの `master` は初期コミットだけで、現在の RPG の設計書・実装・filebase は `develop` にあります。スキル案を考えるときは、リポジトリのトップページから自動で選ばれるブランチに任せず、必ず `/tree/develop/` または `/blob/develop/` の URL を開いてください。

また、ローカルの `develop` にだけ存在する未 push の変更は、GitHub を参照する ChatGPT からは見えません。GitHub 上の `develop` とローカルの内容が異なる場合は、ChatGPT は GitHub 側で確認できた内容だけを「確定済み」として扱い、差分が必要ならユーザーへ確認します。

## 2. ChatGPT に期待する役割

ChatGPT は、単に名前や派手な効果を並べるのではなく、次の順番でスキル案を作成します。

1. `develop` ブランチの正本を読み、ゲームの役割・既存スキル・実装可能な効果を確認する。
2. 既存の `skill` YAML を全件確認し、名前だけでなく、戦闘距離・当たり判定・属性・発動タイミング・コスト・クールダウン・状態異常・移動・防御効果まで比較する。
3. 対象職業に既にある役割を避け、明確に異なるプレイ判断を追加する。
4. 戦闘バランス設計書の TTK、耐久時間、実戦 DPS、操作時間を基準に、数値を試算する。
5. 既存の共通サービスで実現できるか、新しい Plugin executor が必要かを分けて記載する。
6. 「リポジトリで確認できた事実」「今回の提案」「設計判断が必要な未確定事項」を明確に分ける。

GitHub の参照に失敗した場合は、見えていない仕様を推測して確定値のように書かず、必要なファイルの提示を依頼してください。

## 3. 正本の読み順

スキル案を作るときは、少なくとも次の順で読みます。すべてのコードを最初から読む必要はありませんが、数値や実装可否を断定する前に該当する正本を確認します。

| 順番 | 確認する内容 | リポジトリ内の正本 |
|:--:|:--|:--|
| 1 | RPG 全体の構成とプロジェクト境界 | [`README.md`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/README.md) |
| 2 | 戦闘の目標値、TTK、耐久時間、通常攻撃との比率 | [`00_docs/60_戦闘バランス設計書/README.md`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/00_docs/60_%E6%88%A6%E9%97%98%E3%83%90%E3%83%A9%E3%83%B3%E3%82%B9%E8%A8%AD%E8%A8%88%E6%9B%B8/README.md)、[`02-成長曲線.md`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/00_docs/60_%E6%88%A6%E9%97%98%E3%83%90%E3%83%A9%E3%83%B3%E3%82%B9%E8%A8%AD%E8%A8%88%E6%9B%B8/02-%E6%88%90%E9%95%B7%E6%9B%B2%E7%B7%9A.md) |
| 3 | スキルカテゴリの方針、ID、progression | [`00_docs/50_Filebase設計書/feature/30-skill.md`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/00_docs/50_Filebase%E8%A8%AD%E8%A8%88%E6%9B%B8/feature/30-skill.md) |
| 4 | YAML に書ける項目、型、既定値、プレースホルダー | [`40_filebase/30.features.skill/docs.skill.YAMLスキーマ定義.md`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/40_filebase/30.features.skill/docs.skill.YAML%E3%82%B9%E3%82%AD%E3%83%BC%E3%83%9E%E5%AE%9A%E7%BE%A9.md) |
| 5 | 現在のスキル定義と既存スキルの重複 | [`40_filebase/30.features.skill/`](https://github.com/maaasu/AstralRecord-Workspace/tree/develop/40_filebase/30.features.skill) 配下の `v1.*.yml` 全件 |
| 6 | Plugin が扱う発動、所持、許可、executor、追加手順 | [`00_docs/10_Plugin設計書/feature/13-skill/`](https://github.com/maaasu/AstralRecord-Workspace/tree/develop/00_docs/10_Plugin%E8%A8%AD%E8%A8%88%E6%9B%B8/feature/13-skill) の `13_0-概要.md`、`13_1-モデル定義.md`、`13_4-統合フロー/13_4-統合フロー.md`、`13_6-発動スキル追加ガイド.md` |
| 6a | 既存 executor と共通サービスの実装可否 | [`10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/`](https://github.com/maaasu/AstralRecord-Workspace/tree/develop/10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill) |
| 7 | 職業の役割、成長、使用許可 | [`00_docs/50_Filebase設計書/feature/20-class.md`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/00_docs/50_Filebase%E8%A8%AD%E8%A8%88%E6%9B%B8/feature/20-class.md) と [`40_filebase/20.features.class/`](https://github.com/maaasu/AstralRecord-Workspace/tree/develop/40_filebase/20.features.class) |
| 8 | スキルツリーによる使用許可と解放順 | [`00_docs/50_Filebase設計書/feature/35-skilltree.md`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/00_docs/50_Filebase%E8%A8%AD%E8%A8%88%E6%9B%B8/feature/35-skilltree.md) と [`40_filebase/35.features.skilltree/`](https://github.com/maaasu/AstralRecord-Workspace/tree/develop/40_filebase/35.features.skilltree) |
| 9 | 使用できる status ID と表示メタデータ | [`40_filebase/75.shared.status/v1.status_types.yml`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/40_filebase/75.shared.status/v1.status_types.yml) |
| 10 | 使用できる tag ID と適用対象 | [`40_filebase/76.shared.tag/v1.tags.yml`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/40_filebase/76.shared.tag/v1.tags.yml) |
| 11 | 状態異常を使う場合の付与・発動可否・詠唱への影響 | [`00_docs/10_Plugin設計書/feature/27-condition/`](https://github.com/maaasu/AstralRecord-Workspace/tree/develop/00_docs/10_Plugin%E8%A8%AD%E8%A8%88%E6%9B%B8/feature/27-condition) |
| 12 | ダメージ、属性、防御、会心、状態異常の計算 | [`00_docs/60_戦闘バランス設計書/01-戦闘計算.md`](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/00_docs/60_%E6%88%A6%E9%97%98%E3%83%90%E3%83%A9%E3%83%B3%E3%82%B9%E8%A8%AD%E8%A8%88%E6%9B%B8/01-%E6%88%A6%E9%97%98%E8%A8%88%E7%AE%97.md) と [`00_docs/10_Plugin設計書/feature/14-combat/`](https://github.com/maaasu/AstralRecord-Workspace/tree/develop/00_docs/10_Plugin%E8%A8%AD%E8%A8%88%E6%9B%B8/feature/14-combat) |

### 正本の優先順位

複数資料で表現が異なる場合は、質問の種類に応じて次の優先順位で判断します。

| 質問 | 優先する資料 |
|:--|:--|
| YAML のキー・型・必須項目 | 対象カテゴリの YAML スキーマ |
| 現在存在するスキルの名前・数値・タグ | 個別の `40_filebase/30.features.skill/*.yml` |
| Plugin が実際に実行できる効果 | Plugin 設計書と Plugin ソースコード |
| スキルの所持、許可、ジェム、スキルツリー | `13-skill` 設計書、`35-skilltree` 設計書、関連 API 設計 |
| 目標の強さ、TTK、DPS | `60_戦闘バランス設計書` |
| status / tag の ID と表示名 | `75.shared.status` / `76.shared.tag` の共有カタログ |
| 新しい題材・名称の選び方 | `00_docs/50_Filebase設計書/モチーフ選定ガイド.md` |

## 4. 現在のゲーム設計の要点

### 4.1 スキルの役割

- Skill はプレイヤーまたは Mob が実行する能動・受動能力です。
- 1 スキルにつき、主目的を原則1つにします。主目的は攻撃、防御、移動、回復、補助のいずれかです。
- class や equipment の弱点を、無条件にすべて消すスキルは避けます。強い効果には、射程、対象数、発生までの時間、位置取り、コスト、クールダウン、詠唱、命中条件、再使用の隙などの交換条件を置きます。
- スキルの説明は、プレイヤーが判断するために必要な効果値・対象・時間・制約を含めます。`description` / `lore` と実装の効果がずれないようにします。

### 4.2 職業の初期コンセプト

| 職業 | 戦闘コンセプト | 主リソース | 想定される当たり判定 |
|:--|:--|:--|:--|
| `adventurer` | 基礎を学びながら進路を決める見習い冒険者 | 実際の class YAML を確認 | 既存スキルと class YAML を確認 |
| `swordsman` | 間合いを奪い、攻撃を受けて前線を固定する守護剣 | `ENERGY` | 扇形、直線、周囲、突進経路 |
| `hunter` | 射線と位置取りで狩場を組み立てる射手 | `ENERGY` | 飛翔体、複数射線、設置罠、地面指定 |
| `mage` | 元素ごとの形と時間差を使い分ける陣形魔術師 | `MANA` | 飛翔体、連鎖、周囲、地面指定 |

職業名だけで役割を決めつけず、提案時は対応する class YAML と既存スキルを読みます。特に `usableSkills` は使用許可の一覧であり、ジェムを使った習得済み個体そのものを作る項目ではありません。

### 4.3 所持・使用許可・発動経路

- スキルジェムから UUID を持つ習得済みスキル個体を作ります。
- 現在の class と、有効なスキルツリーノードは「使用許可」を与えます。許可だけでは習得・発動できません。
- 習得済み個体は、使用許可を一時的に失っても保持されます。
- 装備、ルーン、セット効果からスキルを付与する設計にはしません。
- 通常攻撃は武器から解決される別経路であり、スキルと同一視しません。
- プレイヤーの発動スキルは、通常はアクションリングから選択して発動します。左クリックへ割り当てる場合も、コスト・クールダウンが自動で軽くなるわけではありません。

### 4.4 スキル定義の実装境界

プレイヤー向けのコード定義スキルでは、次の原則を守ります。

- `id`、`implementationId`、executor の登録キーは同じ lowercase snake_case の ID にします。
- 職業発動スキルの ID は、原則として `swordsman_`、`hunter_`、`mage_` などの職業 prefix を付けます。
- リソース種別・消費量・クールダウン・詠唱時間・必要レベルは、共通の top-level 項目を使います。
- `resourceType` は `MANA` または `ENERGY`、時間は tick で表します。20 tick が約1秒です。
- `params` は executor 固有の拡張値だけに使います。既存実装で読まれない任意の `params` を考案しても、効果は発生しません。
- `maxLevel` と `levels[]` は、前レベルからの差分として設計します。共有 status 補正を使う場合は、既知の status ID だけを使用します。
- 自動生成ジェムには、空でない `gem.rarity` を設定します。ジェムはレベルを持たず、習得時に Lv.1 の個体を作ります。
- `tags` は共有タグカタログに存在し、かつ `SKILL` に適用できる ID だけを使います。タグを追加する案は、既存タグで表現できない理由とカタログ変更範囲を記載します。

スキルの種別はタグ名だけから断定しません。`SkillExecutor.kind()` と、`passive.bindRequired` を含む schema・実装を確認します。

## 5. 既存スキルとの重複を避ける方法

提案前に、`40_filebase/30.features.skill/` 配下の `v1.*.yml` を全件読みます。次の一覧は検索開始点であり、確定した最新一覧の代わりにはなりません。

### 現在の主なプレイヤー向け ID（この資料作成時点の develop）

| 系統 | 既存 ID |
|:--|:--|
| 冒険者 | `adventurer_astral_edge`, `adventurer_smash` |
| ソードマン | `swordsman_crescent_slash`, `swordsman_piercing_thrust`, `swordsman_whirlwind`, `swordsman_vanguard_rush`, `swordsman_blade_wave`, `swordsman_fortress_guard`, `swordsman_war_cry`, `swordsman_earthbreaker` |
| ハンター | `hunter_power_shot`, `hunter_piercing_arrow`, `hunter_fan_shot`, `hunter_rapid_fire`, `hunter_backstep_shot`, `hunter_snare_trap`, `hunter_arrow_rain`, `hunter_ricochet` |
| メイジ | `mage_fireball`, `mage_frost_nova`, `mage_chain_lightning`, `mage_arcane_lance`, `mage_meteor`, `mage_blink`, `mage_mana_barrier`, `mage_elemental_storm` |
| 共通・管理用を含む既存スキル | `fire_boost`, `iron_will` |
| Mob 専用 | `mob_field_heavy_slash`, `mob_glowlamp_bolt`, `mob_goblin_slash`, `mob_mountain_thunder_bolt`, `mob_savanna_heatwave_bolt`, `mob_skeleton_bow_shot` |

既存スキルとの差分は名前だけでなく、次の観点で確認します。

- 主目的：単体火力、範囲火力、防御、移動、拘束、回復、敵視、設置など。
- 操作：即時、詠唱、遅延、wave、連射、再照準、地面指定、対象追従など。
- 当たり判定：扇形、直線、周囲、飛翔体、連鎖、罠、着弾範囲、突進経路など。
- 戦闘距離と位置取り：接近を要求するか、安全距離を維持するか、危険地帯へ入るか。
- 対象数と命中ルール：単体、最大対象数、貫通、跳弾、同一対象への多段、重複命中の扱い。
- 効果の時間軸：発動時、命中時、一定時間後、持続中、再使用可能時など。
- 交換条件：コスト、クールダウン、詠唱、射程、視線、遮蔽、足場、発生位置、失う通常攻撃回数。

既存スキルの小さな数値違いだけを新スキルとして提案せず、「プレイヤーが新しく行う判断」を1文で説明できる案を優先します。

## 6. 戦闘バランスの初期基準

数値は最終決定値ではなく、同格戦を試算するための初期目安です。具体的な倍率を置くときは、必ず `01-戦闘計算.md` の計算順序と、対象レベルの `R` / `D` / HP を確認します。

### 標準戦の基準

- 通常敵の TTK：5〜7秒。
- プレイヤーの耐久時間：8〜10秒。
- プレイヤーの通常攻撃間隔：約1.0秒。
- 標準的な Mob の攻撃間隔：約1.5秒。
- 通常攻撃を続けながら攻撃スキルを2〜3個使う標準実戦で、スキル込み DPS は通常攻撃期待 DPS の約1.5〜1.8倍を目標にします。初期基準は約1.6倍です。
- 標準実戦 DPS の構成比は、通常攻撃55〜65%、攻撃スキル合計35〜45%を目安にします。
- アクションリングの操作時間、詠唱、失った通常攻撃回数、命中率、クールダウン、リソース消費を含めて評価します。

### 攻撃スキルの倍率目安

- アクションリングから選ぶ単体攻撃：通常攻撃との操作差を考慮し、初期目安は150〜180%。
- 左クリックへ割り当てる連打系：初期目安は120〜140%。
- クールダウンが長い大技：初期目安は220〜300%。

範囲追加、拘束、防御、移動、回復、敵視などの utility は、攻撃倍率だけで強さを決めません。安全性、対象数、効果時間、位置取り、再使用間隔、パーティーへの寄与を別に評価します。数値目安から外れる場合は、外れる理由を明記します。

## 7. スキル案に必ず含める項目

ユーザーが指定していない項目は、勝手に確定せず「仮置き」と明示します。最低限、次を埋めます。

| 項目 | 記載内容 |
|:--|:--|
| 対象職業・段階 | class、想定 progression、既存スキルとの差分 |
| ID 候補 | 職業 prefix + lowercase snake_case。重複確認前は「候補」と明記 |
| 日本語名・モチーフ | 題材、視覚的な核、既存名称との重複確認 |
| 主目的 | 攻撃・防御・移動・回復・補助のうち1つ |
| プレイ判断 | いつ、どの位置で、何を見て発動するか |
| 発動フロー | 発動開始、詠唱、対象選択、命中、遅延、終了までの順序 |
| 対象・形状 | 単体、扇形、直線、周囲、飛翔体、地面指定、罠など |
| 効果 | ダメージ、属性、状態異常、移動、防御、回復、敵視など |
| リソース | `MANA` / `ENERGY`、消費量の仮置き根拠 |
| クールダウン・詠唱 | tick と秒を併記し、再使用の隙を説明 |
| 成長 | `maxLevel`、レベルごとの差分、強くなる軸 |
| 交換条件 | 使えない状況、危険、コスト、対象制限、既存弱点との関係 |
| 入手・許可 | ジェム、class、skilltree、progression。所持と使用許可を分ける |
| tags / status | 既存カタログの ID。新規追加が必要なら別途明記 |
| 実装影響 | 共通サービス再利用、新規 executor、既存 executor 拡張、テスト観点 |
| バランス確認 | TTK、実戦 DPS、耐久時間、操作時間への影響 |

## 8. ChatGPT の推奨出力形式

ユーザーが単に「スキル案を考えて」と依頼した場合は、次の構成で回答します。

```markdown
## 前提と参照範囲
- 対象職業、想定レベル・progression、対象ブランチ
- 読んだ正本のリポジトリ相対パス
- 仮定した条件と、まだ確認できない事項

## 既存スキルとの差分
| 既存スキル | 近い点 | 今回の案が追加する判断 |

## スキル案
### 案1：<日本語名>
| 項目 | 内容 |
|:--|:--|
| ID候補 | `<class>_<snake_case>` |
| 主目的 | ... |
| プレイ判断 | ... |
| 対象・形状 | ... |
| 効果 | ... |
| コスト / CD / 詠唱 | ... |
| 成長 | ... |
| 交換条件 | ... |
| 入手・progression | ... |
| 実装影響 | ... |

### 実戦の流れ
1. ...
2. ...

### バランス確認
- TTK / DPS / 耐久時間への影響
- 操作時間、失う通常攻撃、命中回数、リソースの確認

## 未決事項
- 設計者が決める必要のある点
```

複数案を出す場合も、各案に別々の主目的とプレイ判断を持たせます。案の数を増やすより、既存案との違いと採用理由を説明することを優先します。

### YAML 草案を求められた場合

ユーザーが実装向けの YAML 草案を求めた場合だけ、`40_filebase/30.features.skill/docs.skill.YAMLスキーマ定義.md` に従って YAML を出します。草案は確定マスターではないため、次を分けて表示します。

1. ゲームデザイン案。
2. schema に適合させた YAML 草案。
3. 新しい executor、共有サービス、class / skilltree / shop、テストが必要かどうか。
4. まだ実装・検証していない点。

実装が存在しない `params`、存在しない status / tag、未登録の `implementationId` を、動く機能として記載しません。新しいコードが必要な場合は、YAML だけでは動かないことを明記します。

## 9. プロジェクト指示へ追加する推奨文

ChatGPT Project の指示に、次のような文章を追加するとこの案内を使いやすくなります。

```text
あなたは AstralRecord のゲームデザイン補助です。
スキル案を考えるときは、必ず https://github.com/maaasu/AstralRecord-Workspace/tree/develop を起点にし、master ではなく develop ブランチを参照してください。
99_work/chatgpt-skill-idea-guide.md の読み順・正本の優先順位・出力形式に従い、既存の 40_filebase/30.features.skill/*.yml を確認してから案を作成してください。
確定仕様、今回の提案、未決事項を分けて書き、存在しない executor・status・tag・YAML 項目を実装済みのように扱わないでください。
数値は 00_docs/60_戦闘バランス設計書 を基準に試算し、操作時間・クールダウン・リソース・命中条件・交換条件を含めて説明してください。
参照できないファイルがある場合は推測で補わず、参照できない範囲を明記してください。
```

## 10. 資料の更新ルール

- スキルの追加・削除、class の変更、戦闘バランスの変更があったら、既存スキル一覧と参照先の説明を見直します。
- このファイルの一覧は検索補助です。最新状態の確認は常に GitHub `develop` の実ファイルを優先します。
- GitHub に push されていないローカル変更を、ChatGPT が参照できる前提で書きません。
- `99_work` 配下の作業メモは、正本として指定されていない限り補助資料です。確定仕様の判断には設計書、schema、filebase、Plugin 実装を使います。
