# B09 メサハゲワシ・ヒダネナメクジ

基点`3444b74b2`、branch `codex/field-mobs-lv50-b09`、worktree `E:/AstralRecord-Workspace/99_work/field-mobs-lv50-b09`。所有はmanifestのJava3、Mob2、素材2、B09資料のみ。中央登録、管理表、POM、共有カタログ、properties、既存drop、loot、world/spawnerは統合担当の所有として編集しない。

| 種 | Lv | 戦闘と対処 |
|:--|:--|:--|
| メサハゲワシ | 38/40/42/44 | 7mを保つ落石専門。対象足元の半径1.6mの円を30tick予告、10tick下降後に遠隔無属性120%を一回だけ判定。開始時の落下地点を固定し、円外へ避ける。射程10m/CD180tick。 |
| ヒダネナメクジ | 44/46/48/50 | 2m/40tick通常近接に加え、長さ5m/幅1.3mの固定帯を30tick予告、80tick燃やす。魔法炎35%を発火時と20/40/60tick後の最大4回判定。帯外へ離れる。射程5m/CD240tick。 |

どちらも主対象player/召喚pet一体だけを共通isMobCombatTargetで判定し、通行人を巻き込まない。予告、着弾/発火に別サウンドを使う。落石は25点の固定円と中心、4m→2m→着弾の石粒子。火の道は33点の帯輪郭/中央筋と11点の炎。形状と有限時間で識別する。ParticleDisplayServiceの一括閲覧者解決と既存SharedParticleDefinitionsを再利用し、5tick間隔で描く。実clientの視認性・サウンド・Geyserは未確認。

落石の予告中40tick、火の道の予告中30tickだけ通常AIを停止し、開始位置から0.75mを超える外部移動で中断する。火の道は発火時に通常AIを再開するが、炎は固定位置を保つ。casterを移動/teleportさせない。PARROT GRAY/scale1.3とSILVERFISH/scale1.4の既存モデルを使い、鳥の空中AIは追加しない。アイコンは対応するspawn egg。

## 重複とcleanup

同casterの実行は一件だけ。火の道の各segmentを個別に攻撃せず、帯の包含判定一回から攻撃する。全Slugで一つのExecutorを共有し、対象UUIDごとに直前の試行tickを保持する。同じ対象への炎は複数caster/帯が重なっても20tickに一試行まで。先に窓を予約するため、回避/無敵/Shieldでも重複した再試行をしない。先にschedulerが実行された炎が窓を占め、威力の加算や最大値選択は行わない。期限後に窓を除去し、最後の炎終了またはstopでMapを空にする。燃焼Condition、独自DoT、バニラfireTicks、置き火、ブロック、落下entityは作らない。

発動時/5tickごとにPlugin enabled、管理個体の同一性、HP、COMBAT、主対象ID、player/pet有効性、同一world、行動/攻撃/詠唱可能を確認。帰還、target変更/死亡/logout/召喚解除、caster死亡/despawn/world変更、行動不能、無効地形、自然満了、例外で冪等cleanupする。同期Schedulerのみ。中央destroy listenerへhandleMobDestroyed、依存service停止前のonDisableへstop接続をmanifestに記載する。中央接続済みとは扱わない。

地上戦の高低差は1.25m以内。平坦な足場だけに設置し、各足場の高低差0.05m以内を要求する。world高さ/WorldBorder/読込済みchunkを検査後、0.25m以下の線分検査とblock rayで壁・液体を拒否する。落石は固定地点上4mが空いている必要がある。地形変化やchunk unloadで中断し、chunkをロードしない。paramsは空、射程は0.5m〜既定以下、CDは既定以上、castTimeTicksは0。

## 数値と素材

正式な成長曲線アンカーはLv30まで。B08の仮想標準（Lv38 HP29000/Mob R5270/Player R5100、2Lvごとに+2500/+460/+450）をLv50まで線形に延長した暫定値とする。ハゲワシはHP0.8/Mob R0.9/Player R比DEF0.7、ナメクジはHP1.1/Mob R0.55/Player R比DEF0.9で整数に丸める。ナメクジのMAGIC_ATTACKはATTACKと同値で、炎が魔法参照のまま機能する。正式なLv31以降の装備/class/skilltree基準と実TTK確認まで配置・バランス受入を保留する。

| 種/Lv | HP | ATTACK | MAGIC_ATTACK | DEFENSE | EXP |
|:--|--:|--:|--:|--:|--:|
| 鳥38 | 23200 | 4743 | — | 3570 | 660 |
| 鳥40 | 25200 | 5157 | — | 3885 | 708 |
| 鳥42 | 27200 | 5571 | — | 4200 | 756 |
| 鳥44 | 29200 | 5985 | — | 4515 | 804 |
| ナメクジ44 | 40150 | 3658 | 3658 | 5805 | 800 |
| ナメクジ46 | 42900 | 3910 | 3910 | 6210 | 850 |
| ナメクジ48 | 45650 | 4164 | 4164 | 6615 | 900 |
| ナメクジ50 | 48400 | 4417 | 4417 | 7020 | 950 |

命中95/回避3、移動速度は鳥110/ナメクジ80。levelsのmapping再帰継承とlist全置換に合わせ、上位baseStatsを全列挙し、EXP上書き後も共通dropを継承する。progression 38/44は相対的遭遇段階であり、公式のplayerLv対応表ではない。

仮想Player R=5100+450×((Lv-38)/2)、HP=14300+1200×((Lv-38)/2)、DEF=5270+460×((Lv-38)/2)とし、防御倍率max(0,1-(D/R)/2.5)^1.35691545、Player最終1.30×命中0.92×主力技1.60倍で試算すると、鳥のTTK3.69〜3.71秒、ナメクジ5.94〜5.96秒。敵の命中0.915、鳥の落石120%/9秒、ナメクジ通常/2秒+炎140%/12秒がすべて命中すると仮定し、Player MAGIC_DEFENSEも仮想DEFと同値に置いた耐久時間は鳥54.43〜54.87秒、ナメクジ50.27〜50.53秒。通常AI停止/実際の回避移動/会心/Shield/複数種の同時攻撃/魔法属性補正は含まない仮想比較で、実測ではない。現行代表構成R416.58では全DEFが完全防御閾値1041.45を超えるため、正式な上位Player構成の確定が必要。

`10a00280`メサハゲワシの羽はCOMMON/600G/35%×1、`10a00281`ヒダネナメクジの粘液はCOMMON/700G/35%×1。幸運補正、図鑑公開、64stack、売却/取引可能。売却期待値は210G/245G。recipe/装備用途は別担当、Loreは実装済み商人売却だけを書く。FEATHER/SLIME_BALLは既存使用済みだが羽そのもの・粘液へ直接対応するため再利用する。Resource Packの追加は不要。共有tier loot/Gold直付けなし。

## 統合と受入

manifest通り中央でExecutorを一度ずつ生成/登録し、destroy/onDisableを接続する。supportも同時に取り込む。全ID/参照、8個体のmaster読込、2skillの登録、編集サイト動的表示、全体build/test、最終版番号は統合担当がまとめて確認する。

実clientで固定円の予告→下降→着弾、固定帯の予告→80tick燃焼→消失、円/帯外・壁・高低差の回避、4回上限、複数Slugの20tick共通窓、player/pet、Shield/回避/無敵を確認する。全中断条件とPlugin停止後のtask/AI停止/Map消去、未読込chunk、境界、天井、地形変化も確認する。実配置world/座標を確認するまでspawnerを作らない。
