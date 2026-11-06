# バニラのアイテム表示

`scripts/item-models.ps1` は `custom_model_data` が一致しないアイテムに、ここにある元の `model` 定義を使う。カスタムモデルへ切り替えるために、時計の時刻セレクター、ヘッドのspecial renderer、ブロック用モデル参照等を簡略化しない。

アクセサリーの12基本素材の定義は Mojang の Minecraft Java Edition 1.21.11 client JAR の `assets/minecraft/items/` から取得した。`player_head` 以外の11素材は26.3の定義とも一致する。`transformed_item_models/player_head.json` は26.1 client JARの定義（26.2・26.3も同じ）で、pack format 83以降のヘッド座標変換を保持する。

player_headの変換クォータニオンの負のゼロは正のゼロへ正規化している。回転は同じで、PowerShell 5.1と7のJSON数値のシリアライズ差を避ける。

これらはアイテム定義JSONのみ。バニラのモデルとテクスチャ本体はクライアントのリソースを参照し、このリポジトリへ複製しない。

GUI用の基本素材38種も、同じMinecraft Java Edition 1.21.11の公式client JAR（SHA-1 `ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd`）の`assets/minecraft/items/`から追加した。チェストの季節表示、コンパスの針、ポーションと地図の色、防具の装飾などの定義をそのまま保持し、GUIモデル番号が一致しないアイテムには元の表示を使う。

GUI素材を26.1・26.2・26.3の公式client JARとも比較し、プレイヤーヘッド以外では26.3の`filled_map`だけに差分があった。`java-item-fallbacks/gui_modern_item_models/filled_map.json`に26.3の定義を保持し、形式97.1のオーバーレイで地図の色処理を同版の定義へ切り替える。
