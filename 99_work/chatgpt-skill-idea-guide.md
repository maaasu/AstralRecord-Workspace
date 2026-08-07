# ChatGPT用：AstralRecordのスキル案作成ガイド

このファイルは、Minecraft MMO RPG「AstralRecord」のスキル案を ChatGPT で考え、Codex の `$astralrecord-skill-author` へそのまま渡すための参照案内です。
ChatGPT Project にこのファイルを追加し、必要に応じて下記の GitHub `develop` ブランチを参照させてください。

このファイル自体は、個別スキルの確定仕様ではありません。スキルの現在仕様、利用可能な項目、実装可能な挙動、数値の基準は、リンク先の正本を読み直して判断します。

重要な役割分担は、**ChatGPT はゲームデザインの案を作り、Codex の `$astralrecord-skill-author` が正確な仕様と実装を確定する**ことです。

## 1. リポジトリと参照ブランチ

| 項目 | URL・説明 |
|:--|:--|
| 公開リポジトリ | [maaasu/AstralRecord-Workspace](https://github.com/maaasu/AstralRecord-Workspace) |
| RPG 資料の入口 | [develop ブランチ](https://github.com/maaasu/AstralRecord-Workspace/tree/develop) |
| この案内ファイル | [99_work/chatgpt-skill-idea-guide.md](https://github.com/maaasu/AstralRecord-Workspace/blob/develop/99_work/chatgpt-skill-idea-guide.md) |

この資料作成時点では、公開リポジトリの `master` は初期コミットだけで、現在の RPG の設計書・実装・filebase は `develop` にあります。スキル案を考えるときは、リポジトリのトップページから自動で選ばれるブランチに任せず、必ず `/tree/develop/` または `/blob/develop/` の URL を開いてください。

また、ローカルの `develop` にだけ存在する未 push の変更は、GitHub を参照する ChatGPT からは見えません。GitHub 上の `develop` とローカルの内容が異なる場合は、ChatGPT は GitHub 側で確認できた内容だけを「確定済み」として扱い、差分が必要ならユーザーへ確認します。

## 2. ChatGPT と Codex の役割分担

### 2.1 ChatGPT が決めること

ChatGPT は、プレイヤーがどのような判断をするスキルなのかを明確にします。

- 対象職業、スキル名の候補（表示名は日本語カタカナを基本）、モチーフ、主目的。
- プレイ中の判断、発動から命中・終了までの流れ。
- 対象、距離、形状、位置取り、命中させたい状況。
- 主効果・副効果の**概念**と、既存スキルとの差別化。
- 強さの方向性（例：高火力だが長い隙、低火力だが設置で有利）と交換条件。
- 主リソースの候補、入手経路、progression の方向性。
- 参考にしたい演出、音、粒子、既存スキル。

既存仕様を理解するために数値やコードを参照してよいですが、次の項目を ChatGPT の最終決定値として引き継ぎません。

- 正確なダメージ倍率、回復量、resource cost、cooldown、cast time。
- 命中回数、tick 間隔、最大対象数、射程・半径の最終値。
- 状態異常の正確な status ID、付与確率、持続時間、強度。
- `maxLevel`、`levels[]`、status modifier、sigil slot の最終値。
- YAML の `params`、`tags`、`gem.rarity`、`implementationId` の確定値。

これらは既存スキル、共有カタログ、schema、戦闘バランス、Plugin 実装を確認した Codex 側が確定します。ChatGPT が触れる場合は、必ず「仮置き」「方向性」「Codex 側で確定」と表示します。

### 2.2 Codex の `$astralrecord-skill-author` が決めること

Codex は ChatGPT の案を入力として、既存仕様と実装へ接続できる形に落とし込みます。

- 正確な数値、status / tag ID、レベル成長、progression。
- 対象選択、遮蔽、命中順、重複命中、無敵時間などの戦闘規則。
- 既存 executor / 共通サービスの再利用、新規 executor の要否。
- skill YAML、class / skilltree の使用許可、ジェム・ショップ・管理者公開。
- Plugin 実装、演出、メッセージ、設計書、テスト、DPS 検証。

ChatGPT の案に不足する詳細があっても、既存仕様から安全に決められる項目は Codex が提案・確定します。PvP / PvE の適用、対象優先順位、無敵時間、味方への影響など、ゲーム規則そのものが変わる未決事項だけは、Codex が実装前に質問します。

### 2.3 ChatGPT の作成手順

ChatGPT は、単に名前や派手な効果を並べるのではなく、次の順番でスキル案を作成します。

1. `develop` ブランチの正本を読み、ゲームの役割・既存スキル・実装可能な効果を確認する。
2. 既存の `skill` YAML を全件確認し、名前だけでなく、戦闘距離・当たり判定・属性・発動タイミング・コスト・クールダウン・状態異常・移動・防御効果まで比較する。
3. 対象職業に既にある役割を避け、明確に異なるプレイ判断を追加する。
4. 戦闘バランス設計書の TTK、耐久時間、実戦 DPS、操作時間を、最終値ではなく案の強さの方向性を考える基準として使う。
5. 既存の共通サービスで実現できそうか、新しい Plugin executor が必要そうかを「推定」として記載する。
6. 「リポジトリで確認できた事実」「ChatGPT の提案」「Codex が確定する詳細」「設計判断が必要な未確定事項」を明確に分ける。
7. 最終回答は、下記の `$astralrecord-skill-author` 引き継ぎブロックだけを出力する。説明文を付ける場合も、ブロックの外へ確定仕様を書かない。

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

### `$astralrecord-skill-author` へそのまま渡す出力形式

ChatGPT の通常回答は、説明付きの提案書ではなく、次の `text` ブロックをそのまま出力します。このブロックを Codex の依頼文へ貼り付ければ、`$astralrecord-skill-author` の Intake に渡せます。

```text
$astralrecord-skill-author を使って、<職業> に <スキル名> の案を実装可能な仕様へ確定し、必要な filebase・Plugin・設計書・テストを更新してください。

命名方針: <表示名は日本語カタカナを基本。効果や題材に自然なら漢字・ひらがなも可。skill ID / implementationId は Codex 側で既存規約に従って確定>

役割: <単体火力 / 範囲攻撃 / 回復 / 補助 / 移動 など。数値ではなく主目的>
発動・対象: <発動方法、敵/味方、自身、対象数の方向性、射程・角度・半径の方向性>
主効果: <ダメージ・回復・状態異常などの概念。正確な倍率、量、status ID は Codex 側で確定>
副効果: <範囲追加効果、ノックバック、持続など。正確な値は Codex 側で確定。なければ「なし」>
固定条件: <ユーザーが必ず守ってほしい条件。なければ「なし」>
数値方針: <強さの方向性、主リソース候補、短/中/長 CD、詠唱の有無。正確な対象数・射程・半径、EN/MP、CT、詠唱、倍率、status 値、レベル成長は「既存スキルを基準に提案可」とし、Codex 側で確定>
演出: <発動から着弾までの見た目・音。参考にしたい既存スキルやバニラ要素>
入手・利用: <初期習得 / ジェム / ショップ / 管理者。不要なら「既存方針」>
既存比較: <近い既存スキルと、今回の案が追加する新しいプレイ判断>
設計書: Codex 側で対象職業の戦闘バランス設計書と関連設計書を探索
Codex確定範囲: <正確な数値、status / tag ID、YAML、executor、実装、テスト、DPS 検証>
未決事項: <PvP/PvE、味方への影響、無敵時間、対象優先順位など、推測で決めてはいけない事項>
```

ChatGPT が複数案を出す場合は、このブロックを案ごとに分けます。ブロック内に確定 YAML、架空の status ID、架空の executor 名を含めません。ChatGPT が「既存スキルを基準に提案可」と判断した場合は、`数値方針` にその旨を書きます。

## 4. 現在のゲーム設計の要点

### 4.1 スキルの役割

- Skill はプレイヤーまたは Mob が実行する能動・受動能力です。
- 1 スキルにつき、主目的を原則1つにします。主目的は攻撃、防御、移動、回復、補助のいずれかです。
- class や equipment の弱点を、無条件にすべて消すスキルは避けます。強い効果には、射程、対象数、発生までの時間、位置取り、コスト、クールダウン、詠唱、命中条件、再使用の隙などの交換条件を置きます。
- スキルの説明は、プレイヤーが判断するために必要な効果値・対象・時間・制約を含めます。`description` / `lore` と実装の効果がずれないようにします。

### 4.1.1 スキル表示名の命名方針

- プレイヤーが見るスキル表示名は、日本語カタカナを基本にします。例：**「炎球」ではなく「ファイアボール」**。
- 漢字やひらがなの方が効果・題材・役割を自然に表せる場合は、無理にカタカナへ置き換えません。例：ヘイト率を上げるスキルなら **「四面楚歌」** のような漢字名を使えます。
- 既存スキルと読み・意味・演出が混同されないかを確認し、名前だけで効果を誤解させる案は避けます。
- ここでいう名前はプレイヤー向け表示名です。skill の `id`、`implementationId`、executor 登録キーは別物なので、既存の lowercase snake_case 規約に従って Codex 側で確定します。
- ChatGPT は候補名と命名理由を案として渡し、依頼者が表示名を固定指定した場合だけ固定条件として扱います。既存仕様と衝突する場合は Codex が確認します。

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
| ソードマン | 未定 |
| ハンター | 未定 |
| メイジ | 未定 |
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

この章は、Codex の `$astralrecord-skill-author` が最終数値を確定するときに使う基準です。ChatGPT はこの基準から「高火力だが長い隙」「低火力だが継続的に有利」などの方向性を作り、個別スキルの正確な値は引き渡しません。数値は最終決定値ではなく、同格戦を試算するための初期目安です。Codex が具体的な倍率を置くときは、必ず `01-戦闘計算.md` の計算順序と、対象レベルの `R` / `D` / HP を確認します。

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
| 効果 | ダメージ、属性、状態異常、移動、防御、回復、敵視などの概念。正確な値は Codex 側で確定 |
| リソース | `MANA` / `ENERGY` の候補、消費の方向性。正確な量は Codex 側で確定 |
| クールダウン・詠唱 | 短/中/長、即時/詠唱/遅延などの方向性。tick と最終値は Codex 側で確定 |
| 成長 | 強くなる軸（火力、範囲、持続、使いやすさなど）。`maxLevel` と差分は Codex 側で確定 |
| 交換条件 | 使えない状況、危険、コスト、対象制限、既存弱点との関係 |
| 入手・許可 | ジェム、class、skilltree、progression。所持と使用許可を分ける |
| tags / status | 効果の概念だけ。既存カタログからの ID 選定と値は Codex 側で確定 |
| 実装影響 | 共通サービス再利用か新規 executor が必要そうかの推定。確定は Codex 側 |
| バランス確認 | TTK、実戦 DPS、耐久時間、操作時間で確認すべき観点。最終試算は Codex 側 |
| Codex へ委譲する項目 | 正確な数値、status / tag ID、YAML、executor、実装、テスト、DPS 検証 |

## 8. ChatGPT の推奨出力形式

ユーザーが単に「スキル案を考えて」と依頼した場合も、回答は `$astralrecord-skill-author` へそのまま渡せる引き継ぎ文にします。通常は次の `text` ブロック以外の説明を付けません。

```text
$astralrecord-skill-author を使って、<職業> に <スキル名> の案を実装可能な仕様へ確定し、必要な filebase・Plugin・設計書・テストを更新してください。

命名方針: <表示名は日本語カタカナを基本。効果や題材に自然なら漢字・ひらがなも可。skill ID / implementationId は Codex 側で既存規約に従って確定>

役割: <主目的とプレイ上の役割。数値ではなく概念>
発動・対象: <発動方法、敵/味方、自身、対象数の方向性、射程・角度・半径の方向性>
主効果: <ダメージ・回復・状態異常などの概念。正確な倍率、量、status ID は Codex 側で確定>
副効果: <追加効果の概念。正確な値は Codex 側で確定。なければ「なし」>
固定条件: <ユーザーが必ず守ってほしい条件。なければ「なし」>
数値方針: <強さの方向性、主リソース候補、短/中/長 CD、詠唱の有無。正確な対象数・射程・半径、EN/MP、CT、詠唱、倍率、status 値、レベル成長は「既存スキルを基準に提案可」とし、Codex 側で確定>
演出: <発動から着弾までの見た目・音。参考にしたい既存スキルやバニラ要素>
入手・利用: <初期習得 / ジェム / ショップ / 管理者。不要なら「既存方針」>
既存比較: <近い既存スキルと、今回の案が追加する新しいプレイ判断>
設計書: Codex 側で対象職業の戦闘バランス設計書と関連設計書を探索
Codex確定範囲: <正確な数値、status / tag ID、YAML、executor、実装、テスト、DPS 検証>
未決事項: <推測で決めてはいけない PvP/PvE、味方への影響、無敵時間、対象優先順位など>
```

複数案を出す場合も、引き継ぎブロックを案ごとに分けます。案の数を増やすより、既存案との違いと採用理由を `既存比較` と `主効果` に短くまとめることを優先します。

### YAML や詳細数値を求められた場合

ChatGPT Project で YAML、正確な倍率、status ID、クールダウン、レベル別数値まで求められても、通常の引き継ぎブロックでは作成しません。そこは Codex の `$astralrecord-skill-author` に委譲します。

どうしても詳細の例を示す必要がある場合は、引き継ぎブロックとは別に「参考値・未検証」と明記し、Codex が再計算・再選定する前提を明示します。

存在しない `params`、status / tag、`implementationId` を実装済みのように書きません。

## 9. プロジェクト指示へ追加する推奨文

ChatGPT Project の指示に、次のような文章を追加するとこの案内を使いやすくなります。

```text
あなたは AstralRecord のゲームデザイン補助です。
スキル案を考えるときは、必ず https://github.com/maaasu/AstralRecord-Workspace/tree/develop を起点にし、master ではなく develop ブランチを参照してください。
99_work/chatgpt-skill-idea-guide.md の読み順・正本の優先順位・出力形式に従い、既存の 40_filebase/30.features.skill/*.yml を確認してから案を作成してください。
回答は `$astralrecord-skill-author` へそのまま貼り付けられる text ブロックだけにし、役割・プレイ判断・効果の概念・交換条件を引き渡してください。
正確な倍率、EN/MP、クールダウン、詠唱、status / tag ID、レベル成長、YAML、executor、実装、テスト、DPS は Codex 側で確定する項目として `Codex確定範囲` にまとめ、ChatGPT の確定値として出力しないでください。
数値の方向性を説明する場合も、00_docs/60_戦闘バランス設計書を基準にした相対表現とし、操作時間・命中条件・交換条件を記載してください。
存在しない executor・status・tag・YAML 項目を実装済みのように扱わないでください。
参照できないファイルがある場合は推測で補わず、参照できない範囲を明記してください。
```

## 10. 資料の更新ルール

- スキルの追加・削除、class の変更、戦闘バランスの変更があったら、既存スキル一覧と参照先の説明を見直します。
- このファイルの一覧は検索補助です。最新状態の確認は常に GitHub `develop` の実ファイルを優先します。
- GitHub に push されていないローカル変更を、ChatGPT が参照できる前提で書きません。
- `99_work` 配下の作業メモは、正本として指定されていない限り補助資料です。確定仕様の判断には設計書、schema、filebase、Plugin 実装を使います。
