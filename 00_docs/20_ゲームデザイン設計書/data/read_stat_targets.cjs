const fs = require("node:fs");
const vm = require("node:vm");

const html = fs.readFileSync(0, "utf8");
const blocks = [...html.matchAll(/^  \/\/ stat-model\n([\s\S]*?)^  \/\/ \/stat-model$/gm)];
if (blocks.length !== 1) throw new Error("02の計算モデルの範囲が無効です");

const source = blocks[0][1];
const panels = vm.runInNewContext(source + "\npanels", Object.create(null), {
  filename: "02-ステータス指標.html",
  timeout: 1000
});
const result = {player: [], mob: []};
for (const {grid, title, cats, unit, series} of panels) {
  const owner = grid.split("-")[0];
  if (!Object.hasOwn(result, owner)) throw new Error(`未登録の章 ${grid}`);
  const targets = series.map(({label, read}) => ({
    label,
    values: Array.from({length: 100}, (_, index) => {
      const level = index + 1;
      const value = read(level);
      if (!Number.isFinite(value)) throw new Error(`${title}・${label} Lv.${level} の値が無効です`);
      return value;
    })
  }));
  result[owner].push({title, category: cats[0], unit, series: targets});
}
process.stdout.write(JSON.stringify(result));
