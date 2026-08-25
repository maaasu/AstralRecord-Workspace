# Filebase 設計書

## 1. 目的

この設計書は、`40_filebase` に作成するマスタデータの共通コンセプトと、カテゴリごとに「どのようなマスタを作るか」を定義します。

個別ワールドの設定、特定エリアの敵構成、具体的なアイテム名、個別マスタの数値は扱いません。これらは各マスタファイルと、ファイル形式ごとの設計メタデータで管理します。

## 2. 正本

| 対象 | 正本 |
|:--|:--|
| マスタファイルの構造、必須項目、参照形式 | `E:\AstralRecord-Workspace\40_filebase` 配下の各 `docs.<項目名>.YAMLスキーマ定義.md` または `schemas/*.schema.json` |
| ステータスID・日本語名・表示メタデータ | `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml` |
| タグID・日本語名・説明・適用対象 | `E:\AstralRecord-Workspace\40_filebase\76.shared.tag\v1.tags.yml` |
| Plugin が解釈する列挙値や動作 | `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main` 配下のソースコード |
| Plugin の機能仕様 | `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書` |
| 戦闘ステータス、効果量、敵強度、装備更新、報酬量の数値根拠 | `E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` |
| マスタカテゴリの設計方針 | 本設計書の `feature` 配下 |
| 個別マスタの定義 | `E:\AstralRecord-Workspace\40_filebase` 配下の各 YAML / JSON |
| 個別マスタのモチーフと進行度 | YAML の先頭コメント、または対象 JSON Schema が定義する設計メタデータ |

ステータスとタグは複数言語で共有するため、filebaseの共有カタログを正本とし、Plugin/API/TypeScriptの型・定数を生成します。それ以外のPlugin固有列挙値は参照先のソースコードを確認します。

## 3. 共通コンセプト

- 各マスタには、ゲーム内で担う役割を1つ以上定めます。
- 同じ進行度のマスタ同士は、入手元、消費先、攻略対象、報酬先のいずれかで緩く接続します。
- 個別ワールドや物語へ強く依存する説明を共通設計へ持ち込みません。
- 数値は進行度、カテゴリの役割、レアリティ、入手難度の順に根拠を持たせます。
- ステータスID・表示メタデータとタグID・用途は共有カタログ、実装可能な挙動はPluginソース、記述形式は対象カテゴリのYAMLスキーマまたはJSON Schemaを正とします。

## 4. 設計メタデータ

各本番向け YAML の先頭には、次の形式でモチーフと進行度を記載します。このコメントは Plugin/API から参照しません。

```yaml
# design:
#   motif: "北欧神話 / 世界樹"
#   progression: 12
```

JSON はコメントを持たないため、対象 JSON Schema に `design` などの設計メタデータが定義されている場合だけ、そのフィールドへ記載します。スキーマに設計メタデータがない JSON へ未定義プロパティを追加せず、カテゴリの feature 設計書で設計意図を管理します。

### motif

- 元にした題材を、後から見ても変わらない短い語で記載します。
- ワールド内での用途、入手場所、将来予定、現在のバランス事情は記載しません。
- 題材の選び方と絞り方は `モチーフ選定ガイド.md` を参照します。

### progression

- 0 以上の整数で、標準的な入手順または遭遇順を表します。
- `0` は進行に属さないシステム用・管理用マスタにだけ使用します。
- `1` 以上はゲーム開始後の相対的な段階です。上限は固定しません。
- 1差は「次の標準的な更新・攻略段階」を表し、プレイヤーレベルとは一致させません。
- 同じ役割のまま数値だけを調整する場合、進行度コメントは変更しません。
- 進行上の役割そのものを変更する場合は、既存マスタの意味を上書きせず、新しい ID の作成を基本とします。

進行度 `P` の標準関係は次を基準にします。

| 対象 | 標準的な進行度 |
|:--|:--|
| モブと戦うために事前入手する装備・消耗品 | `P-1` から `P` |
| モブ | `P` |
| モブが通常ドロップする素材 | `P` |
| モブ由来の更新装備・希少報酬 | `P` から `P+1` |
| 素材から作る標準装備・消耗品 | 主素材と同値から `+1` |

この関係は機械的な計算式ではありません。カテゴリ固有の役割や入手難度により前後させる場合は、作成時チェックリストで理由を確認します。

## 5. 文書構成

feature 設計書の先頭番号は、原則として `40_filebase` のディレクトリ番号に合わせます。番号を持たない下位カテゴリは、親カテゴリの後に独立した番号を割り当てます。item 配下は `10-item` に続く `11` から連番にします。mob 配下は既存の上位カテゴリ番号との衝突を避け、空いている `44`、`46`、`49` を順に使用します。

```text
50_Filebase設計書/
├─ README.md
├─ feature/
│  ├─ 05-mail.md
│  ├─ 09-guide.md
│  ├─ 10-item.md
│  ├─ 11-bundle.md
│  ├─ 12-enchant.md
│  ├─ 13-orb.md
│  ├─ 14-consumable.md
│  ├─ 15-currency.md
│  ├─ 16-equipment.md
│  ├─ 17-material.md
│  ├─ 18-rune.md
│  ├─ 19-set_effect.md
│  ├─ 20-class.md
│  ├─ 30-skill.md
│  ├─ 35-skilltree.md
│  ├─ 40-mob.md
│  ├─ 41-mob_spawner.md
│  ├─ 42-gathering.md
│  ├─ 43-gathering_spawner.md
│  ├─ 44-boss.md
│  ├─ 45-shop.md
│  ├─ 46-enemy.md
│  ├─ 47-quest.md
│  ├─ 48-quest_board.md
│  ├─ 49-npc.md
│  ├─ 60-world.md
│  ├─ 65-dungeon.md
│  ├─ 70-buff.md
│  ├─ 75-status.md
│  ├─ 76-tag.md
│  ├─ 80-loot.md
│  └─ 85-recipe.md
├─ モチーフ選定ガイド.md
└─ 作成時チェックリスト.md
```

## 6. 作成時の読み順

1. 本 README
2. 対象カテゴリの `feature/<number>-<category>.md`
3. 対象カテゴリの `docs.<項目名>.YAMLスキーマ定義.md` または `schemas/*.schema.json`
4. ステータス、効果量、敵強度、出現密度、報酬量、装備更新などの数値を扱う場合は `00_docs/60_戦闘バランス設計書/README.md` と該当資料
5. feature 文書に記載された Plugin ソースまたは Plugin 設計書
6. `モチーフ選定ガイド.md`
7. 近い既存 YAML / JSON
8. `作成時チェックリスト.md`
