# AstralRecord リソースパック

Java Edition と Bedrock Edition にダメージ数値の専用アセットを配布します。アイテム、バニラ GUI、音声の旧アセットは保管のみとし、現在の配布物には含めません。

## 対象と構成

- Java Edition 1.21.11〜26.3（リソースパック形式 75.0〜97.1）: `pack.mcmeta` と `assets/astralrecord/` 内の `astralrecord:damage_number` フォント。縁付きの数字、カンマ、マイナス、会心・シールド記号を収録します。色と動きはサーバープラグインで設定します。
- Bedrock Edition: `bedrock/` 内の `astralrecord:damage_number` クライアントエンティティ。通常は赤、会心は金、シールド吸収は水色で表示し、カメラに向けて中央揃えします。会心は大きく跳ねて少し長く残り、シールドは登場時に横へ広がります。いずれも上昇しながら縮小し、最後に透明になります。

Bedrock の表示には [Geyser のカスタムエンティティ API](https://geysermc.org/wiki/geyser/custom-entities/) を使う AstralRecordGeyserExtension が必要です。エンティティプロパティの `astralrecord:length` は最大 17 文字、`astralrecord:style` は 0=通常、1=会心、2=シールド吸収、`astralrecord:size` は 0.5～2.0 です。`astralrecord:glyph_0` ～ `astralrecord:glyph_16` の値は 0～9=数字、10=カンマ、11=−、12=✦、13=◇、14=空白です。表示時間は通常とシールド吸収が 1.6 秒、会心が 1.8 秒です。

形式の参照先は [Java 1.21.11](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11)、[Java 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)、[Bedrock のレンダーコントローラー](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/render_controller.v1.8.0?view=minecraft-bedrock-stable)、[Bedrock のマテリアル](https://learn.microsoft.com/en-us/minecraft/creator/documents/material-files?view=minecraft-bedrock-stable) です。リポジトリ内の参照先は `resourcepack.config.json` にまとめています。

## ビルド

このディレクトリで以下を実行します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-resourcepack.ps1
```

`dist/AstralRecordResourcePack.zip`（Java）と `dist/AstralRecordResourcePack.mcpack`（Bedrock）を生成します。出力先は `-OutputDirectory <dir>` で指定できます。同じ入力ファイルからは同じバイト列を生成し、Bedrock の配布内容が変わるとパック UUID も変わります。生成したアーカイブはコミットしません。

字形の修正時は `scripts/generate-damage-number-assets.py` を Python で実行し、両版の PNG と関連 JSON を再生成します。通常のパックビルドに Python は不要です。

## サーバー配布

1. 初回は [Geyser Extension の導入手順](../10_plugin/AstralRecordGeyserExtension/README.md) に従い、Velocity の拡張を更新します。起動時と以後60秒ごとにAPIのBedrockパックを確認し、新しい接続へ配布します。
2. API・Web・Plugin を有効にした設定で [01-deploy-debug.bat](../60_tool/deploy-debug/README.md) を実行します。API の配置物に両パックと最新ハッシュを含めます。
3. Java 版はゲームサーバーから公開WebのダウンロードURLを受け取ります。配布先を変える場合は RPG Plugin の `resourcePack.managed.publicBaseUrl` を設定します。統合版は Geyser の接続時にパックを受け取ります。更新したパックの適用は再接続時です。

RPG Plugin の `resourcePack.bedrockNamePrefixes` は、Velocity の Floodgate の接続名接頭辞と一致させます。現行環境は `.` です。
