# 統合バランスラボのスキル対応範囲

ラボは `40_filebase/30.features.skill` の保存済みマスターを既存parserで自動取得します。画面の候補は固定ID一覧ではありません。新規マスターも表示し、未登録の実装・未知の効果・参照切れは理由付きで診断します。

以下は2026-10-24のカタログAPIによるスナップショットです。モデル `astral-balance/3.0.0`、マスターSHA-256 `1bedca845e5c4d8dfee13f54c9a05c7822de72333306cc7f955054cf0d75bf18`、PluginソースSHA-256 `7acdfd91edd8dc62a60a81e1b12c7a7f32d7362f6bd9ec5b68398f9a231a57fa`。変更後は `GET /api/balance-lab/catalog` を正とします。

| 全マスター | アクティブ | パッシブ | アダプターあり | うち追加条件あり | 未登録 |
|---:|---:|---:|---:|---:|---:|
| 120 | 96 | 24 | 120 | 20 | 0 |

「アダプターあり」は限定モデルでの対応です。20件には弓装備、味方入力、速度変更後の座標リプレイ、静止条件のみ等の追加要件があります。全120件の実サーバー再現を意味しません。88件はイベント・状態を扱うruntimeを使用し、32件は静的効果計画です。前版の未対応73件（アクティブ49・パッシブ24）へ段階的にアダプターを追加しました。

## 使い方と対応条件

「比較・成長グラフ」→「統合バランスラボ」で選択します。検索・職業の潜在候補・計算対応フィルタを使えます。職業候補はクラスとツリーの関連情報です。実プレイヤーの習得済み・装着可否の判定はしません。選択したスキルはLv1・シジルなし・使用許可済みとして扱います。

アクティブは `builds[].skillIds` の順で発動を試みます。パッシブは `passiveSkillIds` に装着し、開始時のbindと実行中のイベントで発動します。依存パッシブを自動装着しません。通常攻撃・回避・スニーク・現在位置・味方・専用資源の指定方法は [BALANCE_RUNTIME.md](BALANCE_RUNTIME.md) を参照してください。各カードの追加条件・計算の前提・参照ソースも確認できます。

| 要件 | 指定・動作 |
|---|---|
| `bow` | 本体と同じ弓装備条件を満たす必要があります。装備不足を別の攻撃式で補いません。 |
| `velocity_replay` | `world.velocityMode="replay"` と、速度変更の次tickから終了まで毎tickの対象座標が必要です。必須軌跡が欠けると停止します。Minecraft物理から座標を生成しません。 |
| `party` | 明示的な味方 `allies` が必要です。位置・生存・視線等による発動失敗も区別します。 |
| `static_only` | 動的runtime経路とは併用できません。アストラルエッジの6フレーム軌道・命中相対tickの動的移植は残課題です。 |

世界は読み込み済みの遮蔽物のない水平床、敵1体、20tick/秒です。ダッシュ・瞬間移動、投射・範囲形状、発動時固定/現在位置の区別を扱います。速度命令後の摩擦・重力・ブロック衝突によるエンティティ移動、実敵AI・複数敵・実操作判断は再現しません。味方は最大8人の明示状態で、自律攻撃は行いません。

`strict` は対応条件・既知パラメーター・Plugin指紋を検査する限定モデルです。未対応効果を黙って無効化しません。`approximate` は診断付き省略を許しますが、欠落した必須リプレイや不正入力の架空補完はしません。新たなマスター効果が追加されれば、この件数が同じでも実行が停止する場合があります。

## 実装と追加方法

マスター解析・カタログ・診断は `BalanceSkills` と `BalanceSkillSourceParity`、効果計画は `BalanceSkill*Profiles`、イベント接続は `BalanceCombatSimulation`、世界は `BalanceRuntimeWorld`、近接・遠隔・魔法は `BalanceMeleeRuntime` / `BalanceRangedRuntime` / `BalanceMagicRuntime` が担当します。ダメージ・回復・状態・Buff・費用・予約は `BalanceRuntimeContext` の共通操作へ委譲します。Clientに対応IDや戦闘式を重複実装しません。

新しい効果の調査、既知パラメーター登録、形状・時点・費用・イベントの実装、Java oracle、指紋更新、回帰テストの手順は [BALANCE_RUNTIME.mdの追加手順](BALANCE_RUNTIME.md#追加効果変更時の手順) に記載しています。指紋だけ更新して照合失敗を消してはいけません。

## 照合済み・未検証の境界

Plugin実クラスを呼ぶJavaハーネスは共通20、executor3、状態等5、近接runtime7、遠隔runtime4、魔法runtime6の計45ケースです。runtime追加分はGUARD・剣気、呪印・弾道、Prism・渦・反射・履歴・瞑想等の固定ケースです。モック・反射による初期状態注入を含み、全スキルのcast全体ではありません。取得方法・実メソッド・限界は [照合README](../tests/plugin-parity/README.md) と [runtime照合範囲](BALANCE_RUNTIME.md#javaハーネスが照合する範囲) を参照してください。

**全120件の実executorのcast通し照合、全組合せ、実サーバー・実DB・実プレイヤー、Bukkitイベントバス全体と実tickは未検証です。** 同seedのモデル再現と、PluginのThreadLocalRandomの乱数列一致は別です。検証結果は [3.0検証記録](BALANCE_RUNTIME_VERIFICATION.md) にまとめています。

## 全スキル一覧

カタログの種別・runtime・追加条件・理由から生成しています。Minecraft装飾コードだけを名前から除きました。個別の前提・ソースは画面またはAPIを参照してください。

| ID | 名前 | 種別 | 処理 | 対応条件・未対応理由 |
|---|---|---|---|---|
| `administrator_just_dodge` | ジャスト回避 | パッシブ | runtime | 限定条件で対応 |
| `administrator_shield_recharge` | シールドリチャージ | パッシブ | runtime | 限定条件で対応 |
| `adventurer_astral_edge` | アストラルエッジ | アクティブ | 静的計画 | `static_only` |
| `adventurer_blast_arrow` | ブラストアロー | アクティブ | runtime | 限定条件で対応 |
| `adventurer_lightning_bolt` | ライトニングボルト | アクティブ | runtime | 限定条件で対応 |
| `adventurer_mana_burst` | マナバースト | アクティブ | 静的計画 | 限定条件で対応 |
| `adventurer_meditation` | メディテーション | パッシブ | runtime | 限定条件で対応 |
| `adventurer_quick_shot` | クイックショット | アクティブ | 静的計画 | 限定条件で対応 |
| `adventurer_smash` | スマッシュ | アクティブ | 静的計画 | 限定条件で対応 |
| `archer_air_shift` | エアーシフト | パッシブ | runtime | `velocity_replay` |
| `archer_heal_arrow_alpha` | ヒールアローα | アクティブ | runtime | 限定条件で対応 |
| `archer_starweave_followup` | スターウィーバー | パッシブ | runtime | `bow` |
| `archmage_aegis_circle` | イージスサークル | アクティブ | runtime | 限定条件で対応 |
| `archmage_astral_ray` | アストラルレイ | アクティブ | runtime | 限定条件で対応 |
| `archmage_astral_spear` | アストラルスピア | アクティブ | 静的計画 | 限定条件で対応 |
| `archmage_bind_circle` | バインドサークル | アクティブ | runtime | 限定条件で対応 |
| `archmage_celestial_circle` | セレスティアルサークル | アクティブ | runtime | 限定条件で対応 |
| `archmage_clear_circle` | クリアサークル | アクティブ | runtime | 限定条件で対応 |
| `archmage_gravity_nova` | グラビティノヴァ | アクティブ | runtime | 限定条件で対応 |
| `archmage_heal_circle` | ヒールサークル | アクティブ | runtime | 限定条件で対応 |
| `archmage_meteor_circle` | メテオサークル | アクティブ | 静的計画 | 限定条件で対応 |
| `archmage_orbital_volley` | オービタルヴォレー | アクティブ | 静的計画 | 限定条件で対応 |
| `archmage_phoenix_resonance` | 不死鳥との共鳴 | パッシブ | runtime | 限定条件で対応 |
| `archmage_reborn_protect_circle` | リボーンプロテクトサークル | アクティブ | runtime | `party` |
| `archmage_renewal_circle` | リニューアルサークル | アクティブ | runtime | 限定条件で対応 |
| `archmage_repulsion_circle` | リパルションサークル | アクティブ | runtime | 限定条件で対応 |
| `archmage_starfall` | スターフォール | アクティブ | 静的計画 | 限定条件で対応 |
| `archmage_stasis_circle` | ステイシスサークル | アクティブ | runtime | 限定条件で対応 |
| `archmage_vigor_circle` | ヴィガーサークル | アクティブ | runtime | 限定条件で対応 |
| `hunter_arrow_rain` | アローレイン | アクティブ | runtime | 限定条件で対応 |
| `hunter_build_up` | ビルドアップ | アクティブ | 静的計画 | 限定条件で対応 |
| `hunter_crash_arrow` | クラッシュアロー | アクティブ | runtime | 限定条件で対応 |
| `hunter_fade_shot` | フェイドショット | アクティブ | runtime | `velocity_replay` |
| `hunter_heal_arrow` | ヒールアロー | アクティブ | runtime | 限定条件で対応 |
| `hunter_spell_step` | スペルステップ | パッシブ | runtime | 限定条件で対応 |
| `mage_arcane_flow` | アーケインフロー | パッシブ | runtime | 限定条件で対応 |
| `mage_blink` | ブリンク | パッシブ | runtime | 限定条件で対応 |
| `mage_fireball` | ファイアーボール | アクティブ | 静的計画 | 限定条件で対応 |
| `mage_frost_ball` | フロストボール | アクティブ | 静的計画 | 限定条件で対応 |
| `mage_frost_blizzard` | フロストブリザード | アクティブ | runtime | 限定条件で対応 |
| `mage_heal_aura` | ヒールオーラ | アクティブ | runtime | 限定条件で対応 |
| `mage_sparking` | スパーキング | アクティブ | runtime | 限定条件で対応 |
| `melee_dual_crest` | デュアルクレスト | パッシブ | runtime | 限定条件で対応 |
| `paladin_blessed_lance` | ブレストランス | アクティブ | runtime | 限定条件で対応 |
| `paladin_defense_conversion` | ディフェンスコンバージョン | パッシブ | runtime | 限定条件で対応 |
| `paladin_divine_chaser` | ディバインチェイサー | パッシブ | runtime | 限定条件で対応 |
| `paladin_fortress` | フォートレス | アクティブ | runtime | 限定条件で対応 |
| `paladin_guard_convert` | ガードコンバート | パッシブ | runtime | 限定条件で対応 |
| `paladin_guardian_bulwark` | ガーディアンブルワーク | アクティブ | runtime | 限定条件で対応 |
| `paladin_guardian_chain` | ガーディアンチェイン | アクティブ | runtime | `velocity_replay` |
| `paladin_guardian_protect` | ガーディアンプロテクト | アクティブ | runtime | `party` |
| `paladin_guardian_renewal` | ガーディアンリニューアル | アクティブ | runtime | 限定条件で対応 |
| `paladin_holy_control` | ホーリーコントロール | アクティブ | runtime | 限定条件で対応 |
| `paladin_holy_field` | ホーリーフィールド | アクティブ | runtime | 限定条件で対応 |
| `paladin_holy_reprieve` | ホーリーリプリーヴ | アクティブ | runtime | 限定条件で対応 |
| `paladin_holy_smash` | ホーリースマッシュ | アクティブ | runtime | 限定条件で対応 |
| `paladin_holy_smite` | ホーリースマイト | アクティブ | runtime | 限定条件で対応 |
| `paladin_shield` | パラディンシールド | アクティブ | runtime | 限定条件で対応 |
| `paladin_shield_bash` | シールドバッシュ | アクティブ | runtime | `velocity_replay` |
| `paladin_shield_impact` | シールドインパクト | アクティブ | runtime | 限定条件で対応 |
| `phantom_archer_curse_weaving` | カースウィービング | パッシブ | runtime | `bow` |
| `phantom_archer_dread_bloom` | ドレッドブルーム | アクティブ | runtime | `bow` |
| `phantom_archer_echo_volley` | エコーボレー | アクティブ | runtime | `bow` |
| `phantom_archer_eclipse_shot` | エクリプスショット | アクティブ | runtime | `bow` |
| `phantom_archer_mist_retreat` | ミストリトリート | アクティブ | runtime | `bow`, `velocity_replay` |
| `phantom_archer_shadow_stitch` | シャドウステッチ | アクティブ | runtime | `bow` |
| `phantom_archer_soul_pierce` | ソウルピアース | アクティブ | runtime | `bow` |
| `phantom_archer_spectral_attunement` | スペクトラルアチューンメント | パッシブ | runtime | `bow` |
| `phantom_archer_spectral_sentry` | スペクトラルセントリー | アクティブ | runtime | `bow` |
| `phantom_archer_wraith_step` | レイスステップ | パッシブ | runtime | `bow` |
| `sharpshooter_fire_arrow` | ファイアアロー | アクティブ | runtime | 限定条件で対応 |
| `sharpshooter_glacial_trap` | フロストガーデン | アクティブ | runtime | 限定条件で対応 |
| `sharpshooter_ice_arrow` | アイスアロー | アクティブ | runtime | 限定条件で対応 |
| `sharpshooter_inheritance_mastery` | 継承の心得 | パッシブ | runtime | 限定条件で対応 |
| `sharpshooter_phantom_shot` | ファントムショット | アクティブ | runtime | 限定条件で対応 |
| `sharpshooter_piercing_line` | ホライゾンブレイカー | アクティブ | runtime | 限定条件で対応 |
| `sharpshooter_ricochet_arrow` | リフレインアロー | アクティブ | runtime | 限定条件で対応 |
| `sharpshooter_spreading_ambition` | 拡散する野望 | アクティブ | runtime | 限定条件で対応 |
| `sharpshooter_thermal_break` | パラドックスボルト | アクティブ | runtime | 限定条件で対応 |
| `swordmaster_blade_tempest` | 旋風剣舞 | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_crescent_duet` | 月影双斬 | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_crimson_drive` | 紅蓮疾駆 | アクティブ | runtime | 限定条件で対応 |
| `swordmaster_flame_lotus` | 焔花咲き | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_frost_bloom` | 氷華円舞 | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_gale_reaper` | 疾風抜刀 | アクティブ | runtime | 限定条件で対応 |
| `swordmaster_heartseeker` | 断命剣 | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_mirage_retreat` | ミラージュリトリート | アクティブ | runtime | 限定条件で対応 |
| `swordmaster_overdrive` | オーバードライブ | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_revenge_accel` | リヴェンジアクセル | アクティブ | runtime | 限定条件で対応 |
| `swordmaster_riposte` | 返し刃 | アクティブ | runtime | 限定条件で対応 |
| `swordmaster_seijaku_issen` | 静寂一閃 | パッシブ | runtime | 限定条件で対応 |
| `swordmaster_serene_mend` | 明鏡止水 | アクティブ | runtime | 限定条件で対応 |
| `swordmaster_star_cleave` | 星砕き | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_thunder_line` | 雷鳴一文字 | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_trinity_edge` | 三煌終刃 | アクティブ | 静的計画 | 限定条件で対応 |
| `swordmaster_vortex_edge` | ヴォルテックスエッジ | アクティブ | runtime | `velocity_replay` |
| `swordsman_bastion_strike` | バスティオンストライク | パッシブ | runtime | 限定条件で対応 |
| `swordsman_challenging_roar` | チャレンジングロア | アクティブ | runtime | 限定条件で対応 |
| `swordsman_exept_stamp` | エクゼプトスタンプ | アクティブ | runtime | `velocity_replay` |
| `swordsman_flame_rush` | フレイムラッシュ | アクティブ | 静的計画 | 限定条件で対応 |
| `swordsman_shield_activate` | タンクシールドアクティベート | パッシブ | runtime | 限定条件で対応 |
| `swordsman_shield_drain` | シールドドレイン | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_arcane_lance` | アーケインランス | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_arcane_overload` | アーケインオーバーロード | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_burn_meteor_strike` | バーンメテオストライク | パッシブ | runtime | 限定条件で対応 |
| `wizard_cinder_lance` | シンダーランス | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_elemental_ball` | エレメンタルボール | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_elemental_prism` | エレメンタルプリズム | アクティブ | runtime | 限定条件で対応 |
| `wizard_emulate_spark` | イミュレートスパーク | アクティブ | runtime | 限定条件で対応 |
| `wizard_lightning_strike` | ライトニングストライク | パッシブ | runtime | 限定条件で対応 |
| `wizard_mana_siphon` | マナサイフォン | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_mana_veil` | マナヴェール | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_meteor` | メテオ | アクティブ | runtime | 限定条件で対応 |
| `wizard_prism_condition` | プリズムコンディション | パッシブ | runtime | 限定条件で対応 |
| `wizard_prismatic_dart` | プリズマティックダート | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_rime_nova` | ライムノヴァ | アクティブ | runtime | 限定条件で対応 |
| `wizard_self_heal` | セルフヒール | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_spectral_orrery` | スペクトラルオーラリー | パッシブ | runtime | 限定条件で対応 |
| `wizard_storm_chain` | ストームチェイン | アクティブ | 静的計画 | 限定条件で対応 |
| `wizard_triad_convergence` | トライアドコンバージェンス | アクティブ | 静的計画 | 限定条件で対応 |
