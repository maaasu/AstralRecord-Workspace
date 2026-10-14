# B03 ハラノヒツジ・カマキリソード

共通基点 `e2058ade10a682118382e70aa01c7db758ac81c6`。所有は2種のenemy、素材10a00220〜222、専用Executor2つ、本B03資料だけ。中央登録・共有基盤・properties・POM・既存loot・spawner・管理表は編集しない。独占予約10a00223〜229は未使用のまま保持する。

| 種 | levels | 役割 | 素材 |
|---|---|---|---|
| ハラノヒツジ | 28,30,32,34 | 少し丈夫な地上近接。角の直線を横へ避ける | 野原の羊毛35%×1、硬い巻き角7%×1 |
| カマキリソード | 42,44,46,48 | やや攻撃寄りの地上近接。扇形から離れるか背後へ回る | カマキリの刃35%×1 |

両方ENEMYで、強敵・ボスではない。素材は独立抽選・幸運補正あり・図鑑表示あり。専用loot/buffは不要。共有tier_2を新高Lvへコピーせず直接dropsだけにする。通常35%は既存Lv15グラスボアの毛皮35%と同じ。希少角はLv7森狼の希少3〜5%より上、計画初期10%より下の7%を採用。低位の羊も素材2種を持つため供給量を抑え、装備側と調整可能な値として記録する。素材期待売却額は羊0.35×90+0.07×280=51.1、カマキリ0.35×160=56。直金は追加しない。

アイコンはSHEEP/SILVERFISHの対応spawn egg。カマキリは現行Vanilla虫モデルをscale2で表し、専用Resource Packは作らない。実際の見え方は未確認。羊毛WHITE_WOOL、巻き角GOAT_HORN、刃SHEARSは素材そのものの形・用途に一致するため、使用済みiconの再利用を許容。素材loreでは未実装recipeを成立済みと説明しない。progressionは既存平原Lv18〜30の16を羊と羊毛に採用、希少角17、独立した高位遭遇段階をカマキリ/刃20とする。レベル数とprogressionを同一視しない。

## 固有技

| 技 | 発動 / CD | 頭上詠唱＋固定領域予告 | 命中・効果 |
|---|---|---|---|
| 角突き | 3.6m / 100tick | 12tick＋12tick、細い直線の両端を12点で表示 | 主対象だけ、hitboxを水平0.45m/垂直0.1m拡張した前方3.6m水平ray、近接無属性115%を1回 |
| かま振り払い | 3.6m / 120tick | 12tick＋20tick、120度扇形を21点で表示 | 半径3.6m/前方120度、最短水平hitbox点で判定。近い順に最大4体、近接無属性110%を各1回 |

Directionは共有MobSkillContextの詠唱開始時、中心はExecutor開始時の実体足元に固定し、予告後の追尾をしない。予告中はscriptedActionで通常AI/通常攻撃を停止し、stopPathfindingも直ちに呼ぶ。中心から0.5m超のノックバック/外部移動で中断する。両技とも地上標的で高低差1.25m以内。角突きは壁までのray、振り払いは対象胴体までのrayで遮蔽物を確認する。FluidCollisionMode.NEVER/ignorePassableBlocks=trueは既存森スキルと同じ。振り払いの境界はhitbox最短点を代表点に使うため、角だけが扇形へ入る場合の全形状交差は判定しない。

DamageService.attack(MELEE, NONE, SKILL)で命中・回避・防御・シールドの共通処理を維持する。DamageServiceの候補取得/有効性判定で、生きたゲームプレイヤーとその召喚ペットだけを対象にする。敵・NPC・管理モード・死亡プレイヤーは含めない。振り払いは各対象への攻撃前にcasterの存続・COMBAT/HP/行動可能性・world・中心を再確認し、同期反撃で倒れた場合も後続対象を攻撃しない。効果開始後の主対象撃破だけでは他の有効対象への振り払いを中断しない。通常攻撃はrange2、羊32tick/カマキリ30tickで維持。高位の攻撃力増加を通常攻撃速度の増加で重ねない。

予告taskは同期4tick間隔、最大3/5回、粒子は閲覧者を中心ごとに一括取得する。各tickはCOMBAT/同一Mob instance/HP/実体/主対象UUID/対象有効性/同一world/canRunAi/canCastSkill/canAttackを確認する。死亡・despawn・leash・target消失/変更・world変更・行動不能で中断、満了/例外でもfinally相当のfinishでtask/map/scriptedActionを解除する。root（移動不能）は静止攻撃なので許容する。clearCasterStateは破棄したinstanceそのものを保持して解除でき、重複解除は無害。Plugin停止は専用stop()必須。登録とdestroy/stop配線は[REGISTRATION.json](REGISTRATION.json)に渡す。

## 数値根拠と未確認の境界

正本 `00_docs/60_戦闘バランス設計書/02-成長曲線.md` §3.1/4.1、既存 `enemy/v1.eriva_camouflage_zombie.yml` のLv28=16600/3007/2896、Lv30=19000/3442/3318（HP/R/D）に照合。Lv28〜30はLv25〜30アンカーの補間。Lv32以上の標準アンカーは存在しないため、HP+1200/R+217.4/D+210.8 per levelを暫定延長する。羊はHP×1.1/R×0.95/D×1.05、カマキリはHP×0.9/R×1.05/D×0.95。STR等を追加して二重に攻撃倍率を加えない。MobのRはATTACKのみで表す（MELEE_ATTACK/STRENGTHなし）。

| Mob / Lv | HP | ATTACK=R | DEFENSE=D | EXP |
|---|---:|---:|---:|---:|
| 羊28 | 18260 | 2857 | 3041 | 400 |
| 羊30 | 20900 | 3270 | 3484 | 440 |
| 羊32 | 23540 | 3683 | 3927 | 480 |
| 羊34 | 26180 | 4096 | 4369 | 520 |
| カマキリ42 | 30060 | 6353 | 5555 | 680 |
| カマキリ44 | 32220 | 6810 | 5956 | 720 |
| カマキリ46 | 34380 | 7266 | 6356 | 760 |
| カマキリ48 | 36540 | 7723 | 6757 | 800 |

EXPは既存迷彩ゾンビのLv28→30の+20/levelを延長した暫定値。経済全体の実測は未実施。Lv30超装備は現行マスタで確認できない。ソードマンclassはbase HP120/STR8/ATTACK10/DEF7、growth HP7/STR1.2/ATTACK1.2/DEF0.8 ×(classLevel−1)。エリヴァ剣20a00073はrequiredLv15、MELEE_ATTACK5〜115/STR0〜9、角兜20a00086はDEF24〜32/HP120。これらだけでPlayer Lv30以降のアンカー性能を再現できるとは判断しない。

試算はPlayerアンカーHP=Mob標準HP/2、PlayerR=Mob標準D、PlayerD=Mob標準Rを同じ傾きで延長し、命中Player92%/Mob91.5%、Player最終130%、skill込みDPS×1.6と仮定する。防御倍率は実 `DamageCalculator.java` / `01-戦闘計算.md` に従い `(1-D/R/2.5)^1.35691545`、D/R>=2.5なら0。防御比の分母はratio前のresolvedAttackPower=Rで、実ダメージはR×ratio×防御倍率となる。技ratioで防御比の分母を増やさない。

TTK/耐久の計算値は[VALIDATION.md](VALIDATION.md)へ記録する。実戦では頭上詠唱＋予告が通常攻撃を停止し、横回避も可能なため、耐久は常時通常攻撃DPS＋CDごとの技DPSという保守的な下限を併記する。実戦・装備構成・盾・skill操作・ペット・粒子OFFでの視認性は未検証。全体build/testと実戦を済んだ扱いにしない。

配置は平原B候補の既存world `vanaheim_supercontinent` と迷彩ゾンビLv28/30spawnerのregion「エリヴァ大草原」を確認しただけ。平原B/Cの具体的位置は未確認。新規spawnerと設置座標は作らず、統合担当に保留する。素材の消費先は装備タスクへ引き渡す。
