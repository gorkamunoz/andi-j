// Chart helpers built on Observable Plot. Colors are read from the CSS tokens so that
// light and dark themes each get their own validated steps.

const C = {};

C.css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
C.series = () => [1, 2, 3, 4, 5].map(i => C.css(`--series-${i}`));

C.base = (opts) => ({
  style: { fontFamily: 'inherit', fontSize: '12px', color: C.css('--text-2'), background: 'transparent', overflow: 'visible' },
  marginLeft: 48, marginBottom: 40, marginTop: 26, marginRight: 12,
  ...opts,
});

C.mount = (el, plot) => { el.replaceChildren(plot); return plot; };

C.width = (el, max = 1000) => Math.max(260, Math.min(max, el.clientWidth || 400));

const fmt = (v, d = 3) => (v == null || Number.isNaN(v) ? '—' : (+v).toFixed(d));

C.seqScheme = () => [C.css('--seq-lo'), C.css('--seq-hi')];

// Readable text color on a sequential cell (dark ink on light steps, white on dark ones).
C.cellText = (t) => {
  const dark = document.documentElement.dataset.theme === 'dark' ||
    (!document.documentElement.dataset.theme && matchMedia('(prefers-color-scheme: dark)').matches);
  if (dark) return t > 0.55 ? '#0b0b0b' : '#f5f5f3';
  return t > 0.5 ? '#ffffff' : '#0b0b0b';
};

// ---------- Task 1 ----------

C.hexbin = (el, truth, pred) => {
  const w = Math.min(C.width(el, 460), 460);
  const data = truth.map((t, i) => ({ t, p: pred[i] }));
  return C.mount(el, Plot.plot(C.base({
    width: w, height: w,
    x: { domain: [0, 2.05], label: 'True α', ticks: 5 },
    y: { domain: [0, 2.05], label: 'Predicted α', ticks: 5, grid: true },
    color: { type: 'sqrt', range: C.seqScheme(), label: 'Trajectories' },
    marks: [
      Plot.dot(data, Plot.hexbin({ fill: 'count' }, { x: 't', y: 'p', binWidth: 11, symbol: 'hexagon', tip: true })),
      Plot.line([[0, 0], [2.05, 2.05]], { stroke: C.css('--text-3'), strokeDasharray: '4 4', strokeWidth: 1 }),
    ],
  })));
};

C.errorHist = (el, errors) => {
  const bins = M.histogram(errors, 50, -1, 1);
  const xs = d3.range(-1, 1.0001, 0.02);
  const kde = M.kde(errors, xs);
  return C.mount(el, Plot.plot(C.base({
    width: C.width(el, 520), height: 230,
    x: { domain: [-1, 1], label: 'Predicted α − true α' },
    y: { label: 'Density', grid: true },
    marks: [
      Plot.rectY(bins, { x1: 'x0', x2: 'x1', y: 'density', fill: C.css('--series-1'), fillOpacity: 0.35, inset: 1,
        tip: true, title: d => `${fmt(d.x0, 2)} to ${fmt(d.x1, 2)}\ndensity ${fmt(d.density, 2)}` }),
      Plot.line(kde, { x: 'x', y: 'y', stroke: C.css('--series-1'), strokeWidth: 2 }),
      Plot.ruleX([0], { stroke: C.css('--text-3'), strokeDasharray: '3 3' }),
      Plot.ruleY([0], { stroke: C.css('--border-strong') }),
    ],
  })));
};

// Bars for a metric by category (e.g. MAE per diffusion model).
C.bars = (el, rows, { label, domain, lowerBetter = true, digits = 3 }) => {
  return C.mount(el, Plot.plot(C.base({
    width: C.width(el, 520), height: 230,
    x: { domain: domain ?? rows.map(r => r.k), label: null, padding: 0.35 },
    y: { label, grid: true, nice: true },
    marks: [
      Plot.barY(rows.filter(r => r.v != null && !Number.isNaN(r.v)), { x: 'k', y: 'v', fill: C.css('--series-1'), rx: 4,
        tip: true, title: r => `${r.k}\n${label}: ${fmt(r.v, digits)}\n${r.n} trajectories` }),
      Plot.text(rows.filter(r => r.v != null && !Number.isNaN(r.v)), { x: 'k', y: 'v', text: r => fmt(r.v, digits === 3 ? 2 : digits), dy: -8, fill: C.css('--text-2'), fontSize: 11 }),
      Plot.ruleY([0], { stroke: C.css('--border-strong') }),
    ],
  })));
};

// Line + dots for a metric over ordered bins, one or more series.
C.lines = (el, series, { label, xlabel, domain, digits = 3, yDomain }) => {
  const colors = C.series();
  const marks = [Plot.ruleY([yDomain ? yDomain[0] : 0], { stroke: C.css('--border-strong') })];
  series.forEach((s, j) => {
    const rows = s.rows.filter(r => r.v != null && !Number.isNaN(r.v));
    const col = s.color ?? colors[j];
    marks.push(Plot.line(rows, { x: 'k', y: 'v', stroke: col, strokeWidth: 2 }));
    marks.push(Plot.dot(rows, { x: 'k', y: 'v', r: 4, fill: C.css('--surface'), stroke: col, strokeWidth: 2,
      tip: true, title: r => `${s.name ? s.name + '\n' : ''}${xlabel}: ${r.k}\n${label}: ${fmt(r.v, digits)}\n${r.n} trajectories` }));
  });
  return C.mount(el, Plot.plot(C.base({
    width: C.width(el, 520), height: domain.length > 8 ? 270 : 240, marginBottom: domain.length > 8 ? 74 : 44,
    x: { type: 'point', domain, label: xlabel, tickRotate: domain.length > 8 ? -35 : 0, padding: 0.3, labelOffset: domain.length > 8 ? 68 : 38, labelAnchor: 'center' },
    y: { label, grid: true, nice: true, domain: yDomain },
    marks,
  })));
};

// Square heatmap with value labels (confusion matrix, RMSE tables).
C.heatmap = (el, cells, { xlabel, ylabel, xdomain, ydomain, digits = 2, label, max }) => {
  const w = Math.min(C.width(el, 460), 460);
  const vals = cells.filter(c => c.v != null).map(c => c.v);
  const lo = 0, hi = max ?? (d3.max(vals) || 1);
  return C.mount(el, Plot.plot(C.base({
    width: w, height: w - 20, marginLeft: 60, marginBottom: 46,
    x: { domain: xdomain, label: xlabel, padding: 0.06 },
    y: { domain: ydomain, label: ylabel, padding: 0.06 },
    color: { type: 'linear', domain: [lo, hi], range: C.seqScheme() },
    marks: [
      Plot.cell(cells, { x: 'x', y: 'y', fill: d => (d.v == null ? C.css('--surface-2') : d.v), rx: 4,
        tip: true, title: d => `${xlabel}: ${d.x}\n${ylabel}: ${d.y}\n${label}: ${d.v == null ? 'no data' : fmt(d.v, digits)}${d.n != null ? `\n${d.n} trajectories` : ''}` }),
      Plot.text(cells, { x: 'x', y: 'y', text: d => (d.v == null ? '–' : fmt(d.v, digits)), fontSize: 12, fontWeight: 500,
        fill: d => (d.v == null ? C.css('--text-3') : C.cellText((d.v - lo) / (hi - lo || 1))) }),
    ],
  })));
};

// ROC curves: one per class present, plus micro (dashed) and macro averages.
C.roc = (el, roc, legendEl) => {
  const colors = C.series();
  const marks = [Plot.line([[0, 0], [1, 1]], { stroke: C.css('--border-strong'), strokeDasharray: '3 3' })];
  const thin = (c) => { // keep the plot light: at most ~400 points per curve
    const step = Math.max(1, Math.floor(c.fpr.length / 400));
    const out = []; for (let i = 0; i < c.fpr.length; i += step) out.push([c.fpr[i], c.tpr[i]]);
    out.push([c.fpr[c.fpr.length - 1], c.tpr[c.tpr.length - 1]]); return out;
  };
  const legend = [];
  for (const c of roc.classes) {
    marks.push(Plot.line(thin(roc.curves[c]), { stroke: colors[c], strokeWidth: 2 }));
    legend.push(`<span><i style="background:${colors[c]}"></i>${MODELS[c]} <span class="muted">${fmt(roc.aucs[c], 3)}</span></span>`);
  }
  marks.push(Plot.line(thin(roc.curves.macro), { stroke: C.css('--text'), strokeWidth: 2 }));
  marks.push(Plot.line(thin(roc.curves.micro), { stroke: C.css('--text'), strokeWidth: 2, strokeDasharray: '5 4' }));
  legend.push(`<span><i style="background:${C.css('--text')}"></i>Macro <span class="muted">${fmt(roc.aucs.macro, 3)}</span></span>`);
  legend.push(`<span><i class="dash"></i>Micro <span class="muted">${fmt(roc.aucs.micro, 3)}</span></span>`);
  if (legendEl) legendEl.innerHTML = legend.join('') + '<span class="muted">(AUC)</span>';
  const w = Math.min(C.width(el, 460), 460);
  return C.mount(el, Plot.plot(C.base({
    width: w, height: w - 40,
    x: { domain: [0, 1], label: 'False positive rate' },
    y: { domain: [0, 1], label: 'True positive rate', grid: true },
    marks,
  })));
};

// Rendered width of a 12px label in the page font (used to place labels without overlaps).
C.textWidth = (txt, weight = 500) => {
  const ctx = C._ctx || (C._ctx = document.createElement('canvas').getContext('2d'));
  ctx.font = `${weight} 12px ${getComputedStyle(document.body).fontFamily}`;
  return ctx.measureText(txt).width;
};

// Scatter of Task 1 MAE vs Task 2 F1, one dot per team.
// Methods that skipped a task sit on a dashed line in a gutter pinned to the plot edge
// (right gutter: no α prediction, bottom gutter: no model prediction). The gutters stay in place when zooming or
// panning; the dots in them still move along the line with their other score.
// Scroll or pinch to zoom, drag to pan, double click to reset, click a dot to select it.
// Labels are placed greedily around each dot so they never overlap; hidden ones show on hover.
C.scatter = (el, input, { selected, onPick } = {}) => {
  const W = C.width(el, 1100), H = Math.max(340, Math.min(560, Math.round(W * 0.52)));
  const m = { l: 60, r: 24, t: 30, b: 50 };
  const G = 46; // gutter size in pixels
  const anyNo1 = input.some(r => r.mae == null), anyNo2 = input.some(r => r.f1 == null);
  const right = anyNo1 ? W - m.r - G : W - m.r;    // edge of the main plotting area
  const bottom = anyNo2 ? H - m.b - G : H - m.b;
  const pxNot = W - m.r - G / 2, pyNot = H - m.b - G / 2; // fixed pixel position of the dashed lines
  const maes = input.filter(r => r.mae != null).map(r => r.mae), f1s = input.filter(r => r.f1 != null).map(r => r.f1);
  const padded = (v) => { if (!v.length) return [0, 1]; const lo = d3.min(v), hi = d3.max(v), r = (hi - lo) || 0.05; return [lo - r * 0.13, hi + r * 0.1]; };
  const bx = d3.scaleLinear(padded(maes), [m.l, right]);
  const by = d3.scaleLinear(padded(f1s), [bottom, m.t]);
  const base = C.css('--neutral-mark');
  const ink = C.css('--text'), ink2 = C.css('--text-2'), ink3 = C.css('--text-3'), surface = C.css('--surface'), gutter = C.css('--surface-2');
  // Each row carries its group color (r.color); r.emph marks non-reference groups (bold label, drawn on top).
  const color = r => r.color || base;
  const emph = r => !!r.emph || r.team === selected;
  let t = el.__zt || d3.zoomIdentity, xs = bx, ys = by, vis = []; // keep the zoom across redraws

  const wrap = document.createElement('div');
  Object.assign(wrap.style, { position: 'relative', width: `${W}px`, maxWidth: '100%', margin: '0 auto', touchAction: 'none', cursor: 'grab' });

  // Greedy label placement in pixel space.
  const place = (rows, reserved) => {
    const inside = (b) => b.x >= m.l && b.x + b.w <= W - m.r && b.y >= m.t - 4 && b.y + b.h <= H - m.b;
    const hit = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    const taken = [...reserved, ...rows.map(r => ({ x: xs(r.x) - 8, y: ys(r.y) - 8, w: 16, h: 16 }))];
    const order = [...rows].sort((a, b) => (b.team === selected) - (a.team === selected) || emph(b) - emph(a) || b.y - a.y);
    const cands = [[11, 0, 'start'], [-11, 0, 'end'], [0, -15, 'middle'], [0, 16, 'middle'], [9, -12, 'start'], [9, 12, 'start'], [-9, -12, 'end'], [-9, 12, 'end']];
    const out = [];
    for (const r of order) {
      const px = xs(r.x), py = ys(r.y);
      const w = C.textWidth(r.team, emph(r) ? 650 : 500) + 8, h = 15;
      for (const [dx, dy, anchor] of cands) {
        const x0 = anchor === 'start' ? px + dx : anchor === 'end' ? px + dx - w : px + dx - w / 2;
        const box = { x: x0, y: py + dy - h / 2, w, h };
        if (!inside(box) || taken.some(b => hit(box, b))) continue;
        taken.push(box);
        out.push({ ...r, lx: xs.invert(px + dx), ly: ys.invert(py + dy), anchor });
        break;
      }
    }
    return out;
  };

  const render = () => {
    xs = t.rescaleX(bx); ys = t.rescaleY(by);
    const xNot = xs.invert(pxNot), yNot = ys.invert(pyNot);
    const X = [xs.invert(m.l), xs.invert(W - m.r)], Y = [ys.invert(H - m.b), ys.invert(m.t)];
    // Dots: complete teams must be inside the main area, partial ones only along their line.
    const inX = (px) => px >= m.l - 1 && px <= right + 1, inY = (py) => py >= m.t - 1 && py <= bottom + 1;
    vis = input.map(r => ({ ...r, x: r.mae ?? xNot, y: r.f1 ?? yNot, partial: r.mae == null || r.f1 == null }))
      .filter(r => (r.mae == null || inX(xs(r.x))) && (r.f1 == null || inY(ys(r.y))));
    // Ticks only over the main area, not the gutters
    const xTicks = d3.ticks(xs.invert(m.l), xs.invert(right), Math.max(3, Math.round((right - m.l) / 90)));
    const yTicks = d3.ticks(ys.invert(bottom), ys.invert(m.t), Math.max(3, Math.round((bottom - m.t) / 45)));
    const guides = [], reserved = [], marks = [];
    if (anyNo1) {
      marks.push(Plot.rect([0], { x1: xs.invert(right + 6), x2: X[1], y1: Y[0], y2: Y[1], fill: gutter }));
      marks.push(Plot.ruleX([xNot], { stroke: C.css('--border-strong'), strokeWidth: 2, strokeDasharray: '6 5' }));
      guides.push({ x: xs.invert(pxNot + 12), y: ys.invert(m.t + 4), text: 'No α prediction', rotate: -90 });
      reserved.push({ x: pxNot + 5, y: m.t, w: 14, h: C.textWidth('No α prediction') + 6 });
    }
    if (anyNo2) {
      marks.push(Plot.rect([0], { x1: X[0], x2: X[1], y1: Y[0], y2: ys.invert(bottom + 6), fill: gutter }));
      marks.push(Plot.ruleY([yNot], { stroke: C.css('--border-strong'), strokeWidth: 2, strokeDasharray: '6 5' }));
      guides.push({ x: xs.invert(right - 4), y: ys.invert(pyNot + 12), text: 'No model prediction', rotate: 0 });
      reserved.push({ x: right - C.textWidth('No model prediction') - 8, y: pyNot + 5, w: C.textWidth('No model prediction') + 8, h: 14 });
    }
    const labels = place(vis, reserved);
    const text = (anchor) => Plot.text(labels.filter(l => l.anchor === anchor), {
      x: 'lx', y: 'ly', text: 'team', textAnchor: anchor, lineAnchor: 'middle', fontSize: 12,
      fill: l => (emph(l) ? ink : ink2), fontWeight: l => (emph(l) ? 650 : 500),
    });
    const guideText = (rot) => Plot.text(guides.filter(g => g.rotate === rot), {
      x: 'x', y: 'y', text: 'text', rotate: rot, textAnchor: 'end', lineAnchor: 'middle', fill: ink3, fontSize: 11.5, fontStyle: 'italic',
    });
    wrap.replaceChildren(Plot.plot({
      style: { fontFamily: 'inherit', fontSize: '12px', color: ink2, background: 'transparent' },
      width: W, height: H, marginLeft: m.l, marginRight: m.r, marginTop: m.t, marginBottom: m.b,
      x: { domain: X, label: 'Exponent α · MAE (lower is better) →', grid: true, ticks: xTicks },
      y: { domain: Y, label: '↑ Diffusion model · F1 score (higher is better)', grid: true, ticks: yTicks },
      marks: [
        ...marks, guideText(-90), guideText(0),
        // Teams with both tasks: filled dots. Teams missing one task: hollow dots on the dashed line.
        // The selected method gets a dark ring instead of a color change, so its group color stays visible.
        Plot.dot(vis.filter(r => r.team !== selected), { x: 'x', y: 'y', r: 7, strokeWidth: 2,
          fill: r => (r.partial ? surface : color(r)), stroke: r => (r.partial ? color(r) : surface),
          sort: r => (emph(r) ? 1 : 0) }),
        Plot.dot(vis.filter(r => r.team === selected), { x: 'x', y: 'y', r: 8.5, strokeWidth: 3,
          fill: r => (r.partial ? surface : color(r)), stroke: ink }),
        text('start'), text('end'), text('middle'),
        Plot.tip(vis, Plot.pointer({ x: 'x', y: 'y', maxRadius: 30,
          title: r => `${r.team}\nα MAE     ${r.mae == null ? 'no prediction' : `${fmt(r.mae)}  (rank ${r.r1})`}\nModel F1  ${r.f1 == null ? 'no prediction' : `${fmt(r.f1)}  (rank ${r.r2})`}` })),
      ],
    }));
  };

  const zoom = d3.zoom().scaleExtent([0.5, 60])
    .on('start', () => (wrap.style.cursor = 'grabbing'))
    .on('zoom', e => { t = el.__zt = e.transform; render(); })
    .on('end', () => (wrap.style.cursor = 'grab'));
  const sel = d3.select(wrap).call(zoom).on('dblclick.zoom', null);
  const reset = () => sel.transition().duration(350).call(zoom.transform, d3.zoomIdentity);
  wrap.addEventListener('dblclick', reset);
  wrap.addEventListener('click', (e) => {
    if (!onPick) return;
    const svg = wrap.querySelector('svg'); const b = svg.getBoundingClientRect();
    const px = (e.clientX - b.left) * (W / b.width), py = (e.clientY - b.top) * (H / b.height);
    let best = null, bd = 14;
    for (const r of vis) { const d = Math.hypot(xs(r.x) - px, ys(r.y) - py); if (d < bd) { bd = d; best = r; } }
    if (best) onPick(best.team);
  });
  el.replaceChildren(wrap);
  sel.property('__zoom', t);
  render();
  return { reset };
};

// ---------- Segmentation ----------
// The five best challenge teams keep one series color each; the AnDi-J default model always keeps
// the blue of its method group, so it reads the same here as in the Trajectory Analysis tab.

// 1, 2, 5 per decade: d3's default log ticks put an unlabelled mark at every unit, which reads as noise.
C.logTicks = (scale) => {
  const [a, b] = d3.extent(scale.domain), out = [];
  for (let e = Math.floor(Math.log10(a)); e <= Math.ceil(Math.log10(b)); e++)
    for (const m of [1, 2]) { const v = m * 10 ** e; if (v >= a && v <= b) out.push(v); }
  return out;
};

C.logFormat = (d) => (d >= 0.01 ? String(+d.toPrecision(3)) : d.toExponential(0).replace('e-', 'e−'));

// Tooltips name the metric and give the value, nothing else: the direction hints belong on the axes.
C.plain = (s) => String(s).replace(/\s*\((?:lower|higher) is better\)/, '').replace(/[\u2190\u2191\u2192\u2193]/g, '').trim();

// Click support for a Plot chart: picks the nearest datum in pixel space, null when you miss.
C.clickPoints = (plot, data, ax, ay, onPick, radius = 15) => {
  const sx = plot.scale('x'), sy = plot.scale('y');
  const W = +plot.getAttribute('width'), H = +plot.getAttribute('height');
  plot.style.cursor = 'pointer';
  plot.addEventListener('click', (e) => {
    const b = plot.getBoundingClientRect();
    const mx = (e.clientX - b.left) * (W / b.width), my = (e.clientY - b.top) * (H / b.height);
    let best = null, bd = radius;
    for (const d of data) {
      const dist = Math.hypot(sx.apply(ax(d)) - mx, sy.apply(ay(d)) - my);
      if (dist < bd) { bd = dist; best = d; }
    }
    onPick(best);
  });
};

C.segSeries = () => [2, 3, 4, 5, 6].map(i => C.css(`--series-${i}`));
C.andijColor = () => C.css('--series-1');

// Greedy label placement around points, in pixel space. Labels that find no free slot are dropped
// (their point keeps its tooltip); `keep` rows are placed first so they are never the ones dropped.
// Returns the rows grouped by candidate offset, since Plot takes dx/dy per mark, not per datum.
C.placeAround = (rows, px, py, bounds, keep = () => false, subset = null) => {
  const cands = [[10, 0, 'start'], [-10, 0, 'end'], [0, -12, 'middle'], [0, 13, 'middle'],
    [8, -10, 'start'], [8, 10, 'start'], [-8, -10, 'end'], [-8, 10, 'end'],
    // a second, wider ring, for the crowded corner where the best methods sit
    [0, -25, 'middle'], [0, 26, 'middle'], [22, 0, 'start'], [-22, 0, 'end'],
    [18, -20, 'start'], [18, 20, 'start'], [-18, -20, 'end'], [-18, 20, 'end']];
  const hit = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
  const taken = rows.map(r => ({ x: px(r) - 7, y: py(r) - 7, w: 14, h: 14 }));
  const groups = cands.map(() => []);
  const wanted = subset ? rows.filter(subset) : rows;
  for (const r of [...wanted].sort((a, b) => keep(b) - keep(a) || (a.rank ?? 99) - (b.rank ?? 99))) {
    const x = px(r), y = py(r), w = C.textWidth(r.team, keep(r) ? 650 : 500) + 6, h = 14;
    for (let i = 0; i < cands.length; i++) {
      const [dx, dy, anchor] = cands[i];
      const x0 = anchor === 'start' ? x + dx : anchor === 'end' ? x + dx - w : x + dx - w / 2;
      const box = { x: x0, y: y + dy - h / 2, w, h };
      if (box.x < bounds.l || box.x + box.w > bounds.r || box.y < bounds.t || box.y + box.h > bounds.b) continue;
      if (taken.some(b => hit(box, b))) continue;
      taken.push(box); groups[i].push(r); break;
    }
  }
  return cands.map(([dx, dy, anchor], i) => ({ dx, dy, anchor, rows: groups[i] })).filter(g => g.rows.length);
};

// Scatter of two overall metrics, one dot per method. Axes are oriented so that the best methods
// sit in the top right corner, as in the paper figures.
C.segScatter = (el, rows, { x, y, xlabel, ylabel, xlog, ylog, xreverse, yreverse, digits = 3, rules = [],
  hover = true, labels = true, selected, onPick }) => {
  const W = C.width(el, 560), H = Math.max(280, Math.round(W * 0.72));
  const m = { l: 56, r: 18, t: 24, b: 44 };
  const data = rows.map(r => ({ ...r, x: r[x], y: r[y] })).filter(r => r.x != null && r.y != null);
  const emph = (r) => r.group !== 'challenge';
  const fill = (r) => (emph(r) ? r.color : C.css('--surface'));
  const opts = (mark, ticks = {}) => ({
    style: { fontFamily: 'inherit', fontSize: '12px', color: C.css('--text-2'), background: 'transparent' },
    width: W, height: H, marginLeft: m.l, marginRight: m.r, marginTop: m.t, marginBottom: m.b,
    x: { label: xlabel, grid: true, type: xlog ? 'log' : 'linear', reverse: xreverse, nice: !xlog, inset: 10, ticks: ticks.x, tickFormat: xlog ? C.logFormat : undefined },
    y: { label: ylabel, grid: true, type: ylog ? 'log' : 'linear', reverse: yreverse, nice: !ylog, inset: 10, ticks: ticks.y, tickFormat: ylog ? C.logFormat : undefined },
    marks: [
      Plot.ruleY(rules, { y: 'v', stroke: C.css('--text-3'), strokeWidth: 1.5, strokeDasharray: '5 4',
        tip: true, title: r => `${r.name}\n${C.plain(ylabel)}: ${fmt(r.v, digits)}` }),
      Plot.dot(data.filter(r => !emph(r)), { x: 'x', y: 'y', r: 5, strokeWidth: 2, fill, stroke: r => r.color }),
      Plot.dot(data.filter(emph), { x: 'x', y: 'y', r: 6.5, strokeWidth: 2, fill, stroke: C.css('--surface') }),
      ...(selected ? [Plot.dot(data.filter(r => r.team === selected), { x: 'x', y: 'y', r: 8.5, fill,
        stroke: C.css('--text'), strokeWidth: 2.5 })] : []),
      ...mark,
      ...(hover ? [Plot.tip(data, Plot.pointer({ x: 'x', y: 'y', maxRadius: 28,
        title: r => `${r.team}\n${C.plain(xlabel)}: ${fmt(r.x, digits)}\n${C.plain(ylabel)}: ${fmt(r.y, digits)}` }))] : []),
    ],
  });
  // First pass gives the scales, so labels can be placed without overlapping.
  const probe = Plot.plot(opts([]));
  const sx = probe.scale('x'), sy = probe.scale('y');
  const groups = labels ? C.placeAround(data, r => sx.apply(r.x), r => sy.apply(r.y),
    { l: m.l, r: W - m.r, t: m.t - 6, b: H - m.b }, emph, labels === 'emph' ? emph : null) : [];
  const tks = { x: xlog ? C.logTicks(sx) : undefined, y: ylog ? C.logTicks(sy) : undefined };
  const texts = groups.map(g => Plot.text(g.rows, { x: 'x', y: 'y', text: 'team', dx: g.dx, dy: g.dy,
    textAnchor: g.anchor, lineAnchor: 'middle', fontSize: 11.5,
    fill: r => (emph(r) ? C.css('--text') : C.css('--text-2')), fontWeight: r => (emph(r) ? 650 : 500) }));
  const plot = C.mount(el, Plot.plot(opts(texts, tks)));
  if (onPick) {
    plot.style.cursor = 'pointer';
    plot.addEventListener('click', (e) => {
      const b = plot.getBoundingClientRect();
      const mx = (e.clientX - b.left) * (W / b.width), my = (e.clientY - b.top) * (H / b.height);
      let best = null, bd = 16;
      for (const r of data) { const d = Math.hypot(sx.apply(r.x) - mx, sy.apply(r.y) - my); if (d < bd) { bd = d; best = r; } }
      onPick(best ? best.team : null);
    });
  }
  return plot;
};

// One metric across the nine experiments: every method as a faint dot, the median of the five best
// challenge teams as a hollow marker, and the AnDi-J default model highlighted.
C.segPerExp = (el, { rows, median, andij, mine = [], label, exps, skip = [], log, height = 210, selected, onPick }) => {
  const grey = C.css('--neutral-mark'), blue = C.andijColor(), surface = C.css('--surface');
  const shown = rows.filter(r => !skip.includes(r.exp));
  const mid = median.filter(r => !skip.includes(r.exp));
  const mineA = andij.filter(r => !skip.includes(r.exp));
  const mineU = mine.filter(r => !skip.includes(r.exp));
  const hit = (r) => selected && r.team === selected;
  const plot = C.mount(el, Plot.plot(C.base({
    width: C.width(el, height > 260 ? 900 : 420), height, marginLeft: 52, marginBottom: 36, marginTop: 24,
    x: { type: 'point', domain: exps, label: 'Experiment', padding: 0.5 },
    y: { label, grid: true, nice: !log, type: log ? 'log' : 'linear', tickFormat: log ? C.logFormat : undefined },
    marks: [
      Plot.ruleX(exps, { stroke: C.css('--border'), strokeWidth: 1 }),
      ...skip.map(e => Plot.ruleX([e], { stroke: C.css('--border-strong'), strokeDasharray: '3 3' })),
      Plot.dot(shown, { x: 'exp', y: 'v', r: r => (hit(r) ? 5 : 3.2), fill: grey,
        fillOpacity: r => (hit(r) ? 1 : 0.35), stroke: null }),
      Plot.dot(mid, { x: 'exp', y: 'v', r: 5, fill: surface, stroke: grey, strokeWidth: 2 }),
      Plot.dot(mineA, { x: 'exp', y: 'v', r: 5, fill: blue, stroke: surface, strokeWidth: 1.5 }),
      Plot.dot(mineU, { x: 'exp', y: 'v', r: 5, fill: C.css('--series-2'), stroke: surface, strokeWidth: 1.5 }),
      ...(selected ? [Plot.dot(shown.concat(mineA, mineU).filter(hit), { x: 'exp', y: 'v', r: 7,
        fill: 'none', stroke: C.css('--text'), strokeWidth: 2 })] : []),
    ],
  })));
  if (onPick) C.clickPoints(plot, shown.concat(mineA, mineU), r => r.exp, r => r.v, onPick);
  return plot;
};

// One metric against the number of changepoints per trajectory, one line per method. Every
// participant is drawn the same grey: clicking a line is what tells you which method it is.
C.segLines = (el, series, { label, xlabel, domain, log, selected, onPick }) => {
  const marks = [], pts = [];
  series.forEach(s => {
    const rows = s.rows.filter(r => r.v != null && !Number.isNaN(r.v));
    const on = selected && s.name === selected;
    marks.push(Plot.line(rows, { x: 'k', y: 'v', stroke: s.color,
      strokeWidth: on ? 3 : s.emph ? 2.4 : 1.5, strokeOpacity: on || s.emph ? 1 : 0.55 }));
    if (on) marks.push(Plot.dot(rows, { x: 'k', y: 'v', r: 3.4, fill: s.color }));
    rows.forEach(r => pts.push({ ...r, name: s.name }));
  });
  const plot = C.mount(el, Plot.plot(C.base({
    width: C.width(el, 420), height: 210, marginLeft: 52, marginBottom: 36, marginTop: 24,
    x: { domain, label: xlabel, ticks: domain[1] - domain[0] + 1, tickFormat: d => d },
    y: { label, grid: true, nice: !log, type: log ? 'log' : 'linear', tickFormat: log ? C.logFormat : undefined },
    marks,
  })));
  if (onPick) C.clickPoints(plot, pts, r => r.k, r => r.v, onPick, 18);
  return plot;
};

// Predicted share of each diffusion type in one experiment, with the ground truth as a dashed rule.
C.segBars = (el, { rows, truth, states, short, teams, ylabel, selected, onPick }) => {
  const plot = C.mount(el, Plot.plot(C.base({
    width: C.width(el, 300), height: 200, marginLeft: 46, marginBottom: 34, marginTop: 24, marginRight: 6,
    fx: { domain: states, label: null, axis: 'bottom', tickFormat: s => short[states.indexOf(s)], padding: 0.18 },
    x: { domain: teams.map(t => t.name), axis: null, padding: 0.12 },
    y: { label: ylabel, grid: true, domain: [0, 1], tickFormat: '.0%' },
    color: { domain: teams.map(t => t.name), range: teams.map(t => t.color) },
    marks: [
      Plot.barY(rows, { fx: 'state', x: 'team', y: 'v', fill: 'team', rx: 1.5,
        stroke: r => (r.team === selected ? C.css('--text') : 'none'), strokeWidth: 1.5,
        fillOpacity: r => (!selected || r.team === selected ? 1 : 0.5) }),
      Plot.ruleY(truth, { fx: 'state', y: 'v', stroke: C.css('--text'), strokeWidth: 1.5, strokeDasharray: '4 3' }),
      Plot.ruleY([0], { stroke: C.css('--border-strong') }),
    ],
  })));
  // Bars live in a band inside a facet band, so hit testing goes through both scales.
  if (onPick) {
    const fx = plot.scale('fx'), sx = plot.scale('x'), sy = plot.scale('y');
    const W = +plot.getAttribute('width'), H = +plot.getAttribute('height');
    plot.style.cursor = 'pointer';
    plot.addEventListener('click', (e) => {
      const b = plot.getBoundingClientRect();
      const mx = (e.clientX - b.left) * (W / b.width), my = (e.clientY - b.top) * (H / b.height);
      let best = null;
      for (const r of rows) {
        const x0 = fx.apply(r.state) + sx.apply(r.team), x1 = x0 + sx.bandwidth;
        if (mx < x0 - 1 || mx > x1 + 1) continue;
        if (my < Math.min(sy.apply(r.v), sy.apply(0)) - 6 || my > sy.apply(0) + 6) continue;
        best = r;
      }
      onPick(best);
    });
  }
  return plot;
};
