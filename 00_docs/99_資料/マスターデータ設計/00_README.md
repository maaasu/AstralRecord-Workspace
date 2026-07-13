# マスターデータ設計 README

このフォルダは、AstralRecord の本番向け filebase マスタを増やすための上位設計を置く場所です。

ここで扱う「世界観」や「背景」は、プレイヤーに強制閲覧させるストーリーではありません。開発側がコンセプト、命名、ステータス、報酬、敵配置の判断を揃えるための軽い設計材料として扱います。

## 正本の位置づけ

- filebase の YAML スキーマ正本は `E:\AstralRecord-Workspace\40_filebase` 配下の各 `YAMLスキーマ定義.md` です。
- ステータス種別の正本はプラグイン側 `StatusType` で、filebase 上の参照資料は `E:\AstralRecord-Workspace\40_filebase\00.meta\StatusType.md` です。
- このフォルダの文書は、どのようなマスタを作るか、どのような名前・数値・報酬に寄せるかを決める補助正本です。

## 文書一覧

| File | Role |
|:--|:--|
| `01_ゲームコンセプト.md` | AstralRecord の体験方針、既存 Web 表現から採用する軸 |
| `02_ステータスとバランス方針.md` | 初期レベル帯の数値感、ステータスの使い分け |
| `03_コンテンツ追加方針.md` | item / mob / loot / world などを追加する順番と粒度 |
| `04_命名規則と世界観メモ.md` | 命名、説明文、モチーフの軽いガイド |
| `05_初期オーバーワールド制作ブリーフ.md` | 最初のオーバーワールド向けにすぐ作れる制作指示 |
| `06_AI追加チェックリスト.md` | AI がマスタを追加する前後に確認する項目 |
| `07_コンテンツ拡張手順書.md` | 進行時間を伸ばすための追加順、作業単位、完了条件 |
| `08_ワールド制作と手動配置ガイド.md` | 実ワールド制作、稼働サーバーへのスポナー・NPC配置、確認と引き渡し手順 |

## AI への標準依頼

```text
$astralrecord-master-data-author を使って、E:\AstralRecord-Workspace\40_filebase に最初のオーバーワールド向けマスタを追加し、結果を報告してください。
```

追加対象を狭めたい場合は、次のように指定します。

```text
$astralrecord-master-data-author を使って、E:\AstralRecord-Workspace\40_filebase に level 1-8 の通常敵3体、素材4個、初期装備6個、対応する loot pool/table と spawner を追加し、結果を報告してください。
```

継続的に進行帯を増やす場合は、先に `07_コンテンツ拡張手順書.md` で対象 phase と追加単位を決めます。
