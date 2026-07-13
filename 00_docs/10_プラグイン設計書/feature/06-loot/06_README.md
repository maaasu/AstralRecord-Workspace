# 06_README

このディレクトリは `feature/loot` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/loot/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/loot/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/loot/model/*`

## ドキュメント一覧（推奨順）

1. [[06_0.00-概要]]
2. [[06_1.00-モデル定義]]
3. [[06_2.00-ユースケース]]
4. [[06_3.00-索引]]
5. [[06_4.00-統合フロー]]
6. [[06_5.00-例外・ログ・運用]]
7. [[06_9.00-未決事項]]（必要時）

## 依存 feature

- `item`
  - `ItemStackFactory` のバンドルルートロア追加（[[04_3.02-サービス]].プロトタイプテンプレート構築 内の内部処理、物理名: `appendBundleLootLore`）で [[06_3.02-サービス]].ロード済みルート取得 を参照する。
  - `ItemBundle.lootTableId` が本 feature のキャッシュキーとなる。
- `mob` / `gathering`
  - `MobDropService` が `drops.lootTable` をキャッシュから解決し、[[06_3.02-サービス]].ルートテーブル抽選 の結果を直接ドロップへ結合する。

## 更新ルール（変更時に必ず更新する章）

- ルートテーブル取得・キャッシュ処理の処理順変更:
  - [[06_3.02-サービス]]
  - [[06_4.00-統合フロー]]
  - [[06_3.04-リポジトリ]]（API 入出力が変わる場合）
- [[06_1.00-モデル定義]].ルートテーブル / ルートエントリ 項目追加・削除:
  - [[06_1.00-モデル定義]]
  - [[06_3.04-リポジトリ]]
- 抽選方式または利用 feature を変更した場合:
  - [[06_3.02-サービス]]
  - [[06_4.00-統合フロー]]
  - 関連先 feature の設計書
- ログIDや障害対応手順の変更:
  - [[06_5.00-例外・ログ・運用]]
  - [[06_9.00-未決事項]]（未確定事項がある場合）

## 2026-05-27 実装反映

- plugin の `LootRepository` は API の loot table / loot pool を結合して `LootModel` を構築する。
- `loot_table:` 接頭辞付き参照を正規化して解決する。
- item 側の bundle 開封と lore 表示は、この解決済み loot 構造を利用する。

## 2026-07-13 実装反映

- bundle 内にあった抽選を `LootRollService` へ移し、content ごとの独立 `rate`% 判定と `pick` 上限制御を共通処理とした。
- Mob と採集の `drops.lootTable` は起動時ロード済みキャッシュを参照し、直接ドロップと結合する。
- `drops.items[].luckAffected` はキラーの最新 `LUCK` ステータスを用いて補正する。
- `rolls` / `pick` の範囲は min/max をモデルに保持し、実行ごとに閉区間で抽選する。
- loot キャッシュは非同期構築後に不変 Map として原子的に公開する。
