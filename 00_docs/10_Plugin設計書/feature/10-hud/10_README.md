# 10_README

このディレクトリは `feature/hud` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/hud/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/hud/view/*`

## ドキュメント一覧（推奨順）

1. [[10_0.00-概要]]
2. [[10_3.00-索引]]

## 依存 feature

- `status`
  - [[07_1.00-モデル定義]].ステータススナップショット の HP/MP/EN を表示に利用する。
- `player`
  - [[03_1.00-モデル定義]].プレイヤーキャッシュ からオンラインプレイヤーを走査する。
- `account`
  - [[02_1.00-モデル定義]].アカウントモード に応じて ActionBar 表示有無を切り替える。
- `world`
  - 現在ワールドの表示名・種別と地域既定値を Sidebar に利用する。
- `mob` / `spawner`
  - オーバーワールドの現在地域とスポナー出現 Mob の平均レベルを Sidebar に利用する。
- `boss`
  - ボスフィールドの地域レベルと挑戦情報を Sidebar に利用する。

## 更新ルール（変更時に必ず更新する章）

- HUD更新周期・更新対象プレイヤーの変更:
  - [[10_3.02-サービス]]
- ActionBar / Sidebar / TabList / バー表示構成の変更:
  - [[10_3.07-View]]
  - [[10_0.00-概要]]
