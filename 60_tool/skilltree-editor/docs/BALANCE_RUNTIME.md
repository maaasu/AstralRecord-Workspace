# 戦闘runtimeの入力・対応条件・照合範囲

統合バランスラボの戦闘runtimeは、明示した位置・操作・味方状態を20 tick/秒で処理する限定モデルです。Pluginの実executorとruntime serviceを参照して、専用資源、パッシブ、飛翔体、範囲効果、遅延処理を計算します。対応判定は現在のマスターとアダプターから生成され、全マスターの取得件数と、条件付きで計算できる件数は別です。

画面の基本操作・API・計算量上限は[BALANCE_LAB.md](BALANCE_LAB.md)、スキルの対応判定は[BALANCE_SKILLS.md](BALANCE_SKILLS.md)を参照してください。この文書の入力契約は`BalanceRuntimeWorld`、発動・イベント接続は`BalanceCombat`と`BalanceCombatSimulation`、効果は各`Balance*Runtime`を正本にしています。

## 使い方

1. ラボで職業、装備、敵、アクティブスキルの優先順位を選びます。まず`strict`で実行します。
2. パッシブはビルドの`passiveSkillIds`へ指定します。アクティブの`skillIds`へ混ぜません。必要なパッシブを自動で装着する機能はありません。
3. 初期位置を`world`、tickごとの操作・座標を`inputs`、パーティ対象を`allies`へ指定します。これらは条件JSONの最上位で、`builds[]`の内側ではありません。複数ビルドを比較するときは同じ世界・入力列を使います。
4. `catalog.skillCatalog`の`runtime`、`kind`、`requirements`、対応理由と、実行結果の`diagnostics`、`assumptions`を確認します。選択できるマスターでも、そのビルドや入力で実行できるとは限りません。
5. 第1試行の`resources`、`skillTimeline`と攻撃別寄与を確認し、条件・結果JSONを保存します。位置、専用資源、味方HP等も資源トレースに含まれます。トレースは間引き・件数上限があるため、全tickのイベントログではありません。

スキルはLv1・シジルなし、習得・装着・使用許可済みとして扱います。アクティブは`builds[].skillIds`の順で自動選択され、`inputs`に任意のスキルIDを指定して発動する契約はありません。`builds[].normalAttacks=false`で自動通常攻撃を止められます。明示的な`normal_attack`入力は別の操作です。

runtimeアダプターを選ぶか、パッシブまたは`world`・`inputs`・`allies`を指定すると、動的位置判定を使います。旧来の静的効果計画もこの経路に混在できますが、形状・参照時点を特定できない効果には`static_only`が付き、動的な`strict`実行を停止します。

## JSONの配置

以下は`POST /api/balance-lab/jobs`へ渡す条件JSONに追加・統合する断片です。単独で実行する完全な要求ではありません。既存の`schemaVersion`、`seed`、`trials`、`maxSeconds`、`mode`、`builds`、敵マスター指定は維持してください。

```json
{
  "world": {
    "floorY": 0,
    "playerPosition": [0, 0, 0],
    "enemyPosition": [0, 0, 3],
    "facing": [0, 0, 1]
  },
  "inputs": [
    { "tick": 0, "sneaking": true, "onGround": true },
    { "tick": 20, "facing": [1, 0, 0], "sneaking": false },
    { "tick": 30, "enemyPosition": [2, 0, 0] }
  ],
  "allies": []
}
```

座標は足元位置、`facing`は視線方向の`[x,y,z]`です。単位はブロック、tickは0始まりの整数で、20 tickが1秒です。ベクトルは有限数3要素、各成分の絶対値は1,000,000以下を指定します。初期視線は0ベクトルにできません。

### world

| 項目 | 意味・省略時 |
|---|---|
| `floorY` | 障害物のない水平床の高さ。省略時0 |
| `playerPosition` | 自分の初期足元位置。省略時`[0,floorY,0]` |
| `enemyPosition` | 敵の初期足元位置。省略時`[0,floorY,enemy.distanceBlocks]`。距離の既定値は3 |
| `facing` | 初期視線。正規化して使用。省略時`[0,0,1]` |
| `velocityMode` | 速度変更が必要な場合に`"replay"`。物理積分モードはない |

敵は1体で、`enemy.targetHalfDepth`を水平当たり箱の半幅、`enemy.targetHeight`を高さとして使います。既定値はそれぞれ0.3、1.8です。位置を明示した動的処理では現在位置を判定に使います。`distanceBlocks`だけを書き換えても、明示した`world.enemyPosition`は移動しません。

### inputs

各要素は`tick`を必須とするオブジェクトです。入力はtickでまとめ、同じtickの要素は配列内の順に適用します。入力がないtickの位置・視線・スニーク・接地状態は、既存状態を引き継ぎます。ただし速度リプレイ中の対象位置は毎tick必須です。

| 項目 | 契約 |
|---|---|
| `tick` | 0以上、戦闘時間上限以内の整数 |
| `playerPosition`, `enemyPosition` | そのtickの足元位置を置換 |
| `facing` | そのtickの視線を置換し正規化 |
| `sneaking`, `onGround` | 真偽値。省略時は前の状態を維持 |
| `kind` | 省略可。`normal_attack` / `dodge` / `air_shift`のいずれか |
| `forward`, `backward`, `left`, `right` | 真偽値。その入力の方向フラグを参照するruntime用。座標を自動積分する指定ではない |
| `allyStates` | 初期`allies`に存在するIDの状態更新。下記参照 |
| `motionEnd` | 速度リプレイ終了対象の配列。要素は`"player"` / `"enemy"` |

`normal_attack`は通常攻撃の待ち時間と詠唱状態を検査します。入力を増やして攻撃間隔を無視することはできません。`dodge`は接地・生存・詠唱状態・ENG等の条件を満たした場合に発動し、速度変更を要求します。`air_shift`は対応するパッシブとruntime側の発動条件が必要です。入力が存在するだけで成功した操作にはなりません。

### 速度変更は毎tickの座標リプレイが必要

ノックバック、引き寄せ、回避等の速度命令を実行する場合、`world.velocityMode="replay"`と、命令の**次tickから終了まで、各tickの対象座標**が必要です。`SetVelocity`相当の直接置換と、耐性・受付間隔を伴う`AddVelocity`相当の命令はアダプターで区別しますが、その命令からMinecraftの摩擦・重力・衝突後の位置を生成しません。

以下はtick 0にプレイヤーへの速度命令が発生し、tick 3で移動を終了する例です。数値は説明用に明示した軌跡です。Pluginの物理結果から生成した値ではありません。

```json
{
  "world": {
    "floorY": 0,
    "playerPosition": [0, 0, 0],
    "enemyPosition": [0, 0, 3],
    "facing": [0, 0, 1],
    "velocityMode": "replay"
  },
  "inputs": [
    { "tick": 0, "kind": "dodge", "onGround": true },
    { "tick": 1, "playerPosition": [0, 0.15, 0.7] },
    { "tick": 2, "playerPosition": [0, 0.1, 1.2] },
    { "tick": 3, "playerPosition": [0, 0, 1.5], "motionEnd": ["player"] }
  ]
}
```

tick 2の座標が欠ければ、tick 3に座標があっても未対応として停止します。`motionEnd`を指定したtickにも対象座標が必要です。終了指定がなければ戦闘終了まで毎tick必要で、終了後に新たな速度命令が発生すると再び次tickから必要になります。敵にも速度命令が出る構成では`enemyPosition`を同様に渡し、両方が動くtickでは両方を指定します。入力列は宣言した軌跡の再生であり、計算結果に応じて軌跡を自動調整しません。

## alliesと単独・パーティ分岐

`allies`は同一世界のパーティメンバーとして扱う、最大8人の明示入力です。実アカウント、パーティサービス、DBから取得しません。自分は`builds[]`のビルドで表し、味方へ重複登録しないでください。味方にクラス・装備から能力値を組み立てる処理はなく、`stats`へ派生済み能力値を渡します。

```json
{
  "allies": [
    {
      "id": "00000000-0000-0000-0000-000000000101",
      "position": [0, 0, 2],
      "stats": {
        "MAX_HEALTH": 500,
        "MAX_MANA": 100,
        "MAX_ENERGY": 100,
        "MAX_SHIELD": 50,
        "DEFENSE": 30,
        "MELEE_DEFENSE": 10
      },
      "hp": 350,
      "mp": 40,
      "eng": 80,
      "shield": 20,
      "shieldEnabled": true
    }
  ],
  "inputs": [
    {
      "tick": 20,
      "allyStates": [
        {
          "id": "00000000-0000-0000-0000-000000000101",
          "position": [1, 0, 2]
        }
      ]
    }
  ]
}
```

`id`は重複しないUUID、`position`と`stats`は必須、`MAX_HEALTH`は正数です。`hp`・`mp`・`eng`・`shield`は0以上の有限数で、省略すると0です。HPを省略して最大HPから開始することはありません。`shieldEnabled`は盾回復を受ける資格の明示値です。必要な防御・耐性・回復関連能力値も入力してください。未指定の能力値を味方の装備から補いません。

`allyStates`は既存IDの`position`・`hp`・`mp`・`eng`・`shield`を置換します。能力値やパーティ加入者の追加指定ではありません。HP等を入力するとその時点までの計算結果も上書きするため、移動だけを再生する場合は`position`だけを指定します。時限補正は味方の基礎`stats`へ毎tick反映して再構築します。

敵の攻撃先を味方へ固定したい場合は、既存の`enemy`オブジェクトへ`targetAllyId`を追加します。値は`allies[].id`と一致させます。省略時は自分が対象です。これはシナリオ上の指定で、敵AIのヘイト・対象選択を再現するものではありません。

パーティを空にした場合と、味方がいても対象条件を満たさない場合は区別します。例えば次の分岐があります。

| 効果 | 入力と分岐 |
|---|---|
| Guardian Protect | `party`要件により`allies`が必要。視線・距離・生存条件に合う味方がいなければ発動失敗。保護中は味方の盾処理後のHPダメージを分配し、自分へ防御・盾を再適用しない |
| Shield | 範囲内の味方がいれば一時盾を付与し、既存盾を置換。対象がいない場合は自分側の聖柱攻撃へ分岐する |
| Holy Field / Holy Control | 自分・敵への処理に加えて、明示した範囲内の味方へ補正・回復を適用。Holy Controlは聖柱がない場合にも費用を伴う失敗経路がある |
| 回復・支援効果 | 味方の距離・HP・回復阻害等を確認。味方自身の通常攻撃・スキルローテーションは実行しない |

## パッシブと専用資源

`builds[].passiveSkillIds`は重複なしで32件以下です。アクティブ・パッシブのリスト取り違え、同じ実装IDの別名マスター同時選択は診断します。パッシブは戦闘開始時にbindし、その後のイベントで作用します。汎用`params.modifiers`の時限能力補正と、専用runtimeの状態処理を分けています。

| 状態 | 入力・観察の要点 |
|---|---|
| 剣気 `RIPOSTE` | 対応する静寂一閃パッシブを指定。スニーク中の剣通常攻撃・反撃等のイベントで獲得し、対応アクティブが消費。対象なしでも消費する経路がある |
| `GUARD` | Guard Convertパッシブを指定。防御前ダメージと実HPダメージから獲得し、上限・時間減衰・専用費用を処理。ENGと同じ軽減費用へ置換しない |
| 盾 | Shield Activate等のbind条件と、盾の再充填パッシブを区別。`initialShield`は実験用の初期値であり、パッシブを装着したことにはならない。パッシブ未指定時の既存初期盾互換条件と、明示装着時の盾有効化条件は異なる |
| 反撃・回避窓 | 通常攻撃の試行・スニーク・被弾・`dodge`イベントで開始・終了。無敵中のキャンセルと通常のHP0ダメージを区別 |
| 呪印・継承・連撃等 | 対応パッシブの有効化、命中・消費成功、継承元、残存時間、二次攻撃の再通知抑制等を各アダプターで処理 |
| Meditation等 | `sneaking`や発動・被弾などのイベントに依存。入力なしで常時有効な能力値へ置換しない |

専用資源は任意の初期値を注入するJSON契約を設けていません。`resources[].custom`でruntimeが記録した値を確認します。各試行で状態を新規作成し、死亡時の解除処理もアダプターが扱います。

## 空間と再現性の境界

扇形・直線・球・円柱・地面指定・投射命中を、実executorに応じて区別します。発動時の位置・視線・対象を固定する効果と、遅延tickに現在位置から対象を再検索する効果も区別します。着弾済みの投射へ、その後の自分の向きを使って扇形判定をかけ直しません。形状を確定できない旧計画は`BalanceStaticGeometry`の記述子で`static_only`にします。

世界は読み込み済みの障害物のない水平床です。投射のモデル内の速度・重力・追尾・床反射と、エンティティのBukkit速度積分は別です。地形、壁による遮蔽、段差、安全地探索の実ブロック判定、チャンク、流体、摩擦、実サーバーのエンティティ衝突を扱いません。ダッシュ・後退・瞬間移動等はこの水平床条件で位置を更新します。座標入力を渡しても地形対応の検証にはなりません。

敵は1体で、複数敵への副対象・連鎖・範囲上限による差は検証できません。味方は入力した対象状態で、戦闘AI・自律移動・プレイヤーの判断・ネットワーク・Paperの負荷によるtick揺れ・実際のイベントバス全体は対象外です。同tick処理順はこのエンジンの契約です。

同じマスター・ソース指紋・モデル版・入力・seedなら同じモデル結果を再実行できます。Pluginの`ThreadLocalRandom`と同じ乱数列ではありません。`strict`は対応条件・指紋の検査を通った限定モデルの実行を意味します。`approximate`は診断付き省略を許す経路ですが、不正入力や実行中に欠けた必須軌跡まで補完しません。

## Javaハーネスが照合する範囲

固定期待値の取得元は`tests/plugin-parity`です。JavaハーネスはPluginの実クラスを呼びますが、各ハーネスのモック・反射・初期状態の注入範囲を含む限定照合です。C#同士へ同じ式をコピーした自己照合とは区別します。

| ハーネス / fixture | 実呼び出しの対象と境界 |
|---|---|
| `CombatGoldenHarness.java` / `combat-golden.json` | 共通ダメージ、戦闘時間計算、通常攻撃劣化、装備寄与、Mob設定読込等の固定値。経済用のドロップ実効率・経験値・Orb変遷も含む。戦闘AIや1戦闘全体を実サーバーで実行するものではない |
| `SkillExecutorGoldenHarness.java` / `skill-executor-golden.json` | Flame Rush・Blade Tempest・Crescent Duetの実executorを仮想tickで実行し、命中tick・倍率・属性を取得。空間検索・演出・ダメージサービスは固定しており、実空間全体の照合ではない |
| `SkillMechanicsGoldenHarness.java` / `skill-mechanics-golden.json` | 実DamageCalculatorの複合属性、ConditionServiceの付与率、ConditionTickServiceの炎上周期、BuffServiceのSCALAR、SkillTargetingServiceの箱交差。全状態・buffの全経路ではない |
| `MeleeRuntimeGoldenHarness.java` / `melee-runtime-golden.json` | GUARD獲得計算・減衰境界と、静寂一閃の剣気bind・獲得・消費。runtimeの実メソッドを呼ぶ。反撃ダメージ、全盾経路、パーティ空間判定を一括実行するものではない |
| `RangedRuntimeGoldenHarness.java` / `ranged-runtime-golden.json` | Phantom Archerの呪印保持・失効・消費と条件倍率、Fade Shotの散弾方向、Arrow Rainの弾道初速・経路長。実飛翔体を実サーバーへ発射する照合ではない |
| `MagicRuntimeGoldenHarness.java` / `magic-runtime-golden.json` | Prism交差、Blizzardの渦速度、Sparking螺旋、Emulate Spark床反射、Arcane Flow履歴、Meditation倍率境界。反射経路には初期状態注入があり、実地形の連続衝突・瞑想の全操作列を検証するものではない |

対応する`run-parity.ps1`、`run-skill-parity.ps1`、`run-melee-runtime-parity.ps1`、`run-ranged-runtime-parity.ps1`、`run-magic-runtime-parity.ps1`を、JDK 21とMavenを指定して実行します。runtime用の例は次のとおりです。

```powershell
& ./tests/plugin-parity/run-melee-runtime-parity.ps1 `
  -JavaHome 'C:/path/to/jdk-21' -MavenHome 'C:/path/to/maven' -Check
& ./tests/plugin-parity/run-ranged-runtime-parity.ps1 `
  -JavaHome 'C:/path/to/jdk-21' -MavenHome 'C:/path/to/maven' -Check
& ./tests/plugin-parity/run-magic-runtime-parity.ps1 `
  -JavaHome 'C:/path/to/jdk-21' -MavenHome 'C:/path/to/maven' -Check
dotnet test ./tests/SkillTreeEditor.Server.Tests/SkillTreeEditor.Server.Tests.csproj
```

実行ディレクトリは`60_tool/skilltree-editor`です。Java/Mavenは照合時だけ必要で、ラボ稼働時にはJava・Mockito・Minecraftサーバーを起動しません。

マスターを読み込むC#回帰検査、個別runtimeテスト、指紋一致、上記Java固定ケースは、それぞれ確認対象が異なります。**全120マスターの実executorで`cast`を通す一括照合、全組合せの実Plugin戦闘、実サーバー・実DB・実プレイヤーによる検証は未実施です。** テスト件数やソース指紋数を、全スキル全経路の動作照合件数として数えません。最新の実施件数・結果は統合時の検証記録とハーネス出力を参照してください。

## 追加効果・変更時の手順

1. マスター読込、実executor、runtime service、イベント接続を追い、発動成否・費用・CD開始・遅延処理・死亡解除・空間と参照時点を特定します。対象なしで失敗するか、費用を伴う失敗かも確認します。
2. 静的効果なら`BalanceSkill*Profiles`、イベント・状態が必要なら`IBalanceSkillRuntime`を実装するアダプターへ追加します。`BalanceSkills.Resolve`へ接続し、既知パラメーター、`Sources`、前提、未対応理由、`Runtime`・`Passive`・`Requirements`を宣言します。マスターIDの手動列挙でカタログを補いません。
3. ダメージ・回復・状態・資源・能力補正・予約は`BalanceRuntimeContext`の共通操作へ委譲します。式や盾処理を各スキルへ複製せず、通常攻撃/スキル/二次攻撃の通知・キャンセル・再入・消費の責任を一度に定めます。静的効果を動的に使う場合は`BalanceStaticGeometry`へ実形状と固定/再検索の記述子を追加します。未確定なら`static_only`等で停止条件を残します。
4. 未知のパラメーター・追加ルート効果・資源種別を検出する診断を維持します。`bow`・`velocity_replay`・`party`・`static_only`等、必要条件をプロフィールに含めます。新しい条件名を追加する場合は共通検査と表示も実装します。
5. Plugin実処理を呼ぶJava oracleを追加し、入力・取得元メソッド・モック範囲・期待値をfixtureへ保存します。取得後にC#で境界、適用順、キャンセル、費用、対象なし、tickずれ、再通知を照合します。乱数の固定入力検査と分布検査は分けます。実クラスを呼べない部分は理由と代替検査の範囲を記載します。
6. fixtureの`sources`指紋群と`BalanceSkillSourceParity`、共通計算なら`BalanceCombatParity`の対象指紋を、依存追加を含めて更新します。`catalog.sourceHash`は実ソース集合の識別値です。**指紋だけを更新して照合失敗を消してはいけません。** Javaを再実行し、期待値変更の根拠を確認した後にC#回帰を実行します。既存fixtureを上書きしない生成スクリプトでは、更新差分を明示的にレビューします。
7. `GET /api/balance-lab/catalog`の`skillCatalog`に、実装ID、active/passive、runtime、必要条件、参照、理由が反映されることを確認します。対応件数は現在のカタログから集計し、条件未達を無条件対応へ数えません。モデル版、対応表、この文書と検証記録を更新し、未照合・未実測の境界を残します。

対応アダプターの追加はラボ側の作業です。Plugin本体・マスター・実サーバーへ自動反映する機能はありません。
