# AstralArchitect Development Guide

`10_plugin/AstralArchitect/`は、Codexが編集する局所建築チケットとMinecraftワールドの間を安全に接続する独立Paperプラグインです。

## Read Next

- 利用手順、チケット契約、状態遷移: `README.md`
- Codexによる候補編集: `$astralarchitect-builder`
- 共通Java実装規約: ルート`PLUGIN_GUIDE.md`と`$astralrecord-code`

## 固定方針

- `FastAsyncWorldEdit` 2.15.2を必須依存とする。
- Java 21 / Paper 1.21.11を対象とする。
- プラグインはAI設計を行わず、範囲保存、候補検証、差分適用、ロールバックを担当する。
- `source.schem`は不変とし、Codexは`candidate.schem`だけを専用CLI経由で編集する。
- 適用時にもプラグイン側でハッシュ、形式、差分、現在ワールドとの競合を再検証する。
- プレイヤー操作はクリエイティブモードと権限を必須とする。
- 新規public型・publicメソッドには日本語JavaDocを付ける。

## 検証

```powershell
mvn clean test package
python -m unittest discover -s tools -p 'test_*.py'
```

実サーバーへ配置する場合は`60_tool/09-astralarchitect-build-deploy.bat`を使います。実行入口は`60_tool`で管理し、スクリプトはサーバーを停止・再起動しません。
