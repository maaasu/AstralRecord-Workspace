# 戦闘モデルの照合境界

`BalanceCombat.Simulate` はプレイヤー1人対Mob1体を基本に、明示した味方状態を支援・被保護対象として加える戦闘を20 tick/秒で実行する。モデル識別子は`astral-balance/3.0.0`。入力は`catalog`, `request`, `build`, `seed`で、複数試行と統計集約は呼び出し側が行う。結果の`damageDealt`と`contributions[].damage`はHPまたは盾へ実際に与えた量、`damageTaken`は自分が受けたHPダメージ、`resources[].tick`はゲームtickである。乱数は指定seedで再現するが、Pluginの`ThreadLocalRandom`とは同じ列にならない。

位置・視線、tick入力、毎tickの速度リプレイ、味方のJSON契約とパッシブ・専用資源は[戦闘runtime](../../docs/BALANCE_RUNTIME.md)、基本操作は[統合バランスラボ](../../docs/BALANCE_LAB.md)を参照。ここでは実Pluginコードと照合した範囲を示す。

## 実行と照合

固定値は `CombatGoldenHarness.java` が**実際のPluginクラス**を呼び出して出力した値。`combat-golden.json` は戦闘13ケースと経済7ケースの入力・期待値、依存Pluginソース29ファイルのSHA-256を保持する。戦闘の23ファイルはC#の `BalanceCombatParity` がカタログの `sources[{path,hash}]` と比較する。不一致・欠落があれば `strict` は `unsupported`、`approximate` は警告付きで実行する。追加した経済6ファイルの照合は経済モデル側で行う。指紋一致はソース版の確認であり、戦闘・経済の全体結果の数値一致を証明するものではない。照合元ソースを変更したら、Javaハーネス・C#テストを再実行してから固定値と指紋を更新する。

固定ケースの実呼び出し範囲は次の通り。

| Plugin実クラス | 固定ケース |
| --- | --- |
| `DamageCalculator` | 基本近接命中、会心、ミスの3件 |
| `CombatTimingCalculator` | CD/攻撃間隔、複合詠唱短縮の積と50%上限の3件 |
| `NormalAttackDegradationService` | 発数と遅延、最大段階、10秒経過後のリセットの3件 |
| `EquipmentStatContributionCalculator` | 強化・エンチャント・ルーンFLATと最後のSCALAR、負値レンジの2件 |
| `MobRepository` / `MobTemplate` / `MobShieldConfig` | レベルプロファイル継承・未定義Lvフォールバック、盾の有効/再充填条件と値の2件 |
| `MobDropService.calculateEffectiveRate` | 直接ドロップ率80%への幸運10%適用・非適用の1件 |
| `LevelDifferenceCalculator` / `StatusRateCalculator` | レベル差による経験値減衰と経験値獲得率125%、端数0.5の丸めの2件 |
| `OrbLocalMutationCalculator` | 強化成功、失敗時`NONE`/`DECREASE_ONE`/`SET_LEVEL`の4件 |

`CombatGoldenHarness`単体は`StatusRegenTask`、戦闘AI、スキルexecutorの空間判定・詠唱中断・実際のBukkit tick、盾ダメージ経路、1戦闘全体の勝敗を呼んでいない。経済側もドロップ候補の抽選全体、強化の0%と100%以外の乱数判定、Orbの条件・費用・消費を照合していない。ラボ側に対応実装やC#テストがあっても、このJava固定ケースの照合範囲には含めない。

```powershell
& '60_tool/skilltree-editor/tests/plugin-parity/run-parity.ps1' `
  -JavaHome 'C:\path\to\jdk-21' -MavenHome 'C:\path\to\apache-maven-3.9.12'
dotnet test 60_tool/skilltree-editor/tests/SkillTreeEditor.Server.Tests/SkillTreeEditor.Server.Tests.csproj `
  --filter FullyQualifiedName~BalanceCombatTests
```

JavaハーネスのMockitoは固定ケースのテスト補助のみ。ラボ稼働時にはJava、Mockito、Bukkitサーバーを起動しない。

## 計算の根拠と範囲

`DamageCalculator.java:169-263,305-306,323-364,389-466,509-567` の順に、命中、攻撃力 `(ATTACK+属性別攻撃力)×(1+主能力/100)`、攻撃倍率、会心、貫通後防御の減衰、属性/無属性耐性を適用する。防御倍率は `max(0,1-(防御/攻撃力)/2.5)^(log(0.5)/log(0.6))`。`CombatTimingCalculator.java` の切り上げによるクールダウン・攻撃間隔、`StatusService.java:1149-1275,1352-1457` の基礎/職業/装備/主能力由来値、`StatusRegenTask.java` の20 tickごとの自然回復、`EquipmentStatContributionCalculator.java` のFLAT/SCALAR、`EquipmentRequirementService.java` の装備条件を移植した。

武器の通常攻撃間隔は`ItemWeaponAttackService.java`のタグ別基礎tickを使う。動的経路では`SWORD`・`HAMMER`の近接判定、`SPEAR`の多段、`BOW`・`SHORTBOW`・`LONGBOW`の投射、`WAND`・`STAFF`の追尾を扱う。静的経路には従来の武器・距離制限が残る。Mobは`MobCombatService.java`の通常攻撃間隔・攻撃種別を使う。盾は`DamageService`のHPダメージ遮断と盾減少を計算し、プレイヤー側は装着パッシブに応じた有効化・再充填・一時盾を扱う。Mobの再充填盾は厳密モードで停止する。

スキルはマスターから自動取得し、実装IDで静的効果計画またはruntimeアダプターを解決する。現在の対応理由・必要条件は[スキル対応表](../../docs/BALANCE_SKILLS.md)と`catalog.skillCatalog`を参照。プロフィールの認識と、特定ビルド・入力での実行可能性を区別する。3.0は多段攻撃、複合属性、投射、状態、時限buff/魔法陣、回復に加え、反撃・回避、剣気・GUARD・呪印、継承、パッシブ、味方支援等を扱う。アクティブは`skillIds`の優先順で使い、パッシブは`passiveSkillIds`でbindする。即時効果・共通消費・CD開始、費用を伴う失敗、対象なしの成否は各実処理に沿って区別する。遅延効果は発動時の能力値と、アダプターごとの固定位置・命中時状態・再検索条件を使う。

スキルはLv1・シジル未装着・習得/装着/使用許可済みとして扱う。レベル差分・シジル指定、未知の追加パラメーター、未検証のルート効果・実装IDを検出する。`bow`・`velocity_replay`・`party`等の必要条件も検査する。Astral Edgeは`static_only`で、world入力やパッシブを伴う動的な厳密実行では停止する。敵は1体のため、別の敵に対する副対象・連鎖は評価できない。付与スキル付き装備、Mobスキル、Mob再充填盾、個体値範囲未確定、超越による能力値上書き等は厳密モードで停止し、近似モードでは省略理由を残す。超越の名称・強化上限のみの上書きは、指定済み強化レベルの能力値計算に影響しないため許容する。

`world`・`inputs`・`allies`は要求の最上位へ指定する。世界は遮蔽なしの水平床で、ダッシュ等の位置更新・投射のモデル内弾道と、Bukkitのエンティティ速度積分は別。速度命令の次tickから毎tickの対象座標が必要で、終了tickにも座標と`motionEnd`を併記する。敵AI、実地形、全サーバー物理を再現しない。味方は入力した派生済み能力値と資源を使い、自律的な攻撃はしない。

`SkillExecutorGoldenHarness.java`は実executorを仮想tickで動かし、FlameRush、BladeTempest、CrescentDuetの命中tick・倍率・属性を取得する。空間検索・演出・ダメージサービスはMockitoで固定するため、空間判定や全戦闘の実測照合ではない。`skill-executor-golden.json`は3ケース・依存15ソース指紋を保持する。`SkillMechanicsGoldenHarness.java`は実`DamageCalculator`の火氷成分、`ConditionService`の付与率、`ConditionTickService`の炎上周期、`BuffService`のSCALAR、`SkillTargetingService`の箱交差を呼び、`skill-mechanics-golden.json`に5ケース・12ソース指紋を保持する。C#テストはこれらの期待値を参照する。乱数の固定値照合と統計分布検査を区別する。

### 3.0で追加したruntime照合

| ハーネス / fixture | 件数 | 実Pluginコードを呼ぶ範囲 |
|---|---:|---|
| `MeleeRuntimeGoldenHarness.java` / `melee-runtime-golden.json` | 7 | GUARD獲得・減衰境界、静寂一閃の剣気bind・獲得・消費 |
| `RangedRuntimeGoldenHarness.java` / `ranged-runtime-golden.json` | 4 | Phantom Archer呪印の保持・失効・消費と条件倍率、Fade Shot散弾方向、Arrow Rain弾道初速・経路長 |
| `MagicRuntimeGoldenHarness.java` / `magic-runtime-golden.json` | 6 | Prism交差、Blizzard渦速度、Sparking螺旋、Emulate Spark床反射、Arcane Flow履歴、Meditation倍率境界 |

追加17ケースと既存28ケース（20+3+5）は、異なる限定経路を照合する固定ケースである。runtimeのprivateメソッドには反射を使い、境界を試すため状態を注入するケースもある。反撃ダメージ全体、全パーティ分岐、実飛翔体のサーバー衝突、実地形、瞑想の全操作列をまとめて照合したものではない。[各ハーネスの詳細な境界](../../docs/BALANCE_RUNTIME.md#javaハーネスが照合する範囲)を参照。

スキル/状態/buff等の282ソース指紋はアダプターと共通依存の変更検出用であり、282ファイルの全動作を照合したという意味ではない。3.0統合時点のbackendテスト568件通過も、全120マスターの実executorで`cast`を通した一括照合ではない。全120件の実cast通し、全組合せのPlugin戦闘、実サーバー・実DB・実プレイヤーによる実測は未検証である。カタログの認識件数・条件別実行件数は統合検証時に確認する。

```powershell
& '60_tool/skilltree-editor/tests/plugin-parity/run-skill-parity.ps1' `
  -JavaHome 'C:\path\to\jdk-21' -MavenHome 'C:\path\to\maven' -Check
& '60_tool/skilltree-editor/tests/plugin-parity/run-melee-runtime-parity.ps1' `
  -JavaHome 'C:\path\to\jdk-21' -MavenHome 'C:\path\to\maven' -Check
& '60_tool/skilltree-editor/tests/plugin-parity/run-ranged-runtime-parity.ps1' `
  -JavaHome 'C:\path\to\jdk-21' -MavenHome 'C:\path\to\maven' -Check
& '60_tool/skilltree-editor/tests/plugin-parity/run-magic-runtime-parity.ps1' `
  -JavaHome 'C:\path\to\jdk-21' -MavenHome 'C:\path\to\maven' -Check
dotnet test 60_tool/skilltree-editor/tests/SkillTreeEditor.Server.Tests --filter FullyQualifiedName~BalanceSkill
```

実在マスタの最小例は `adventurer`（`40_filebase/20.features.class/v1.0.adventurer.yml`）、`20a00001`（`40_filebase/10.features.item/20.equipment/v1.20a00001.simple_weapon.yml`）、`eriva_grassboar` Lv15（`40_filebase/40.features.mob/enemy/v1.eriva_grassboar.yml`）。`build.equipment[]` は `itemId`, `enhanceLevel`, 固定個体値 `statRolls:[{status,min,max}]`, `enchants:[{status,type,value}]`, `runes:[itemId|{itemId}]`, `transcendRank` を受け取る。マスタ上の乱数範囲を個体値なしで使う場合、厳密モードは停止する。`build.baseStats` は派生能力値の計算前に基礎値を上書きする。

`strict`は対応条件と指紋を検査した**限定モデル**の実行を意味する。入力した位置・操作を使っても、実サーバーの操作・tick揺れ・乱数列・全パッシブ連動・ボス/ダンジョン環境まで一致するものではない。結果の`assumptions`と`diagnostics`を確認する。`approximate`でも、不正入力や欠けた必須軌跡を物理計算で補完しない。

追加効果ではアダプターと既知パラメーター・必要条件を宣言し、Java oracleとC#回帰を追加した上で、fixtureと`BalanceSkillSourceParity`等の追跡指紋、カタログmetadata、対応表・モデル版を更新する。**指紋だけを更新して照合失敗を消さない。** 詳細は[runtime拡張手順](../../docs/BALANCE_RUNTIME.md#追加効果変更時の手順)を参照。
