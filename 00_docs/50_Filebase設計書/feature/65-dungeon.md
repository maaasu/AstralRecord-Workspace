# Dungeon 設計

## 役割

Dungeon は、入場条件、道中、節目、boss、報酬、退出条件を一連の攻略単位として束ねます。

## 設計方針

- 想定人数、所要時間、失敗条件、再挑戦条件、主な報酬を定めます。
- 道中と boss に、事前準備した装備・消耗品・skill を確認する役割を持たせます。
- dungeon 外の進行必須品を、極端な低確率報酬だけに依存させません。
- 個別 dungeon のテーマや部屋構成は個別定義で扱います。
- `generation.roomTypes` は `STANDARD` / `SUPPORT_HALL` / `COLLAPSED` / `ORE_CHAMBER` を正の相対weightで指定します。省略時は `STANDARD` だけを使用します。
- `theme.lightMaterial` と `theme.decorations` で照明、木製support／beam、rubble、ore／accentを汎用設定します。中央導線、gate、player／Mob spawnを塞がないことをPluginが保証します。
- 個別調整が不要な dungeon は受付地点と Mob 参照だけの最小構成とし、生成寸法・Material・照明・部屋タイプ装飾・柱・人数には Plugin の安全な既定値を使います。
- `challenge.deathLimit` は0以上の死亡許容回数です。設定回数までは復帰可能で、次の死亡で挑戦終了となるため、難易度意図と合わせます。
- `clearRewards` はクリア時点で Dungeon world 内にいる参加者ごとに独立抽選されます。直接 item と loot table を利用でき、内部では設定確率の低い当選からsortします。実報酬GUIは確率を表示せず、設定数量・確率の確認はカルトグラフarchiveだけに限定します。
- 省略可能値の既定値はキーを省略した場合だけ使い、明示した型違い・境界外の死亡設定・確率・数量は補正せず load／公開を拒否します。
- クリア報酬は30秒以内に受け取らなければ破棄されるため、必須進行品を未受取のまま失う構成にしないでください。
- `twilight_mine` は deepslate／tuffを基調に、spruce支柱、soul-light、rubble、deepslate ore accentと4種のroom typeを設定します。

## progression

標準攻略段階を記載します。準備コンテンツは `P-1` から `P`、道中と boss は `P`、更新報酬は `P` から `P+1` を基準にします。

## 正本参照

- 戦闘・ゲームバランス: Mob構成、難度、想定人数、攻略時間、報酬に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- database 登録先: `40_filebase/config.yml` の `dungeon`
- YAML スキーマ: `40_filebase/65.features.dungeon/docs.dungeon.YAMLスキーマ定義.md`
- Plugin の生成・進行契約: `00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md`
- 個別 dungeon YAML はモチーフ、攻略段階、参照 World/Mob が決まってから作成します。スキーマ例は本番データとして読み込みません。
