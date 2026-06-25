---
name: astralrecord-unused-properties-prune
description: `10_plugin/AstralRecord` の `player.properties` と `logger.properties` について、対応する `PlayerMsgId.java` / `LogId.java` と Java・Kotlin ソース全体を照合し、未使用定義の一覧化と削除を行う。properties のみにある定義、enum のみにある定義、enum と properties の両方にあるが enum ファイル以外から参照されていない定義を調べたいときに使う。
---

# AstralRecord Unused Properties Prune

`player.properties` と `logger.properties` の掃除専用 skill として扱う。
一覧検索は必ず同梱スクリプトで行い、その結果を根拠に AI が削除する。

## 対象

- `E:\AstralRecord-Workspace\10_plugin\AstralRecord`
- `src/main/resources/player.properties`
- `src/main/resources/logger.properties`
- `src/main/java/io/github/maaasu/astralRecord/feature/player/PlayerMsgId.java`
- `src/main/java/io/github/maaasu/astralRecord/infrastructure/logging/LogId.java`

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` と `E:\AstralRecord-Workspace\PLUGIN_GUIDE.md` を読む。
2. 次のスクリプトを実行して削除候補を取得する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-unused-properties-prune\scripts\find_unused_properties.py --root E:\AstralRecord-Workspace\10_plugin\AstralRecord
```

3. レポートを次の 3 区分で確認する。
   - `properties のみに存在`
   - `enum のみに存在`
   - `enum だけが接続点になっている定義`
4. 削除するときは対応をそろえる。
   - `properties のみに存在`: `*.properties` から削除する。
   - `enum のみに存在`: `PlayerMsgId.java` または `LogId.java` から削除する。
   - `enum だけが接続点になっている定義`: `*.properties` と enum の両方から同じキーを削除する。
5. 削除後に同じスクリプトを再実行し、対象キーがレポートから消えたことを確認する。

## 注意

- 一覧取得のために広い grep を手で繰り返さない。まずスクリプトを使う。
- 参照判定は `src/main/java` / `src/main/kotlin` を対象にしたキー文字列照合で行う。結果に違和感がある場合だけ周辺コードを追加確認する。
- 既存の番号帯コメントや並びは不用意に崩さない。
- この skill は削除対象の洗い出しと削除に集中し、無関係なメッセージ整理やリネームはしない。

## 出力

最終報告は日本語で行い、少なくとも次を含める。

- 削除対象キーの一覧
- 実際に削除したキーの一覧
- 再実行結果
- 実行した確認コマンド

## 追加オプション

- JSON が必要な場合:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-unused-properties-prune\scripts\find_unused_properties.py --root E:\AstralRecord-Workspace\10_plugin\AstralRecord --format json
```

- レポートを保存したい場合:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-unused-properties-prune\scripts\find_unused_properties.py --root E:\AstralRecord-Workspace\10_plugin\AstralRecord --write E:\AstralRecord-Workspace\work\unused-properties-report.md
```
