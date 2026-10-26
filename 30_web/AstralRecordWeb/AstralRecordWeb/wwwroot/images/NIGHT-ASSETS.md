# 夜景背景

`bg-night-640.webp` / `bg-night-1280.webp` / `bg-night-1672.webp`

- 既存のMinecraft画像 `bg-sky-1920.webp` を参照し、組み込みの画像生成ツールで夜のライティングへ編集した背景画像。
- 飛行船、浮島、大樹、木造建築、ブロックの質感を維持し、月明かりと暖かな窓・ランタンの光を追加した。ゲーム内の撮影時刻や照明をそのまま再現するスクリーンショットではない。
- 生成日: 2026-10-26。生成結果は1672×941。配信画像はWebP、幅640 / 1280 / 1672、品質86。配信時に外部画像サービスを参照しない。
- ホーム、共通の空背景ヒーロー、プロフィールで使用する。街の背景 `bg-town-*` は既存の夜景を継続使用する。星雲動画は夜空へ薄く合成し、建築を隠さないようにする。

## 最終生成プロンプト

Use case: lighting-weather. Asset type: wide 16:9 website background, 1920x1080 or larger. Edit target: the referenced Minecraft screenshot. Transform this exact scene into an evocative true NIGHT scene. Preserve the recognizable voxel/block geometry, floating islands, giant central tree, wooden buildings, right-hand ornate airship and the composition/perspective. Replace the daytime pale sky with a deep midnight blue-teal sky with sparse small stars and a subtle crescent moon high on the right. Use cool moonlight to reveal stone and white sails, warm amber light from windows, lanterns and airship interior, a few cyan magical lights among foliage, atmospheric mist far below the floating islands. Keep Minecraft pixel textures crisp and geometric; it must unmistakably look like Minecraft with cinematic shaders, not a smooth fantasy illustration. Make the right-hand airship and tree readable and beautifully lit without overexposure. Left third should be darker and calm enough for white website headings. Night sky must not look like daylight or dusk. No words, no UI, no logos, no watermark, no added foreground characters, no large stars or magic-circle graphics; we add effects in code.
