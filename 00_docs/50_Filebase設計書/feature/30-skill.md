# Skill 設計

## 役割

Skill は、プレイヤーまたは Mob が実行する能動・受動能力と、自動生成するスキルジェムを定義します。

## 設計方針

- 1 skill につき、攻撃、防御、移動、回復、補助の主目的を1つ定めます。
- 発動条件、対象、コスト、クールダウン、効果範囲を組み合わせて強さを制御します。
- class や equipment の弱点を無条件で消す効果は避けます。
- 実行可能な action と値は Plugin 設計・実装を正とします。
- プレイヤー向け職業発動スキルの ID は、職業 prefix と lowercase snake_case を組み合わせ、`implementationId` と同じ値にします。
- リソース種別・消費量・クールダウン・詠唱時間などの共通値は top-level 項目へ定義し、`params` は実装固有の拡張が本当に必要な場合だけ使用します。
- 当たり判定、倍率、状態異常、演出はスキルごとの Plugin 実装へ型付きで定義し、YAML へ同じ既定値を大量に複製しません。プレイヤーが判断するために必要な効果値は `description` / `lore` と実装を同期します。
- 攻撃演出は当たり判定の形と発生時刻が読み取れる輪郭を優先し、持続パーティクルを常時大量に表示しません。
- API は全スキルから決定的 ID `00_skill_gem_<skillId>` の非スタック型ジェムを自動生成します。ジェム自身はレベルを持ちません。生成後IDはインベントリ列に合わせ100文字以内とし、loot・quest等のitem参照検証でも有効な仮想itemとして扱います。
- 全skill masterで `gem` と空でない `gem.rarity` を明示し、レベル別status補正には共有カタログの既知status IDだけを指定します。
- ジェム交換所での初回購入は Lv.1 の新しい個体を即時習得し、同じスキルを再購入した場合は既存個体を1レベル上げます。最大レベルでは購入できません。インベントリ操作やスキルマネージャーからの習得・レベルアップは行いません。
- レベル差分は `levels` へ前レベルからの増分として定義し、最大レベルはスキルごとの `maxLevel` を正本とします。
- `allowedSigilIds` と `sigilSlotsByLevel` で、消費装着可能なシジルとレベル別枠数を定義します。

## 表示文と lore の使い分け

- `description` はスキルの主目的や印象を伝える抽象的な一文だけを記載します。原則として表示上1行に収まる長さ（目安40文字以内）にし、具体的な効果詳細、数値、発動条件、対象数、持続時間、例外処理、消費やクールダウンは書きません。
- 効果の詳細、条件、対象、数値、状態異常、発動・中断条件は `lore` へ移します。1行1要素を基本とし、変動する値は `params` のプレースホルダーで表示します。
- 消費リソース、クールダウン、詠唱時間などの共通値は top-level 項目と GUI の専用表示を正とし、`description` や `lore` へ重複記載しません。
- Mob 用など詳細説明を必要としない定義は、短い `description` のみでも構いません。具体的なプレイヤー向け説明が必要な場合は、必ず `lore` を追加します。

## 現行の職業発動スキル

| 職業 | コンセプト | 主リソース | 主な当たり判定 |
|:--|:--|:--|:--|
| `adventurer` | 近接・間接・魔法の基礎操作を試す見習い | `ENERGY` / `MANA` | 扇形、単体飛翔体、単体対象 |
| `hunter` | 散弾・移動・着弾地点制圧・シールド破壊・回復支援・自己強化を扱う遠距離職 | `ENERGY` + `MANA` | 複数短射程飛翔体、単体飛翔体、発射後移動、重力弾道範囲、着弾回復エリア、自己強化バフ |
| `mage` | 魔法攻撃、壁面反射弾、移動する範囲制圧、短周期の範囲回復を扱う遠距離職 | `MANA` | 前方魔法、条件付き連鎖、着弾範囲、周囲反射弾、移動する持続範囲、発動者中心の即時回復範囲 |

現行定義は冒険者、ソードマン、ハンター、メイジ、Administrator向けのskillを含みます。アローレインはハンター、ヒールオーラはメイジ、シールドドレインとチャレンジングロアは各classの `usableSkills` から初期使用許可を与えます。追加skillは `starter` skilltreeから使用許可を与え、ソードマンはnode `1202` / `1203` / `1204` / `1211` / `1352`、ハンターは `1282`～`1284` / `1353` / `1355`、メイジは `1347`～`1349` / `1351` / `1354`、メディテーションは共通root `1047`、ジャスト回避は冒険者node `1350` とハンターnode `1355` を使用します。Administratorの `usableSkills` には検証用の使用許可を残します。クラスやskilltreeは使用許可だけを与え、習得済み個体の作成はジェム消費に限定します。

ハンターの `hunter_fade_shot` は、5本の短射程飛翔体と水平velocityによるバックステップを同時に扱う機動射撃です。ハンターの `usableSkills` から直接使用許可を配布し、習得用ジェムは `skill_gem_exchange` で無印原石2個から交換します。`hunter_arrow_rain` はハンターの `usableSkills` から初期使用許可を与えます。`hunter_heal_arrow` はハンターの `usableSkills` へ追加せず、`starter` node `1284` から使用許可を与えます。

`swordsman_shield_drain` は前方8m・全角110度から最大8体を選ぶ扇形近接攻撃です。各対象へ97.5%の基礎攻撃、敵Shieldへの3倍ブレイク、実減少量50%の自己Shield吸収を行い、消費リソースはMP10とします。実際に自身のShieldが回復した場合だけ、対象から発動者へ向かう吸収演出を表示します。

`administrator_shield_recharge` は最大Shieldを30増加し、シールド残存時の被弾後8秒から最大Shieldの毎秒2%を再充填するバインド必須パッシブです。ただし `swordsman_shield_activate` が有効でない限り、最大Shield補正があってもShieldの獲得・再充填は行いません。再充填中に被弾すると待機をやり直し、シールド破壊時は通常の回復仕様に従います。ソードマンではskilltree node `1202`、Administratorではclassの `usableSkills` から使用を許可します。

`swordsman_shield_activate` は、使用許可と `passive.bindRequired: true` のパッシブ設定がそろった場合だけ、プレイヤーのShield獲得・回復・再充填を有効化するソードマン用パッシブです。使用許可はskilltree node `1352` とAdministratorのclassから与え、無効時の現在Shieldは0として扱います。

`swordsman_bastion_strike` は前方単体へLv.1で187.5%、Lv.5で225%の攻撃を行い、シールドアクティベートが有効な場合だけ発動時に自身の現在Shieldを最大Shieldまで即時回復します。Lv.1〜4はMP最大時だけ、Lv.5は最大MPの80%以上で発動でき、成功時は現在MPを全量消費します。Lv.1の150秒からレベルごとに10秒ずつ短縮され、シジル枠は全レベル0です。

`swordsman_flame_rush` は前方最大5体へLv.1で78% / 90%の火属性二連撃を行い、二撃目の炎上率はLv.1〜7が0%、Lv.8/9/10が35%/40%/45%です。`swordsman_challenging_roar` は周囲のMobを一時的に挑発する非攻撃スキルです。

`hunter_crash_arrow` は、HPへの基礎倍率45%の単体遠距離攻撃です。計算シールドダメージ全体へ一撃限定のシールドブレイク倍率を適用し、Lv.1の3.0倍からレベルごとに0.5ずつ増加します。`starter` node `1282` から使用許可を与え、ジェムは無印原石3個との交換で入手します。
`hunter_heal_arrow` は、強い重力を受けてMobを貫通し、Blockへ到達する矢です。敵Mobへの命中ごとに36%の無属性間接ダメージとその地点の半径2m・3秒の回復エリアを作り、Block命中時にも同じ回復エリアを作ります。各エリアへ入ったプレイヤーは1エリアにつき1回だけ、Lv.1の12からLv.5の28まで回復します。

`swordsman_last_shield` は、シールドを破壊する `NORMAL_ATTACK` / `SKILL` の直接攻撃を1回だけ無効化するバインド必須パッシブです。クールダウンは120秒で、表示アイコンは `BEACON` とします。無効化時はシールドを消費せず、HPダメージ・ノックバック・耐久消費も発生しません。状態異常DoTと環境ダメージは対象外です。

`hunter_arrow_rain` は2秒詠唱後に重力のある初弾を放ち、Mobまたはblockへの着弾地点を中心にLv.1で半径3m・45本、Lv.5で半径5m・81本の雨矢を3本/tickで降らせます。雨矢は初弾の正確な着弾Y以上にあるblockを貫通し、着弾Yより低い位置では従来どおり最初のblockで消滅します。初弾と雨矢は `RANGED` / `NONE` で、Lv.1基礎倍率は84% / 36%です。主ENG 16と副MP 8を同時消費し、クールダウンは12秒です。ハンターclassで初期使用許可を与え、習得個体は交換ジェムから作成します。
`mage_fireball` は、射程16mの火球を最初のMobまたはBlockへ着弾させ、半径2.25m・最大4体へ火属性の範囲攻撃を行うメイジの初期魔法です。MP 12、4秒のクールダウン、0.2秒詠唱で、Lv.1の基礎倍率132%からレベル補正を加え、最大Lv.5では158.4%です。使用許可は `starter` node `1354` とAdministratorで与え、習得個体は `skill_gem_exchange` の無印原石2個交換から作成します。
`mage_heal_aura` は、発動者を中心に水平半径4m・上下3m以内のゲームプレイ中プレイヤー全員を即時回復するメイジの短周期支援魔法です。回復量はLv.1の5からLv.5の9まで、消費MPは6、クールダウンはLv.1の2秒からLv.5の1.6秒まで短縮します。範囲輪郭は紫色の粒子リングで示し、実際に回復したプレイヤーへ追加の回復粒子を表示します。メイジclassとAdministratorで使用許可を与え、習得個体は `skill_gem_exchange` の無印原石2個交換から作成します。攻撃を行わないためDPS算出対象外です。
`hunter_spell_step` は、`ranged` タグ付きスキルの成功後20tick以内に行う次のドッジを1回だけENG消費0にするバインド必須パッシブです。無料化成立時は `block.beacon.power_select` を再生し、通常のドッジ移動・成功通知・演出は維持します。`starter` node `1283` から使用許可を与え、ジェムは `skill_gem_exchange` の無印原石3個交換で入手します。攻撃を行わないためDPS算出対象外です。

`hunter_build_up` は発動後20秒間、`RANGED_ATTACK` の `SCALAR` 補正を10%付与するハンターの自己強化スキルです。クールダウンは30秒、消費はENERGY10、最大Lv.1とし、効果の実体は `hunter_build_up` buff masterへ委ねます。使用許可は `starter` node `1353` から与え、ジェムは `skill_gem_exchange` のslot19で無印原石3個から交換します。ハンターclassの `usableSkills` へは追加せず、Administratorには検証用の使用許可を与えます。

`mage_sparking` は、足元付近から水平360度へLv.1で5個、Lv.5で13個の雷弾を放つメイジ用の範囲制圧魔法です。雷弾は2.5秒で半径5mまで2周する渦を描き、壁面では螺旋軌道ごと反射します。命中半径は0.60mで、各弾は最初に触れたMobへ `MAGIC` / `LIGHTNING` 120%と25%の `SHOCKED` を適用しますが、同じ発動では1体につき1回だけ命中します。メイジへの使用許可は `starter` node `1348` から与え、Administratorの検証用 `usableSkills` も維持します。交換ジェムは1ページ目・slot23へ無印原石3個で配置します。

`mage_frost_blizzard` は、視点方向へ前進して地形の手前で停止する、10秒持続の氷竜巻です。半径2.75m・最大8体へ0.5秒ごとに24%の `MAGIC` / `ICE` ダメージを与え、対象へ接線・中心・上方向のvelocityを合成して適用します。velocityはノックバック耐性で線形減衰し、耐性100では0になります。MP40、20秒クールダウン、1秒詠唱とし、メイジへの使用許可は `starter` node `1349` から与えます。Administratorの検証用 `usableSkills` も維持し、交換ジェムは1ページ目・slot24へ無印原石3個で配置します。

`mage_frost_ball` は、射程16mの氷球を最初のMobまたはBlockへ着弾させ、半径2.25m・最大4体へ氷属性の範囲攻撃を行うメイジ魔法です。MP12、4秒のクールダウン、0.2秒詠唱で、基礎倍率は45%、Lv.2〜5の `SKILL_DAMAGE_INCREASE +5%` により最大Lv.5の実効倍率は54%です。命中した対象へ75%の確率で `FROZEN` を付与し、設定持続時間はLv.1の2秒からレベルごとに2秒ずつ増加して最大Lv.5で10秒です。Bossも対象ですが、`FROZEN` は共通のCONTROL補正によりBossへの実適用時間が設定値の25%（最大Lv.5で2.5秒）になります。使用許可は `starter` node `1351` から与えます。Administratorの検証用 `usableSkills` も維持し、交換ジェムは1ページ目・slot26へ無印原石3個で配置します。

`mage_arcane_flow` は、魔法タグ付きスキルの成功履歴をプレイヤーごとに保持し、前回と異なる魔法スキルの詠唱時間を追加で短縮するバインド必須パッシブです。最大Lv.5で、Lv.1の5%からレベルごとに1.25%加算し、Lv.5で10%とします。初回、同じスキル、非魔法スキルでは短縮せず、詠唱に成功したスキルだけを履歴へ記録します。条件成立時は紫色の16点リング粒子を表示し、ダメージ、状態異常、リソース消費、クールダウンは追加しません。メイジへの使用許可は `starter` node `1347` から与え、Administratorの検証用 `usableSkills` も維持します。交換ジェムは1ページ目・slot25へ無印原石3個で配置します。

## ドッジ連動パッシブ

`administrator_just_dodge` は `passive.bindRequired: true` のドッジ連動パッシブです。成功したドッジから `params.invulnerabilityTicks` tick の間、`NORMAL_ATTACK` / `SKILL` の直接攻撃を無効化します。無効化回数に上限は設けず、状態異常DoTは `DamageService.applyConditionDamage` の専用経路であるため対象外です。

無効化時は既存のドッジパーティクルとシールドブロック音を表示し、同じドッジ中の最初の無効化時だけ `params.energyRecoveryAmount` のENGを回復します。Lv.1・最大Lv.1の設定は無効化時間8 tick、ENG回復量30です。ダメージを与えないためDPS算出対象外です。冒険者への使用許可は `starter` node `1350` から、ハンターへの使用許可は `starter` node `1355` から、Administratorへの検証用許可はclassから与えます。自動生成ジェムは `skill_gem_exchange` の1ページ目・slot20で無印原石3個から交換します。

`adventurer_lightning_bolt` は、通常時は単体へ `MAGIC` / `LIGHTNING` を適用し、命中対象から半径5m以内にいる別の `SHOCKED` 状態の Mob へだけ最大2体を連鎖させる。連鎖は距離順・UUID順、視線遮蔽なしの対象を選び、連鎖先からの再連鎖と本スキルによる `SHOCKED` 付与は行わない。

`adventurer_mana_burst` は前方扇形へ無属性の `MAGIC` ダメージを瞬間放出する基礎範囲魔法です。

## 条件付きリソース回復パッシブ

`adventurer_meditation` は、`passive.bindRequired: true` のバインド必須パッシブです。全職共通root node `1047` から使用許可を与え、冒険者classの直接許可からは除外します。YAML の `params.chargeTicks: 60`、`params.initialRegenMultiplier: 2`、`params.regenMultiplierIncrement: 0.5`、`params.activeDurationTicks: 140`、`params.buffId: buff:adventurer_meditation` は Plugin executor が固定値として検証し、スキルレベルでは変更しません。3秒の連続スニーク後、MP / ENG自然回復倍率を2.0から開始し、1秒ごとに0.5ずつ加算して7秒後に終了します。開始時は `buff:adventurer_meditation` の移動速度+120%バフを7秒付与し、スニーク解除・被弾・通常攻撃・他スキル使用などの中断時に強制解除します。`chargeParticleIntervalTicks` / `activeParticleIntervalTicks` は控えめな予兆・維持演出の間隔、`activeSoundIntervalTicks` は発動中の環境音の間隔だけを指定します。環境音は `World#playSound` / `SoundCategory.PLAYERS` で音源位置の周囲へ再生し、効果対象の自然回復倍率は自身のみです。

このスキルは最大値や固定回復量を持たず、Plugin の自然回復処理が持つ MP / ENG の既存回復量へ条件付き倍率を適用します。HP / Shield の自然回復量は変更しません。回復効果開始から7秒後に MP / ENG だけを最大値へ回復して終了します。スニーク解除、被弾、通常攻撃、他スキル使用で runtime 状態とメディテーションバフを破棄し、完了後にスニークを継続していても再発動せず、再発動にはいったん中断してから再度60 tickの継続が必要です。

## progression

標準的にジェムを入手できる段階、またはclass / skilltreeから使用許可を得る段階を記載します。装備・ルーン・セット効果はスキルを付与しません。

## 正本参照

- 戦闘・ゲームバランス: ダメージ、回復、防御、状態異常、コスト、クールダウンなど性能に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\30.features.skill\docs.skill.YAMLスキーマ定義.md`
- Plugin 設計: `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\13-skill`
