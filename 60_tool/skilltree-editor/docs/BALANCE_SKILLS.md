# 統合バランスラボのスキル対応範囲

統合バランスラボは `40_filebase/30.features.skill` の保存済みスキルマスターをカタログへ自動取得します。UIの候補は固定ID一覧ではありません。新しいマスターも一覧に現れ、計算アダプターがなければ「計算未対応」と理由を表示します。以下の件数と表は、2026-10-24のカタログAPIの `skillCatalog` から作成したスナップショットです（モデル `astral-balance/2.0.0`、マスターSHA-256 `f270808d3035383c364fb9b3b46d2ea376dd1dfcdbcf86615d45aef779725880`、PluginソースSHA-256 `7acdfd91edd8dc62a60a81e1b12c7a7f32d7362f6bd9ec5b68398f9a231a57fa`）。マスターやPluginソースが変われば、現在の `GET /api/balance-lab/catalog` を正としてください。

| 全マスター | 計算対応 | 未対応 | 内訳 |
|---:|---:|---:|---|
| 120 | 47 | 73 | 対応47件はアクティブ。未対応はアクティブ49件、パッシブ24件。 |

## 使い方と条件

既存マスターエディタの「比較・成長グラフ」→「統合バランスラボ」で職業、装備、敵、スキルを選びます。「スキル」は初期状態で全マスターを表示します。名前・ID検索、「職業の潜在候補のみ」、「計算対応のみ」で絞り込めます。職業候補はクラスの `usableSkills` とスキルツリーノードの関連を示すだけで、実プレイヤーの習得済み・装着済み・使用可能を判定しません。選択済みスキルは表示フィルタを変えても保持され、↑↓または詳細JSONの `skillIds` 順が戦闘中の使用優先順位です。

選択したスキルは**スキルLv1、シジル未装着、習得・装着・使用許可済み**として扱います。`skillLevels`、`skillSigils`、`sigils`、`skillModifiers` は未対応と診断します。通常攻撃を含めるかは「通常攻撃も使う」で切り替えられます。開始シールド、敵との固定距離（既定3ブロック）、敵の当たり判定の半奥行き（既定0.3ブロック）も条件です。プレイヤー1人対停止した敵1体、20tick/秒の限定モデルであり、視線・地形・移動・操作・対象再選択・複数対象・実サーバーtickを再現するものではありません。各スキル固有の省略条件は画面の「計算の前提」と結果の `assumptions` に表示します。

厳密モードでは未対応効果、射程外、照合済みPluginソースとの不一致を診断して停止します。近似モードを選んだ場合は未対応効果を省いて計算し、診断と前提を結果に残します。「計算対応」は、その限定条件でアダプターを適用できる意味です。全フィールド・全ゲーム状況・実サーバー結果との一致を保証しません。未対応スキルを選ぶ前に一覧の「未対応理由」を確認してください。

ビルドアップは実際の参照Buffマスターの期間と間接攻撃補正を読み、調整案の変更も反映します。未知の追加補正や参照切れは停止します。`BUFF_DURATION_INCREASE` による期間延長は未対応のため、非ゼロのビルドは厳密モードで停止します。敵の通常攻撃にも本体の射程判定を適用し、固定距離で射程外なら攻撃せず接近もしない、と結果へ記録します。

## カタログと追加アダプター

`BalanceLabCatalog` は保存済みマスターを既存parserで読み、`BalanceSkills.Catalog` がスキルごとに `id`、`implementationId`、`path`、表示名、active/passive、対応可否、理由、前提、参照ソース、職業・ツリー関連、クールダウン・詠唱・費用を返します。Clientはこの情報を表示するだけで、戦闘式や対応IDを複製しません。カタログ情報がない新規マスターは未対応として表示します。提案JSONでマスターを変更した場合、実行時には提案後の定義を再び `Resolve` します。

新しい計算対応を加えるときは次の順に進めます。

1. 対象マスターの `implementationId`、`params` の既定値とレベル1値、Pluginのexecutor・runtime・呼出元を追跡します。IDの文字列や表示名から効果を推測しません。
2. `BalanceSkills.Resolve` が参照する `BalanceSkillMeleeProfiles`、`BalanceSkillRangedProfiles`、`BalanceSkillMagicProfiles` または適切な新規adapterに `BalanceSkillProfile` を追加します。`Effects` に実際の命中列・回復・資源・状態異常・陣などを表し、`Sources` に依存するrepo相対ソース、`KnownParameters` に検証済みパラメーター、`Assumptions` に固定単体条件を記します。省略不可の動的効果がある場合は `Reasons` に理由を残して未対応にします。
3. `BalanceSkillEffect` の `Tick` は発射相対tickです。飛翔速度・半径・射程・対象、命中後などの発火条件、発射時評価、効果の持続と適用順をPluginに合わせます。未知のルート項目・`params`、不正値は `BalanceSkills.Resolve` の診断を通し、黙って既定値や0に落としません。
4. `BalanceSkillSourceParity` の照合対象に依存ソースと指紋を追加し、変更時はPlugin実装を再確認します。profile・シミュレーション・カタログのテストを追加し、可能ならJavaの `SkillExecutorGoldenHarness` で実executorの固定入力を取得してC#側と照合します。同じ式を両言語のテストに写した自己照合だけで完了としません。
5. この表を現在のカタログから更新し、対応数・理由・前提・照合範囲を見直します。ClientにIDの固定リストや計算式を追加しません。

関連実装は `src/SkillTreeEditor.Server/Services/BalanceSkills.cs`、`BalanceSkillPlan.cs`、`BalanceSkill*Profiles.cs`、`BalanceSkillSourceParity.cs`、`BalanceCombat.cs`、`src/SkillTreeEditor.Client/src/components/BalanceLab.tsx` です。

## 検証済み範囲と未実測範囲

Pluginソースを読み、計算アダプターの依存ファイルをSHA-256で固定しています。C#テストにはprofileの効果列・未知/未対応検出・固定距離の結果などを含みます。Javaの `SkillExecutorGoldenHarness` が**実executorを呼んだ固定trace**は、現時点で `swordsman_flame_rush`、`swordmaster_blade_tempest`、`swordmaster_crescent_duet` の3件です。既存の `CombatGoldenHarness` はダメージ・詠唱時間・装備などの共通計算を固定ケースで照合します。47スキル全件について実executorのJavaハーネス照合を終えたという意味ではありません。実サーバーでの120件通し実測、移動・複数対象・パーティ・詠唱中断・Bukkit実tick、Plugin乱数列との一致も未実測です。

## 全スキルの対応一覧

表の「理由」はカタログの `reasons` をそのまま列挙します。「対応」の行は個別の仮定と参照ソースを画面またはAPIの `skillCatalog` で確認してください。マスター名のMinecraft装飾コードのみ除去しています。

| ID | 名前 | 種別 | 計算 | 未対応理由 |
|---|---|---|---|---|
| `administrator_just_dodge` | ジャスト回避 | パッシブ | 未対応 | 回避成功時の無敵とENG回復を扱うパッシブです。<br>実行処理で未検証のパラメーター params.invulnerabilityTicks が指定されています。<br>実行処理で未検証のパラメーター params.energyRecoveryAmount が指定されています。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `administrator_shield_recharge` | シールドリチャージ | パッシブ | 未対応 | 盾再充填設定のパッシブであり、castは失敗します。<br>実行処理で未検証のパラメーター params.maxShield が指定されています。<br>実行処理で未検証のパラメーター params.rechargeDelaySeconds が指定されています。<br>実行処理で未検証のパラメーター params.rechargePercentPerSecond が指定されています。<br>実行処理で未検証のパラメーター params.particleIntervalTicks が指定されています。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `adventurer_astral_edge` | アストラルエッジ | アクティブ | 対応 | — |
| `adventurer_blast_arrow` | ブラストアロー | アクティブ | 未対応 | 飛翔体の着弾位置・時間と継承バフに依存します。<br>実行処理で未検証のパラメーター params.inheritanceMasteryName が指定されています。<br>実行処理で未検証のパラメーター params.inheritanceBuffName が指定されています。<br>実行処理で未検証のパラメーター params.inheritanceDurationTicks が指定されています。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.radius が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.projectileSpeed が指定されています。<br>実行処理で未検証のパラメーター params.projectileHitRadius が指定されています。 |
| `adventurer_lightning_bolt` | ライトニングボルト | アクティブ | 未対応 | 飛翔体、連鎖対象、プリズムと状態異常に依存します。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.chainRadius が指定されています。<br>実行処理で未検証のパラメーター params.chainDamageRatio が指定されています。<br>実行処理で未検証のパラメーター params.maxChainTargets が指定されています。<br>実行処理で未検証のパラメーター params.projectileSpeed が指定されています。<br>実行処理で未検証のパラメーター params.projectileHitRadius が指定されています。 |
| `adventurer_mana_burst` | マナバースト | アクティブ | 対応 | — |
| `adventurer_meditation` | メディテーション | パッシブ | 未対応 | チャネル中の状態とMP回復倍率の変化に依存します。<br>実行処理で未検証のパラメーター params.chargeTicks が指定されています。<br>実行処理で未検証のパラメーター params.initialRegenMultiplier が指定されています。<br>実行処理で未検証のパラメーター params.regenMultiplierIncrement が指定されています。<br>実行処理で未検証のパラメーター params.activeDurationTicks が指定されています。<br>実行処理で未検証のパラメーター params.buffId が指定されています。<br>実行処理で未検証のパラメーター params.chargeParticleIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.activeParticleIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.activeSoundIntervalTicks が指定されています。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。<br>リソース  の専用制御は未対応です。 |
| `adventurer_quick_shot` | クイックショット | アクティブ | 対応 | — |
| `adventurer_smash` | スマッシュ | アクティブ | 対応 | — |
| `archer_air_shift` | エアーシフト | パッシブ | 未対応 | 空中回避操作による移動パッシブで、固定距離モデルに移動がない。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `archer_heal_arrow_alpha` | ヒールアローα | アクティブ | 未対応 | 詠唱時のHEALING_INCREASE buffと着弾後の範囲回復・直撃の分岐が必要。 |
| `archer_starweave_followup` | スターウィーバー | パッシブ | 未対応 | 通常弓攻撃の有効命中後に発動するパッシブ追撃で、独立castではない。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `archmage_aegis_circle` | イージスサークル | アクティブ | 対応 | — |
| `archmage_astral_ray` | アストラルレイ | アクティブ | 未対応 | 現存陣数に応じた円と10tick後のランダム対象光線には現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_astral_spear` | アストラルスピア | アクティブ | 対応 | — |
| `archmage_bind_circle` | バインドサークル | アクティブ | 未対応 | 引き寄せと拘束には現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_celestial_circle` | セレスティアルサークル | アクティブ | 未対応 | 陣内のプレイヤーbuffには現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_clear_circle` | クリアサークル | アクティブ | 未対応 | 接触した味方の状態異常浄化には現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_gravity_nova` | グラビティノヴァ | アクティブ | 未対応 | 現存陣数に応じた範囲と押し戻しには現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_heal_circle` | ヒールサークル | アクティブ | 未対応 | 接触した味方の回復には現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_meteor_circle` | メテオサークル | アクティブ | 対応 | — |
| `archmage_orbital_volley` | オービタルヴォレー | アクティブ | 対応 | — |
| `archmage_phoenix_resonance` | 不死鳥との共鳴 | パッシブ | 未対応 | 不死鳥召喚の追撃と消滅を再現する必要があります。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `archmage_reborn_protect_circle` | リボーンプロテクトサークル | アクティブ | 未対応 | 死亡回復と混乱buffには現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_renewal_circle` | リニューアルサークル | アクティブ | 対応 | — |
| `archmage_repulsion_circle` | リパルションサークル | アクティブ | 未対応 | 敵の押し戻しpulseには現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_starfall` | スターフォール | アクティブ | 対応 | — |
| `archmage_stasis_circle` | ステイシスサークル | アクティブ | 未対応 | 移動速度低下pulseには現存陣の登録・終了と位置条件を再現する必要があります。 |
| `archmage_vigor_circle` | ヴィガーサークル | アクティブ | 対応 | — |
| `hunter_arrow_rain` | アローレイン | アクティブ | 未対応 | 初弾とランダム配置の雨矢・重力・範囲内複数命中があり、固定射線でhit列を確定できない。 |
| `hunter_build_up` | ビルドアップ | アクティブ | 対応 | — |
| `hunter_crash_arrow` | クラッシュアロー | アクティブ | 未対応 | シールド破壊倍率、超星クリティカル抽選と継承追撃の処理が必要。 |
| `hunter_fade_shot` | フェイドショット | アクティブ | 未対応 | 扇状の複数矢・拡散角と後方移動により、固定射線の単体衝突へ縮約できない。 |
| `hunter_heal_arrow` | ヒールアロー | アクティブ | 未対応 | 着弾後の範囲回復・地面着弾・敵への直撃の分岐が必要。 |
| `hunter_spell_step` | スペルステップ | パッシブ | 未対応 | 回避入力後の時間窓で発動するパッシブで、単体スキル発動ではない。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `mage_arcane_flow` | アーケインフロー | パッシブ | 未対応 | 異なる魔法の連鎖に伴う詠唱時間短縮が必要です。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `mage_blink` | ブリンク | パッシブ | 未対応 | 空中シフト発動の移動と着地点判定は固定距離モデルで再現できません。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `mage_fireball` | ファイアーボール | アクティブ | 対応 | — |
| `mage_frost_ball` | フロストボール | アクティブ | 対応 | — |
| `mage_frost_blizzard` | フロストブリザード | アクティブ | 未対応 | 移動する竜巻の接触判定と10tick周期の継続命中が必要です。 |
| `mage_heal_aura` | ヒールオーラ | アクティブ | 対応 | — |
| `mage_sparking` | スパーキング | アクティブ | 未対応 | 5発の旋回弾の軌道・命中と感電付与を再現する必要があります。 |
| `melee_dual_crest` | デュアルクレスト | パッシブ | 未対応 | この実行処理の計算アダプターは未実装です。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.hitThreshold が指定されています。<br>実行処理で未検証のパラメーター params.hitIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.crestDurationTicks が指定されています。<br>実行処理で未検証のパラメーター params.targetRange が指定されています。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `paladin_blessed_lance` | ブレストランス | アクティブ | 対応 | — |
| `paladin_defense_conversion` | ディフェンスコンバージョン | パッシブ | 未対応 | 防御転換パッシブであり、castは失敗します。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `paladin_divine_chaser` | ディバインチェイサー | パッシブ | 未対応 | 他スキル発動後の追尾弾を追加するパッシブです。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.sphereRadius が指定されています。<br>実行処理で未検証のパラメーター params.spherePoints が指定されています。<br>実行処理で未検証のパラメーター params.projectileSpeed が指定されています。<br>実行処理で未検証のパラメーター params.projectileHitRadius が指定されています。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `paladin_fortress` | フォートレス | アクティブ | 未対応 | 220tick間の挑発と敵対象選択に依存します。<br>実行処理で未検証のパラメーター params.radius が指定されています。<br>実行処理で未検証のパラメーター params.height が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.durationTicks が指定されています。<br>実行処理で未検証のパラメーター params.pulseIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.tauntHoldTicks が指定されています。<br>実行処理で未検証のパラメーター params.visualIntervalTicks が指定されています。 |
| `paladin_guard_convert` | ガードコンバート | パッシブ | 未対応 | GUARD変換パッシブであり、castは失敗します。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `paladin_guardian_bulwark` | ガーディアンブルワーク | アクティブ | 未対応 | GUARD8の消費と持続被ダメ/与ダメ倍率が必要です。<br>実行処理で未検証のパラメーター params.durationTicks が指定されています。<br>実行処理で未検証のパラメーター params.incomingMultiplier が指定されています。<br>実行処理で未検証のパラメーター params.outgoingMultiplier が指定されています。<br>リソース GUARD の専用制御は未対応です。 |
| `paladin_guardian_chain` | ガーディアンチェイン | アクティブ | 未対応 | 20tick間隔の引寄せで距離と命中を変更します。<br>実行処理で未検証のパラメーター params.radius が指定されています。<br>実行処理で未検証のパラメーター params.pullCount が指定されています。<br>実行処理で未検証のパラメーター params.pullIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.minimumPullStrength が指定されています。<br>実行処理で未検証のパラメーター params.maximumPullStrength が指定されています。<br>実行処理で未検証のパラメーター params.verticalStrength が指定されています。<br>実行処理で未検証のパラメーター params.curveHeight が指定されています。<br>実行処理で未検証のパラメーター params.curvePoints が指定されています。 |
| `paladin_guardian_protect` | ガーディアンプロテクト | アクティブ | 未対応 | パーティ対象への90%ダメージ肩代わりで単独では発動しません。<br>実行処理で未検証のパラメーター params.targetRange が指定されています。<br>実行処理で未検証のパラメーター params.aimRadius が指定されています。<br>実行処理で未検証のパラメーター params.durationTicks が指定されています。<br>実行処理で未検証のパラメーター params.visualIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.damageRedirectRatio が指定されています。 |
| `paladin_guardian_renewal` | ガーディアンリニューアル | アクティブ | 未対応 | GUARD8消費の自己回復でGUARD資源が必要です。<br>実行処理で未検証のパラメーター params.healHpRatio が指定されています。<br>リソース GUARD の専用制御は未対応です。 |
| `paladin_holy_control` | ホーリーコントロール | アクティブ | 未対応 | 聖柱状態・HP200消費・周期バフ/デバフに依存します。<br>実行処理で未検証のパラメーター params.hpCost が指定されています。<br>実行処理で未検証のパラメーター params.pillarSearchRadius が指定されています。<br>実行処理で未検証のパラメーター params.effectRadius が指定されています。<br>実行処理で未検証のパラメーター params.partyDamageIncrease が指定されています。<br>実行処理で未検証のパラメーター params.mobDamageTakenIncrease が指定されています。<br>実行処理で未検証のパラメーター params.hpRecovery が指定されています。<br>実行処理で未検証のパラメーター params.mpRecoveryMaxManaRatio が指定されています。 |
| `paladin_holy_field` | ホーリーフィールド | アクティブ | 未対応 | 聖域の位置と盾破壊倍率による他スキル連携に依存します。<br>実行処理で未検証のパラメーター params.radius が指定されています。<br>実行処理で未検証のパラメーター params.durationTicks が指定されています。<br>実行処理で未検証のパラメーター params.casterShieldBreak が指定されています。<br>実行処理で未検証のパラメーター params.partyShieldBreak が指定されています。 |
| `paladin_holy_reprieve` | ホーリーリプリーヴ | アクティブ | 対応 | — |
| `paladin_holy_smash` | ホーリースマッシュ | アクティブ | 対応 | — |
| `paladin_holy_smite` | ホーリースマイト | アクティブ | 対応 | — |
| `paladin_shield` | パラディンシールド | アクティブ | 未対応 | 盾50%消費、パーティの有無による付与/聖柱分岐に依存します。<br>実行処理で未検証のパラメーター params.minimumCurrentShield が指定されています。<br>実行処理で未検証のパラメーター params.transferRatio が指定されています。<br>実行処理で未検証のパラメーター params.targetRange が指定されています。<br>実行処理で未検証のパラメーター params.temporaryShieldDurationTicks が指定されています。<br>実行処理で未検証のパラメーター params.pillarHeight が指定されています。<br>実行処理で未検証のパラメーター params.pillarDamageRatio が指定されています。<br>実行処理で未検証のパラメーター params.pillarRadius が指定されています。<br>実行処理で未検証のパラメーター params.pillarImpactIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.pillarMaxTargets が指定されています。<br>実行処理で未検証のパラメーター params.pillarSlashRingRadius が指定されています。 |
| `paladin_shield_bash` | シールドバッシュ | アクティブ | 未対応 | GUARD消費・移動経路の反復命中・鈍足/ノックバックに依存します。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.width が指定されています。<br>実行処理で未検証のパラメーター params.height が指定されています。<br>実行処理で未検証のパラメーター params.travelTicks が指定されています。<br>実行処理で未検証のパラメーター params.hitCooldownTicks が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.movementSpeedReductionRatio が指定されています。<br>実行処理で未検証のパラメーター params.slowDurationTicks が指定されています。<br>実行処理で未検証のパラメーター params.knockbackStrength が指定されています。<br>リソース GUARD の専用制御は未対応です。 |
| `paladin_shield_impact` | シールドインパクト | アクティブ | 未対応 | GUARD消費と命中後の敵DEF倍率低下を同時に処理する必要があります。<br>リソース GUARD の専用制御は未対応です。 |
| `phantom_archer_curse_weaving` | カースウィービング | パッシブ | 未対応 | 霊印別の常時倍率と状態異常付与率・持続補正を持つパッシブで、単独castではない。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `phantom_archer_dread_bloom` | ドレッドブルーム | アクティブ | 未対応 | 直撃に加えて着弾点の範囲判定、盲目抽選、霊印蓄積が必要。 |
| `phantom_archer_echo_volley` | エコーボレー | アクティブ | 未対応 | 連射時の再照準・貫通・各有効命中での霊印蓄積が必要。 |
| `phantom_archer_eclipse_shot` | エクリプスショット | アクティブ | 未対応 | チャージ後の射撃、貫通、霊印数依存倍率と霊印消費が必要。 |
| `phantom_archer_mist_retreat` | ミストリトリート | アクティブ | 未対応 | 後退移動・回避補正・次の射撃を強める待機状態が必要。 |
| `phantom_archer_shadow_stitch` | シャドウステッチ | アクティブ | 未対応 | 有効命中で霊印を蓄積し、以後の倍率・消費へ波及する専用stateが必要。 |
| `phantom_archer_soul_pierce` | ソウルピアース | アクティブ | 未対応 | 霊印数に応じた倍率、状態異常特効、貫通後の霊印消費が必要。 |
| `phantom_archer_spectral_attunement` | スペクトラルアチューンメント | パッシブ | 未対応 | 通常弓攻撃の有効命中で霊印を蓄積し、常時CT・MP補正を持つパッシブである。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `phantom_archer_spectral_sentry` | スペクトラルセントリー | アクティブ | 未対応 | 設置型の再照準・複数射撃・霊印蓄積と召喚寿命の専用stateが必要。 |
| `phantom_archer_wraith_step` | レイスステップ | パッシブ | 未対応 | 回避後の待機状態と次の専用矢への独立追加hitを持つパッシブである。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `sharpshooter_fire_arrow` | ファイアアロー | アクティブ | 対応 | — |
| `sharpshooter_glacial_trap` | フロストガーデン | アクティブ | 未対応 | 地面への着弾位置から遅延範囲攻撃・凍結するため、単体射線衝突と異なる。 |
| `sharpshooter_ice_arrow` | アイスアロー | アクティブ | 対応 | — |
| `sharpshooter_inheritance_mastery` | 継承の心得 | パッシブ | 未対応 | 12種の継承バフ・通常攻撃追撃・再消費を管理するパッシブで、単独castではない。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `sharpshooter_phantom_shot` | ファントムショット | アクティブ | 対応 | — |
| `sharpshooter_piercing_line` | ホライゾンブレイカー | アクティブ | 対応 | — |
| `sharpshooter_ricochet_arrow` | リフレインアロー | アクティブ | 対応 | — |
| `sharpshooter_spreading_ambition` | 拡散する野望 | アクティブ | 対応 | — |
| `sharpshooter_thermal_break` | パラドックスボルト | アクティブ | 対応 | — |
| `swordmaster_blade_tempest` | 旋風剣舞 | アクティブ | 対応 | — |
| `swordmaster_crescent_duet` | 月影双斬 | アクティブ | 対応 | — |
| `swordmaster_crimson_drive` | 紅蓮疾駆 | アクティブ | 未対応 | dash成功・経路上命中・炎上・撃破時CD短縮に依存します。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.hitRadius が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.conditionChance が指定されています。<br>実行処理で未検証のパラメーター params.conditionTicks が指定されています。 |
| `swordmaster_flame_lotus` | 焔花咲き | アクティブ | 対応 | — |
| `swordmaster_frost_bloom` | 氷華円舞 | アクティブ | 対応 | — |
| `swordmaster_gale_reaper` | 疾風抜刀 | アクティブ | 未対応 | dash成功と移動経路上の命中に依存します。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.hitRadius が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。 |
| `swordmaster_heartseeker` | 断命剣 | アクティブ | 対応 | — |
| `swordmaster_mirage_retreat` | ミラージュリトリート | アクティブ | 未対応 | backstep成功後の位置から3回の対象検索に依存します。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.radius が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.hitCount が指定されています。<br>実行処理で未検証のパラメーター params.intervalTicks が指定されています。 |
| `swordmaster_overdrive` | オーバードライブ | アクティブ | 対応 | — |
| `swordmaster_revenge_accel` | リヴェンジアクセル | アクティブ | 未対応 | 静寂一閃の剣気消費状態に依存します。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.angle が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.riposteMultiplier が指定されています。 |
| `swordmaster_riposte` | 返し刃 | アクティブ | 未対応 | 静寂一閃の剣気消費状態に依存します。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.angle が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.riposteMultiplier が指定されています。 |
| `swordmaster_seijaku_issen` | 静寂一閃 | パッシブ | 未対応 | 被弾と通常攻撃に結合したパッシブ反撃です。<br>実行処理で未検証のパラメーター params.counterTicks が指定されています。<br>実行処理で未検証のパラメーター params.counterDamageRatio が指定されています。<br>実行処理で未検証のパラメーター params.energyRecoveryRatio が指定されています。<br>実行処理で未検証のパラメーター params.counterSweepRange が指定されています。<br>実行処理で未検証のパラメーター params.counterSweepMaxTargets が指定されています。<br>実行処理で未検証のパラメーター params.maxRiposteStacks が指定されています。<br>実行処理で未検証のパラメーター params.counterDamageBonusPerStack が指定されています。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `swordmaster_serene_mend` | 明鏡止水 | アクティブ | 未対応 | 剣気有無による追加HP/ENG回復と剣気獲得に依存します。<br>実行処理で未検証のパラメーター params.healHpRatio が指定されています。<br>実行処理で未検証のパラメーター params.riposteHealHpRatio が指定されています。<br>実行処理で未検証のパラメーター params.energyRecovery が指定されています。<br>実行処理で未検証のパラメーター params.riposteGain が指定されています。 |
| `swordmaster_star_cleave` | 星砕き | アクティブ | 対応 | — |
| `swordmaster_thunder_line` | 雷鳴一文字 | アクティブ | 対応 | — |
| `swordmaster_trinity_edge` | 三煌終刃 | アクティブ | 対応 | — |
| `swordmaster_vortex_edge` | ヴォルテックスエッジ | アクティブ | 未対応 | 引寄せで以後の距離と命中を変更します。<br>実行処理で未検証のパラメーター params.radius が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.pullStrength が指定されています。<br>実行処理で未検証のパラメーター params.stopDistance が指定されています。 |
| `swordsman_bastion_strike` | バスティオンストライク | パッシブ | 未対応 | 被弾を契機とするパッシブ反撃と現在MP全消費に依存します。<br>実行処理で未検証のパラメーター params.range が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.consumeAllCurrentMana が指定されています。<br>実行処理で未検証のパラメーター params.levelFiveRequiredManaRatio が指定されています。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `swordsman_challenging_roar` | チャレンジングロア | アクティブ | 未対応 | 挑発による敵の対象選択は固定1対1モデルの発動効果外です。<br>実行処理で未検証のパラメーター params.radius が指定されています。<br>実行処理で未検証のパラメーター params.height が指定されています。<br>実行処理で未検証のパラメーター params.maxTargets が指定されています。<br>実行処理で未検証のパラメーター params.durationTicks が指定されています。<br>実行処理で未検証のパラメーター params.visualIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.tauntIntervalTicks が指定されています。<br>実行処理で未検証のパラメーター params.tauntHoldTicks が指定されています。 |
| `swordsman_exept_stamp` | エクゼプトスタンプ | アクティブ | 未対応 | 飛翔・スニーク・着地で命中の有無と時刻が変わります。<br>実行処理で未検証のパラメーター params.radius が指定されています。<br>実行処理で未検証のパラメーター params.damageRatio が指定されています。<br>実行処理で未検証のパラメーター params.movementSpeedReduction が指定されています。<br>実行処理で未検証のパラメーター params.movementSpeedDebuffDurationTicks が指定されています。<br>実行処理で未検証のパラメーター params.launchHorizontalVelocity が指定されています。<br>実行処理で未検証のパラメーター params.launchVerticalVelocity が指定されています。<br>実行処理で未検証のパラメーター params.diveVelocity が指定されています。<br>実行処理で未検証のパラメーター params.maxFlightTicks が指定されています。 |
| `swordsman_flame_rush` | フレイムラッシュ | アクティブ | 対応 | — |
| `swordsman_shield_activate` | タンクシールドアクティベート | パッシブ | 未対応 | シールド常時パッシブであり、castは失敗します。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `swordsman_shield_drain` | シールドドレイン | アクティブ | 対応 | — |
| `wizard_arcane_lance` | アーケインランス | アクティブ | 対応 | — |
| `wizard_arcane_overload` | アーケインオーバーロード | アクティブ | 対応 | — |
| `wizard_burn_meteor_strike` | バーンメテオストライク | パッシブ | 未対応 | SKILL由来BURNING付与成功時の追加MP消費と隕石追撃が必要です。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `wizard_cinder_lance` | シンダーランス | アクティブ | 対応 | — |
| `wizard_elemental_ball` | エレメンタルボール | アクティブ | 対応 | — |
| `wizard_elemental_prism` | エレメンタルプリズム | アクティブ | 未対応 | 設置プリズムが他の魔法を吸収して発射するruntime状態が必要です。 |
| `wizard_emulate_spark` | イミュレートスパーク | アクティブ | 未対応 | 跳弾軌道、同一敵への最大4hit、感電付与が必要です。 |
| `wizard_lightning_strike` | ライトニングストライク | パッシブ | 未対応 | SKILL由来SHOCKED付与成功時の追加MP消費と落雷追撃が必要です。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `wizard_mana_siphon` | マナサイフォン | アクティブ | 対応 | — |
| `wizard_mana_veil` | マナヴェール | アクティブ | 対応 | — |
| `wizard_meteor` | メテオ | アクティブ | 未対応 | 60tick後の着弾、広がる波、燃焼付与を含む距離依存命中が必要です。 |
| `wizard_prism_condition` | プリズムコンディション | パッシブ | 未対応 | 状態異常付与による魔法陣生成と接触MP回復が必要です。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `wizard_prismatic_dart` | プリズマティックダート | アクティブ | 対応 | — |
| `wizard_rime_nova` | ライムノヴァ | アクティブ | 未対応 | 2tick刻みの氷波とFROZEN・CHILLED、BURNING増幅が必要です。 |
| `wizard_self_heal` | セルフヒール | アクティブ | 対応 | — |
| `wizard_spectral_orrery` | スペクトラルオーラリー | パッシブ | 未対応 | 200tick内の火氷雷3属性履歴と3成分の追撃が必要です。<br>パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。 |
| `wizard_storm_chain` | ストームチェイン | アクティブ | 対応 | — |
| `wizard_triad_convergence` | トライアドコンバージェンス | アクティブ | 対応 | — |
