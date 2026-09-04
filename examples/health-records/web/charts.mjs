import { h, svg } from "./dom.mjs";
import { numericValue, referenceRange, monthlyDays, canonicalUnit } from "./model.mjs";

function sample(points, max = 120) {
  if (points.length <= max) return points;
  const result = [points[0]], size = Math.ceil((points.length - 2) / (max / 2 - 1));
  for (let i = 1; i < points.length - 1; i += size) {
    const group = points.slice(i, Math.min(points.length - 1, i + size));
    let min = group[0], maxPoint = group[0];
    for (const p of group) { if (p.y < min.y) min = p; if (p.y > maxPoint.y) maxPoint = p; }
    result.push(...(min === maxPoint ? [min] : [min, maxPoint].sort((a, b) => a.x - b.x)));
  }
  result.push(points.at(-1));
  return result;
}

export function trendChart(metric) {
  const points = metric.points.map((p) => ({ ...p, y: numericValue(p.value), x: Date.parse(`${p.date}T00:00:00Z`) })).filter((p) => p.y !== null).reverse();
  if (!points.length) return h("p", { class: "chart-message" }, "文字或阈值结果不连成数值曲线，请查看原始记录。");
  const ranges = points.map((p) => referenceRange(p.normal));
  const first = ranges[0];
  const sameUnit = points.every((p) => canonicalUnit(p.unit) === canonicalUnit(points[0].unit));
  const shared = sameUnit && first && !first.qualitative && Number.isFinite(first.min) && Number.isFinite(first.max) && ranges.every((r) => r && r.min === first.min && r.max === first.max && r.minClosed === first.minClosed && r.maxClosed === first.maxClosed);
  const allY = points.map((p) => p.y).concat(shared ? [first.min, first.max] : []);
  let min = Math.min(...allY), max = Math.max(...allY);
  const pad = (max - min || Math.abs(max) || 1) * 0.15;
  min = min >= 0 ? Math.max(0, min - pad) : min - pad; max += pad;
  if (!Number.isFinite(max - min) || max === min) return h("p", { class: "chart-message" }, "数值跨度不适合绘图，请查看原始记录。");
  const step = (max - min) / 3;
  const ticks = Array.from({ length: 4 }, (_, i) => {
    const value = min + step * i, magnitude = Math.abs(value);
    const label = magnitude >= 10000 || (magnitude > 0 && magnitude < 0.001)
      ? value.toExponential(Math.max(0, Math.min(14, Math.floor(Math.log10(magnitude)) - Math.floor(Math.log10(step)) + 1)))
      : Number(value.toFixed(Math.max(0, Math.min(100, 1 - Math.floor(Math.log10(step)))))).toString();
    return { value, label };
  });
  const width = 360, height = 174, left = Math.max(40, Math.min(156, Math.max(...ticks.map((tick) => tick.label.length)) * 6.5 + 12)), right = 12, top = 12, bottom = 30;
  const minX = points[0].x, spanX = points.at(-1).x - minX;
  const x = (p) => spanX ? left + (p.x - minX) / spanX * (width - left - right) : (width + left - right) / 2;
  const y = (n) => top + (max - n) / (max - min) * (height - top - bottom);
  const chart = svg("svg", { viewBox: `0 0 ${width} ${height}`, class: "trend-svg", role: "img", "aria-label": `${metric.name}，${metric.specimen}，${metric.unit || "未注明单位"}，${points.length} 次数值记录；详细值见原始记录。` });
  if (shared) chart.append(svg("rect", { x: left, y: y(first.max), width: width - left - right, height: y(first.min) - y(first.max), class: "reference-band" }));
  for (const { value, label } of ticks) {
    const lineY = y(value);
    chart.append(svg("line", { x1: left, y1: lineY, x2: width - right, y2: lineY, class: "chart-grid" }));
    chart.append(svg("text", { x: left - 7, y: lineY + 4, "text-anchor": "end", class: "chart-label" }, label));
  }
  const plotted = sample(points);
  if (plotted.length > 1) chart.append(svg("path", { d: plotted.map((p, i) => `${i ? "L" : "M"}${x(p).toFixed(2)},${y(p.y).toFixed(2)}`).join(" "), class: "chart-line" }));
  for (const point of plotted) {
    chart.append(svg("circle", { cx: x(point), cy: y(point.y), r: plotted.length > 30 ? 2 : 3.5, class: ["low", "high", "outside"].includes(point.status) ? "chart-dot outside" : "chart-dot" }, svg("title", {}, `${point.date} ${point.value} ${point.unit || "未注明单位"}`)));
  }
  const crossesYears = points[0].date.slice(0, 4) !== points.at(-1).date.slice(0, 4);
  const labels = [points[0]];
  if (points.length > 2 && spanX) {
    const middleX = minX + spanX / 2;
    const middle = points.slice(1, -1).reduce((closest, point) => Math.abs(point.x - middleX) < Math.abs(closest.x - middleX) ? point : closest);
    const gap = crossesYears ? 100 : 60;
    if (x(middle) - x(points[0]) >= gap && x(points.at(-1)) - x(middle) >= gap) labels.push(middle);
  }
  if (points.length > 1) labels.push(points.at(-1));
  const used = new Set();
  labels.forEach((p, i) => {
    if (used.has(p.date)) return;
    used.add(p.date);
    chart.append(svg("text", { x: x(p), y: height - 8, "text-anchor": spanX && i === 0 ? "start" : spanX && i === labels.length - 1 ? "end" : "middle", class: "chart-label" }, (crossesYears ? p.date : p.date.slice(5)).replaceAll("-", ".")));
  });
  return h("div", { class: "chart" }, chart, h("p", { class: "chart-note" }, !sameUnit ? "单位不一致，按原始数值绘制且未换算，不共用参考区间；请核对单位后比较。" : shared ? "浅色带为各次报告一致的参考区间" : "按各次原始记录绘制，不同参考范围不共用区间带"));
}

export function monthlyChart(records, year) {
  const days = monthlyDays(records, year), max = Math.max(...days, 1);
  return h("div", { class: "month-chart", role: "img", "aria-label": `${year} 年每月记录天数：${days.map((d, i) => `${i + 1}月 ${d}天`).join("，")}` }, days.map((value, i) => {
    const fill = h("div", { class: "month-fill" });
    fill.style.height = `${value / max * 100}%`;
    return h("div", { class: "month-column" }, h("span", { class: "month-value" }, value), h("div", { class: "month-track" }, fill), h("span", {}, i + 1));
  }));
}
