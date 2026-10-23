# 戦闘モデルの照合境界

`BalanceCombat.Simulate` は1人対1体・静止・射程内の戦闘を20 tick/秒で実行する。入力は `catalog`, `request`, `build`, `seed`。複数試行と統計集約は呼び出し側が行う。結果の `damageDealt` と `contributions[].damage` はHPまたは盾へ実際に与えた量、`damageTaken` は受けたHPダメージ、`resources[].tick` はゲームtickである。乱数は指定seedで再現するが、Pluginの `ThreadLocalRandom` とは同じ列にならない。

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

Javaハーネスは `StatusRegenTask`、戦闘AI、スキルexecutorの空間判定・詠唱中断・実際のBukkit tick、盾ダメージ経路、1戦闘全体の勝敗を呼んでいない。経済側もドロップ候補の抽選全体、強化の0%と100%以外の乱数判定、Orbの条件・費用・消費を照合していない。これらは別のモデル前提または未対応境界であり、固定ケースの照合済み範囲には含めない。

```powershell
& '60_tool/skilltree-editor/tests/plugin-parity/run-parity.ps1' `
  -JavaHome 'C:\path\to\jdk-21' -MavenHome 'C:\path\to\apache-maven-3.9.12'
dotnet test 60_tool/skilltree-editor/tests/SkillTreeEditor.Server.Tests/SkillTreeEditor.Server.Tests.csproj `
  --filter FullyQualifiedName~BalanceCombatTests
```

JavaハーネスのMockitoは固定ケースのテスト補助のみ。ラボ稼働時にはJava、Mockito、Bukkitサーバーを起動しない。

## 計算の根拠と範囲

`DamageCalculator.java:169-263,305-306,323-364,389-466,509-567` の順に、命中、攻撃力 `(ATTACK+属性別攻撃力)×(1+主能力/100)`、攻撃倍率、会心、貫通後防御の減衰、属性/無属性耐性を適用する。防御倍率は `max(0,1-(防御/攻撃力)/2.5)^(log(0.5)/log(0.6))`。`CombatTimingCalculator.java` の切り上げによるクールダウン・攻撃間隔、`StatusService.java:1149-1275,1352-1457` の基礎/職業/装備/主能力由来値、`StatusRegenTask.java` の20 tickごとの自然回復、`EquipmentStatContributionCalculator.java` のFLAT/SCALAR、`EquipmentRequirementService.java` の装備条件を移植した。

武器の通常攻撃間隔は `ItemWeaponAttackService.java` のタグ別基礎tickを使う。単発近接武器 `SWORD` と `HAMMER` はモデル範囲内。`SPEAR`, `BOW`, `SHORTBOW`, `LONGBOW`, `WAND`, `STAFF` の多段・飛翔体挙動は厳密モードで停止する。Mobは `MobCombatService.java` の通常攻撃間隔・攻撃種別を使う。盾は `DamageService.java:1709-1804` のHPダメージ遮断と盾減少を計算する。再充填は停止する。

対応する実在スキルは `adventurer_smash`（近接主対象、詠唱後8 tick遅延）、`adventurer_quick_shot`（遠隔1発）、`adventurer_mana_burst`（魔法1発）、`wizard_self_heal`（自己回復）。スキルは記載順で使用し、成功時にMP/ENGを消費しクールダウンを開始する。単体戦闘のため、扇形/範囲攻撃の副対象、弾道遮蔽、ノックバックと移動、詠唱中断、継承などの周辺効果は計算しない。`skillIds` はLv1・習得/装着/使用許可済みとして扱う。その他executor、指定されたパッシブ・バフ・状態異常・追撃、付与スキル付き装備、Mobスキル、再充填盾、個体値範囲未確定、超越による能力値上書きは厳密モードで停止し、近似モードでは理由を `diagnostics` と `assumptions` に示す。超越の名称・強化上限のみの上書きは、指定済み強化レベルの能力値計算に影響しないため許容する。

実在マスタの最小例は `adventurer`（`40_filebase/20.features.class/v1.0.adventurer.yml`）、`20a00001`（`40_filebase/10.features.item/20.equipment/v1.20a00001.simple_weapon.yml`）、`eriva_grassboar` Lv15（`40_filebase/40.features.mob/enemy/v1.eriva_grassboar.yml`）。`build.equipment[]` は `itemId`, `enhanceLevel`, 固定個体値 `statRolls:[{status,min,max}]`, `enchants:[{status,type,value}]`, `runes:[itemId|{itemId}]`, `transcendRank` を受け取る。マスタ上の乱数範囲を個体値なしで使う場合、厳密モードは停止する。`build.baseStats` は派生能力値の計算前に基礎値を上書きする。

`strict` が保証するのは**対応済みの限定モデル**と指紋が一致すること。ターゲット位置、プレイヤー操作、サーバーtickの揺れ、Pluginの乱数列、パッシブ・buff、ボス/ダンジョン環境まで一致するという意味ではない。これらは結果の `assumptions` に明示される。
