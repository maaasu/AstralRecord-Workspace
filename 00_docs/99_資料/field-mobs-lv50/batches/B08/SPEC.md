# B08 アカサソリ・スナヤモリ

専用branch `codex/field-mobs-lv50-b08`、worktree `E:/AstralRecord-Workspace/99_work/field-mobs-lv50-b08`。基点は作成時ローカルdevelop `3444b74b2`。中央登録と実配置は統合待ち。

## 所有と統合境界

`REGISTRATION_MANIFEST.json`の`owned_paths`が所有範囲。Executor2、B08専用の`MesaDebuffSkillSupport`、Mob2、素材3と本B08ディレクトリを追加する。素材予約枠270〜279のうち`10a00270/271/272`だけを使う。既存drop、共有loot、AstralRecord.java、registry、POM、properties、status/tag/schema、spawnerは変更しない。メサの三種目`mesa_vulture`はB09担当で、直接参照はない。

## 二種の使い分け

| 種 | 役割 | 固有技 | 対処 |
|---|---|---|---|
| アカサソリ | 標準寄りの耐久と短い毒の圧力 | 3.5m/180tick、24tick予告、長さ3.5m/半幅0.32mの固定直線、近接無属性85%と毒 | 直線から横へ避ける、遮蔽物へ隠れる |
| スナヤモリ | 低耐久・低火力・高移動速度の妨害役 | 5m/220tick、30tick予告、左右40度の固定扇、近接無属性35%と短い盲目 | 扇外/背後へ回る、距離を取る、遮蔽物へ隠れる |

両種とも通常近接2m/36tickを維持する。毒針は外枠二本と中央筋・終端横棒の27点、砂散らしは二辺・中央筋・外周弧の31点を4tickごとに一括描画する。予告開始と発動に別のサウンドを使い、形状・距離・予告時間・命中効果で識別できる。共有ParticleDisplayService/SharedParticleDefinitionsを再利用する。見た目と音の実client確認は未実施。

予告はcaster開始位置と対象方向を固定し、追尾しない。主対象の足元を固定形状と水平射程・高低差1.25m以内で判定する。主対象以外のplayer/petを巻き込まない。発動者も移動/瞬間移動させず、地形、砂ブロック、設置物を残さない。モデルはSPIDERのscale0.7とSILVERFISHのscale1.2。未対応の赤色variantや独自ヤモリモデルを要求せず、アイコンは対応するspawn eggを使う。

## 有限のデバフ

直接攻撃が回避されず、HPまたはShieldへ実ダメージがある時だけ、既存`ConditionService.applyCondition(ConditionApplyRequest)`へ渡す。基礎付与率100%に既存の付与率/耐性を反映するため、全対象への確定付与ではない。通常ノックバック・Shield・回避・無敵・状態耐性の共通規則を変えない。

毒は基礎70tick、20tick間隔、`SUPPORT_POWER`係数0.12。同APIの毒威力上限0.16以内。毒はATTACKのbasePowerを明示してもSUPPORT_POWER上限に抑えられるため、アカサソリの各レベルへATTACKの約半分のSUPPORT_POWERを定義する。毒のsnapshot基礎威力は284.64/309.36/334.32/359.04、通常時のパルスは20/40/60tick相当の最大3回で、70tick期限で失効する。処理負荷や実時間の差でパルスが減る場合があり、既存TickServiceへ委譲する。HP割合毒、独自DoT task、バニラPotionEffect直接付与は使わない。

砂は基礎30tickの`BLINDNESS`だけ。独自の移動禁止・減速・攻撃禁止・詠唱禁止を追加せず、毒も付けない。盲目の表示は既存ConditionDisplayServiceが残期限に合わせて扱う。バニラ盲目表示やGeyserでの見え方は実機で確認する。

両状態の期限には既存の持続延長を反映し、同一対象・同一ConditionTypeは既存APIの一件に更新される。強い方の効果を保ち、期限はmaxへ更新される。B08マスターは持続延長statusを持たず、独自の再付与ループはない。複数個体の交互命中による再付与は可能であるため、短い状態でも常時命中の密度は実戦受入で確認する。B09と共存するが、永久拘束を防ぐための共通ルール変更は加えない。

発動者のdespawn後も命中済みの毒/盲目は共通APIの有限期限まで残り得る。source消失を理由に他の同種効果まで削除しない。対象の死亡/ログアウト/召喚解除とPlugin停止の解除は既存のclearAll/clearAllRuntimeStateを使い、B08に残留Map/解除用taskを作らない。

## 中断とcleanup

開始時と毎tick、Plugin enabled、管理個体の同一性、COMBAT、生存、主対象ID、共通isMobCombatTarget、caster/target/originの同一world、行動/攻撃/詠唱可能を検査する。無効player、creative/spectator、召喚解除petを共通境界で拒否する。二重castも拒否する。

通常AIを予告中だけ停止し、開始位置から0.6mを超える外部移動で中断する。高低差、WorldBorder、world高さ、読込済みchunkを確認する。block rayの全区間を0.25m以下で事前検査してから遮蔽物判定し、未読込chunkをロードしない。paramsは空、射程は0.5m〜既定以下、CDは既定以上、castTimeTicksは0だけを許可する。

自然終了、帰還、対象変更/消失、world変更、死亡/despawn、Plugin停止、例外で冪等cleanupを使う。ダメージ処理前にMapから実行を除去し、taskをcancel、通常AI停止を解除して再入による二重命中を防ぐ。中央destroy listenerとonDisableのstop接続が必要で、manifestだけの段階を登録・停止連携済みとは扱わない。

## 数値根拠

正本`02-成長曲線.md`の公式アンカーはLv30まで。B06/B07の仮想標準をLv38以降へ線形に延長し、標準HP29000/31500/34000/36500、Mob R5270/5730/6190/6650、Player R5100/5550/6000/6450・HP14300/15500/16700/17900・DEF5270/5730/6190/6650を比較条件に置く。アカサソリはHP0.9/R0.9/Player R比DEF0.85、スナヤモリはHP0.75/R0.7/Player R比DEF0.65を丸める。SUPPORT_POWERだけで通常攻撃のATTACKは上がらない。

| 種/Lv | HP | ATTACK | DEFENSE | SUPPORT_POWER | EXP |
|---|---:|---:|---:|---:|---:|
| 蠍38 | 26100 | 4743 | 4335 | 2372 | 650 |
| 蠍40 | 28350 | 5157 | 4718 | 2578 | 698 |
| 蠍42 | 30600 | 5571 | 5100 | 2786 | 746 |
| 蠍44 | 32850 | 5985 | 5482 | 2992 | 794 |
| ヤモリ38 | 21750 | 3689 | 3315 | — | 620 |
| ヤモリ40 | 23625 | 4011 | 3608 | — | 664 |
| ヤモリ42 | 25500 | 4333 | 3900 | — | 708 |
| ヤモリ44 | 27375 | 4655 | 4192 | — | 752 |

命中95/回避3、移動速度は蠍100/ヤモリ115。mapping再帰継承、list全置換の既存形式に従い、上位レベルのbaseStatsを全列挙する。drops.expだけの上書きは共通素材dropを継承する。4レベル・2刻み・幅6・Lv50以下を満たす。`progression: 38`は同帯パッケージに揃える相対的遭遇段階であり、公式のPlayerLv対応表ではない。

防御倍率`max(0,1-(D/R)/2.5)^1.35691545`、Player最終倍率1.30・命中0.92・主力skill込みDPSを通常の1.6倍、Mob命中0.915・通常間隔1.8秒と置く。蠍TTK4.68〜4.70秒/通常近接耐久13.06〜13.17秒、ヤモリTTK3.34〜3.35秒/通常近接耐久23.88〜24.08秒。固有技、毒、盲目、予告中の通常攻撃停止、会心、Shield、複数個体、操作時間を除く仮想値であり、実測ではない。

B06/B07の現行代表構成のR約416.58ではB08の全DEFが完全防御閾値1041.45を超える。公式Lv31以降の装備/class/skilltreeを決めてTTK/耐久を再試算するまで実配置と戦闘バランス受入を保留する。既存world/spawnerにメサの実配置根拠も確認できず、world名・座標を作成しない。

## 素材と経済

蠍は`10a00270`アカサソリの毒針35%×1（COMMON/550G）。ヤモリは`10a00271`砂ヤモリの皮35%×1（COMMON/420G）と`10a00272`砂ヤモリの砂粒8%×1（UNCOMMON/620G）を独立抽選する。幸運補正あり、図鑑公開、64stack、取引/売却可能。Gold直付けや共有tier loot追加はない。幸運なし売却期待値は蠍192.5G/ヤモリ196.6Gで近い値にする。狩猟速度とGold sinkを含む実測は統合後。

新素材のrecipe/装備用途は別担当とし、Loreには実装済みの商人売却だけを書く。毒針のPOINTED_DRIPSTONE、皮のRABBIT_HIDE、砂粒のSANDは既存使用済みだが、細く鋭い針・動物の薄皮・砂そのものに直接対応するため再利用する。Resource Packの変更は不要。

## 統合担当の工程

manifestに従い中央registry/destroy/onDisableを接続し、SUPPORT_POWERを含む8個体と3素材のmaster読込、2つのMob skill IDを確認する。supportクラスも同時に取り込む。全体build/test、版番号、他batch後の全ID/変更参照再検証は統合担当がまとめて行う。実機では形状の判別、横/背後/壁回避、player/pet、回避/Shield/無敵/耐性、毒パルス/盲目期限、全キャンセル条件、B09との複数個体同時発動、素材の抽選・売却を確認する。
