// AnDi-J benchmark: views, filters, tables and the in-browser submission scoring.
// Reference methods are the participants of the 1st AnDi Challenge (Tasks 1 and 2).

const UPLOAD = 'Your submission';
// Method groups: color in the plot and pin in the tables. The group of each method comes from
// data/methods.json ({"name": {"group": "andij" | "model"}}); anything not listed is a challenge entry.
const GROUPS = {
  andij: { label: 'AnDi-J default', color: '--series-1' },
  model: { label: 'Other available models', color: '--series-3' },
  challenge: { label: '1st AnDi Challenge', color: '--neutral-mark' },
  upload: { label: 'New submission', color: '--series-2' },
};
let METHODS = {};
const methodsReady = fetch('data/methods.json', { cache: 'no-cache' }).then(r => (r.ok ? r.json() : {})).catch(() => ({})).then(m => (METHODS = m || {}));
const groupOf = (t) => (t === UPLOAD ? 'upload' : METHODS[t]?.group in GROUPS ? METHODS[t].group : 'challenge');
const DIM_N = {}; // number of test trajectories per task and dimension, filled when data loads

const DEFAULT_F = () => ({ model: 'All', snr: 'All', alpha: [0, 2], length: [10, 1000] });

const state = {
  view: 'overview',
  dim: 2,
  f: DEFAULT_F(),        // filters shared by all views
  sel: { overview: null, task1: null, task2: null, segmentation: null },
  seg: { x: 'mae_alpha', y: 'msle_k',   // metric explorer: metric on each axis
    pick: { overall: null, perexp: null, cp: null, single: null, clf: null }, max: null },

  sort: {},
  upload: { 1: {}, 2: {} }, // upload[task][dim] -> predictions shaped like a team's data
  segUpload: null,          // a scored Trajectory segmentation submission, in memory only
  uploadInfo: {},                  // uploadInfo[task] -> { filename, dims, skipped }
};

// ---------- Data ----------

const cache = {};
function load(task, dim) {
  const key = `${task}_${dim}`;
  if (!cache[key]) {
    cache[key] = fetch(`data/task${task}_${dim}d.json`, { cache: 'no-cache' })
      .then(r => { if (!r.ok) throw new Error(`Could not load data/task${task}_${dim}d.json (${r.status})`); return r.json(); })
      .then(d => { DIM_N[key] = (d.alpha || d.alpha1).length; return d; })
      .catch(e => { delete cache[key]; throw e; });
  }
  return cache[key];
}

function teamsOf(task, dim, data) {
  const t = Object.keys(data.teams);
  if (state.upload[task][dim]) t.push(UPLOAD);
  return t;
}
const teamData = (task, dim, data, team) => (team === UPLOAD ? state.upload[task][dim] : data.teams[team]);

// ---------- Filters -> row indices ----------

function idxF(d, f, useModel = true) {
  const out = []; const snr = f.snr === 'All' ? null : +f.snr;
  const m = useModel && f.model !== 'All' ? MODELS.indexOf(f.model) : null;
  for (let i = 0; i < d.alpha.length; i++) {
    const a = d.alpha[i], L = d.length[i];
    if (a < f.alpha[0] - 1e-9 || a > f.alpha[1] + 1e-9) continue;
    if (L < f.length[0] || L > f.length[1]) continue;
    if (snr !== null && d.snr[i] !== snr) continue;
    if (m !== null && d.model[i] !== m) continue;
    out.push(i);
  }
  return out;
}

// ---------- Leaderboards ----------

function rankRows(rows, key, lowerBetter) {
  const valid = rows.filter(r => r[key] && !Number.isNaN(r[key].value));
  valid.sort((a, b) => (lowerBetter ? a[key].value - b[key].value : b[key].value - a[key].value));
  valid.forEach((r, i) => (r.rank = i + 1));
  return valid;
}

function lbTask1(d, idx, dim) {
  return rankRows(teamsOf(1, dim, d).map(team => ({ team, mae: M.mae(teamData(1, dim, d, team), d.alpha, idx) })), 'mae', true);
}

function lbTask2(d, idx, dim, withAuc = true) {
  return rankRows(teamsOf(2, dim, d).map(team => {
    const td = teamData(2, dim, d, team);
    const row = { team, f1: M.accuracy(td.pred, d.model, idx) };
    if (withAuc && idx.length) row.auc = M.roc(td.prob, d.model, idx).aucs.micro;
    return row;
  }), 'f1', false);
}

// ---------- Small DOM helpers ----------

const $ = (s, el = document) => el.querySelector(s);
const h = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };
const esc = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const f3 = (v, d = 3) => (v == null || Number.isNaN(v) ? '—' : (+v).toFixed(d));
const pin = (t) => { const g = GROUPS[groupOf(t)]; return `<i class="pin" style="background:var(${g.color})" title="${g.label}"></i>`; };
const teamLabel = (t) => `<span class="mname">${pin(t)}${esc(t)}${t === UPLOAD ? '<span class="you">You</span>' : ''}</span>`;
const swatch = (g) => `<span class="sw" style="background:var(${GROUPS[g].color})"></span>`;
const rankBadge = (r) => (r ? `<span class="rank-badge ${r <= 3 ? 'r' + r : ''}">${r}</span>` : '<span class="na">—</span>');
const loadingHTML = '<div class="loading"><div class="spinner"></div>Loading data…</div>';
const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

// Generic sortable leaderboard table.
function table(el, { id, cols, rows, onClick, selected, defaultSort }) {
  const s = state.sort[id] ?? (state.sort[id] = defaultSort);
  const col = cols.find(c => c.key === s.key);
  const sorted = [...rows];
  if (col?.sort) sorted.sort((a, b) => {
    const va = col.sort(a), vb = col.sort(b);
    if (va == null && vb == null) return 0; if (va == null) return 1; if (vb == null) return -1;
    return s.dir * (va - vb);
  });
  const arrow = (c) => (c.key === s.key ? `<span class="arrow">${s.dir > 0 ? '↑' : '↓'}</span>` : '');
  el.innerHTML = `<div class="table-wrap"><table class="lb"><thead><tr>${cols.map(c =>
    `<th class="${c.cls ?? ''} ${c.sort ? 'sortable' : ''}" data-k="${c.key}" title="${c.title ?? ''}">${c.label}${arrow(c)}</th>`).join('')}</tr></thead>
    <tbody>${sorted.map(r => `<tr data-team="${esc(r.team)}" class="${r.team === selected ? 'sel' : ''}">${cols.map(c =>
      `<td class="${c.cls ?? ''}">${c.html(r)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
  el.querySelectorAll('th.sortable').forEach(th => th.addEventListener('click', () => {
    const k = th.dataset.k; const c = cols.find(x => x.key === k);
    state.sort[id] = s.key === k ? { key: k, dir: -s.dir } : { key: k, dir: c.dir ?? 1 };
    table(el, { id, cols, rows, onClick, selected, defaultSort });
  }));
  el.querySelectorAll('tbody tr').forEach(tr => tr.addEventListener('click', () => onClick?.(tr.dataset.team)));
}

// Metric cell with a CI and a small bar (bar length relative to the best/worst in the table).
function metricCell(m, rows, key, lowerBetter, digits = 3) {
  if (!m || Number.isNaN(m.value)) return '<span class="na">—</span>';
  const vals = rows.map(r => r[key]?.value).filter(v => v != null && !Number.isNaN(v));
  const lo = Math.min(...vals), hi = Math.max(...vals);
  let t = hi > lo ? (m.value - lo) / (hi - lo) : 1; if (lowerBetter) t = 1 - t;
  const ci = Number.isNaN(m.ci) ? '' : `<span class="ci">±${m.ci.toFixed(digits)}</span>`;
  return `<div class="bar-cell"><span>${m.value.toFixed(digits)}${ci}</span><span class="minibar"><i style="width:${(15 + 85 * t).toFixed(0)}%"></i></span></div>`;
}

// ---------- Filter bar ----------

function segField(label, options, value, onChange, fmt = (o) => o) {
  const el = h(`<div class="field"><span class="field-label">${label}</span><div class="seg">${options.map(o =>
    `<button data-v="${o}" class="${String(o) === String(value) ? 'on' : ''}">${fmt(o)}</button>`).join('')}</div></div>`);
  el.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
    el.querySelectorAll('button').forEach(x => x.classList.toggle('on', x === b)); onChange(b.dataset.v);
  }));
  return el;
}

function selectField(label, options, value, onChange, fmt = (o) => o) {
  const el = h(`<label class="field"><span class="field-label">${label}</span><select class="sel">${options.map(o =>
    `<option value="${o}" ${String(o) === String(value) ? 'selected' : ''}>${fmt(o)}</option>`).join('')}</select></label>`);
  el.querySelector('select').addEventListener('change', e => onChange(e.target.value));
  return el;
}

function rangeField(label, min, max, step, value, onChange, digits = 2) {
  const el = h(`<div class="field range"><div class="range-top"><span class="field-label">${label}</span><span class="range-val"></span></div>
    <div class="range-track"><div class="range-fill"></div>
    <input type="range" min="${min}" max="${max}" step="${step}" value="${value[0]}" aria-label="${label} minimum">
    <input type="range" min="${min}" max="${max}" step="${step}" value="${value[1]}" aria-label="${label} maximum"></div></div>`);
  const [a, b] = el.querySelectorAll('input'); const fill = $('.range-fill', el); const out = $('.range-val', el);
  const fire = debounce(onChange, 140);
  const paint = () => {
    let lo = +a.value, hi = +b.value; if (lo > hi) [lo, hi] = [hi, lo];
    fill.style.left = `${(100 * (lo - min)) / (max - min)}%`; fill.style.width = `${(100 * (hi - lo)) / (max - min)}%`;
    out.textContent = `${lo.toFixed(digits)} – ${hi.toFixed(digits)}`; return [lo, hi];
  };
  paint();
  [a, b].forEach(inp => inp.addEventListener('input', () => fire(paint())));
  return el;
}

function filterBar(fields, onReset) {
  const bar = h('<div class="card filters"></div>');
  fields.forEach(f => bar.append(f));
  if (onReset) { const r = h('<button class="reset">Reset filters</button>'); r.addEventListener('click', onReset); bar.append(r); }
  return bar;
}

function dimField(onChange) {
  return segField('Dimension', [1, 2, 3], state.dim, v => { state.dim = +v; onChange(); }, o => `${o}D`);
}

async function snrOptions(task) {
  const d = await load(task, state.dim);
  return ['All', ...[...new Set(d.snr)].sort((a, b) => a - b)];
}

async function fFields(task, refresh, { model = true } = {}) {
  const snr = await snrOptions(task);
  if (!snr.map(String).includes(String(state.f.snr))) state.f.snr = 'All';
  const fields = [
    dimField(() => renderView()),
  ];
  if (model) fields.push(selectField('Diffusion model', ['All', ...MODELS], state.f.model, v => { state.f.model = v; refresh(); }));
  fields.push(
    selectField('Noise (SNR)', snr, state.f.snr, v => { state.f.snr = v; refresh(); }, o => (o === 'All' ? 'All' : `SNR ${o}`)),
    rangeField('Exponent α', 0, 2, 0.05, state.f.alpha, v => { state.f.alpha = v; refresh(); }),
    rangeField('Length', 10, 1000, 10, state.f.length, v => { state.f.length = v; refresh(); }, 0),
  );
  return filterBar(fields, () => { state.f = DEFAULT_F(); renderView(); });
}

// ---------- Views ----------

const VIEWS = {
  overview: viewHome,
  trajectory: viewOverview,
  'trajectory/alpha': viewTask1,
  'trajectory/model': viewTask2,
  'trajectory/submit': viewSubmit,
  segmentation: viewSegmentation,
  'segmentation/submit': viewSegSubmit,
};
const ALIASES = { task1: 'trajectory/alpha', task2: 'trajectory/model', submit: 'trajectory/submit' }; // old links
let renderToken = 0;

async function renderView() {
  const token = ++renderToken;
  const main = $('#main');
  const section = state.view.split('/')[0];
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.view === section));
  // Each section with sub pages has its own row of links; only the active section's row shows.
  $('#subnav').hidden = !['trajectory', 'segmentation'].includes(section);
  document.querySelectorAll('#subnav .subnav-inner').forEach(n => (n.hidden = n.dataset.for !== section));
  document.querySelectorAll('#subnav a').forEach(a => a.classList.toggle('active', a.dataset.view === state.view));
  main.innerHTML = loadingHTML;
  try {
    await methodsReady;
    const page = await VIEWS[state.view](token);
    if (token !== renderToken) return;
    main.replaceChildren(page);
    page.dispatchEvent(new Event('mounted'));
  } catch (e) {
    console.error(e);
    if (token === renderToken) main.innerHTML = `<div class="card card-body empty">Something went wrong: ${esc(e.message)}</div>`;
  }
}

function pageHead(eyebrow, title, lede, right = '') {
  return h(`<div class="page-head"><div>${eyebrow ? `<p class="eyebrow">${eyebrow}</p>` : ''}<h1>${title}</h1>${lede ? `<p class="lede">${lede}</p>` : ''}</div>${right}</div>`);
}

function card(title, sub, bodyCls = 'card-body') {
  return h(`<section class="card"><div class="card-head"><div><h2>${title}</h2>${sub ? `<div class="card-sub">${sub}</div>` : ''}</div></div><div class="${bodyCls}"></div></section>`);
}
const body = (c) => c.lastElementChild;

// Runs `fn` now and whenever the window width changes (charts need their container width).
function onLayout(page, fn) {
  page.addEventListener('mounted', () => requestAnimationFrame(fn));
  const r = debounce(() => { if (page.isConnected) fn(); }, 200);
  window.addEventListener('resize', r);
}

// ----- Overview -----

async function viewOverview() {
  const dim = state.dim;
  const [d1, d2] = await Promise.all([load(1, dim), load(2, dim)]);
  const page = h('<div></div>');
  page.append(pageHead('', 'Trajectory Analysis benchmark',
    `The Trajectory Analysis tab of AnDi-J predicts, among other things, the anomalous exponent α and the diffusion model of each trajectory. Use this page to benchmark new models and compare them with the existing ones.
    In ${swatch('andij')}<b>blue</b> you find the AnDi-J default model, in ${swatch('model')}<b>green</b> other available models (see the list <a href="#" title="List coming soon">here</a>)
    and in ${swatch('challenge')}<b>grey</b> the methods of the <a href="https://doi.org/10.1038/s41467-021-26320-w" target="_blank" rel="noopener">1st AnDi Challenge</a>. New submissions are marked in ${swatch('upload')}<b>orange</b>.`));

  const sCard = h(`<section class="card"><div class="card-head"><div><h2>AnDi-J Trajectory Analysis Benchmark</h2>
    <div class="card-sub">Scroll or pinch to zoom, drag to move, double click to reset. Hover a dot for details, click it to highlight the method. Best models sit in the top left; models without one of the two predictions sit on the dashed lines.</div></div>
    <div class="scatter-tools wide"><span class="legend" id="sc-legend"></span><button class="btn small" id="sc-reset">Reset view</button><span class="dim-note" id="sc-dim"></span></div></div>
    <div class="card-body"></div></section>`);
  page.append(sCard);
  const bar = await fFields(1, () => { delete body(sCard).__zt; draw(); });
  bar.style.marginTop = '16px';
  page.append(bar);
  const tCard = card('Rankings', ' ', '');
  page.append(tCard);
  let scatter;
  $('#sc-reset', sCard).addEventListener('click', () => scatter?.reset());

  function draw() {
    const i1 = idxF(d1, state.f), i2 = idxF(d2, state.f, false);
    const l1 = lbTask1(d1, i1, dim), l2 = lbTask2(d2, i2, dim, false);
    const teams = [...new Set([...l1, ...l2].map(r => r.team))];
    const by = (l) => Object.fromEntries(l.map(r => [r.team, r]));
    const b1 = by(l1), b2 = by(l2);
    const rows = teams.map(team => ({ team, t1: b1[team], t2: b2[team] }));
    if (!teams.includes(state.sel.overview)) state.sel.overview = null;
    const hasAndij = teams.some(t => groupOf(t) === 'andij');
    $('#sc-dim', sCard).textContent = hasAndij ? `${dim}D` : `${dim}D (not included in AnDi-J; only AnDi challenge results)`;
    $('.card-sub', tCard).textContent = `Rank of each method under the current filters (${dim}D, ${i1.length.toLocaleString()} trajectories for α, ${i2.length.toLocaleString()} for the model). Click a column to sort, a row to highlight.`;

    const pick = team => { state.sel.overview = state.sel.overview === team ? null : team; drawTable(); drawScatter(); };
    const cell = (r, m, digits) => (r ? `${rankBadge(r.rank)} <span class="muted small" style="margin-left:6px">${f3(r[m].value, digits)}</span>` : '<span class="na">—</span>');
    const drawTable = () => table(body(tCard), {
      id: 'overview', rows, selected: state.sel.overview, defaultSort: { key: 't1', dir: 1 },
      cols: [
        { key: 'team', label: 'Method', cls: 'team', html: r => teamLabel(r.team) },
        { key: 't1', label: 'Exponent α · MAE', html: r => cell(r.t1, 'mae', 3), sort: r => r.t1?.rank, dir: 1 },
        { key: 't2', label: 'Diffusion model · F1', html: r => cell(r.t2, 'f1', 3), sort: r => r.t2?.rank, dir: 1 },
      ],
      onClick: pick,
    });
    const drawScatter = () => {
      const pts = rows.filter(r => r.t1 || r.t2).map(r => ({ team: r.team, mae: r.t1?.mae.value ?? null, f1: r.t2?.f1.value ?? null, r1: r.t1?.rank, r2: r.t2?.rank, color: C.css(GROUPS[groupOf(r.team)].color), emph: groupOf(r.team) !== 'challenge' }));
      const present = new Set(pts.map(p => groupOf(p.team)));
      $('#sc-legend', sCard).innerHTML = Object.keys(GROUPS).filter(g => present.has(g))
        .map(g => `<span><i class="dot" style="background:${C.css(GROUPS[g].color)}"></i>${GROUPS[g].label}</span>`).join('');
      if (!pts.length) { body(sCard).innerHTML = '<div class="empty">No results under these filters.</div>'; scatter = null; return; }
      scatter = C.scatter(body(sCard), pts, { selected: state.sel.overview, onPick: pick });
    };
    drawTable();
    drawScatter();
  }
  onLayout(page, draw);
  return page;
}

// ----- Shared task page scaffolding -----

function detailHead(title, teams, selected, onPick, kpis) {
  const el = h(`<div class="detail-head"><h2>${title}</h2></div>`);
  el.append(selectField('', teams, selected, onPick, t => (t === UPLOAD ? `${t} (you)` : t)));
  el.lastElementChild.querySelector('.field-label').remove();
  el.append(h(`<div class="kpis">${kpis.map(([v, l]) => `<div class="kpi"><b>${v}</b><span>${l}</span></div>`).join('')}</div>`));
  return el;
}

// After picking a model in the leaderboard (bottom of the page), bring its plots into view.
function scrollToPlots(el) {
  const header = $('.topbar').offsetHeight;
  window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - header - 12, behavior: 'smooth' });
}

function pickTeam(view, lb) {
  const names = lb.map(r => r.team);
  if (!names.includes(state.sel[view])) state.sel[view] = names.includes(UPLOAD) ? UPLOAD : names[0] ?? null;
  return state.sel[view];
}

// Metric over ordered bins of a variable, for one team.
function binned(idx, key, edges, labels, metricFn) {
  return labels.map((k, j) => {
    const sub = idx.filter(i => { const v = key(i); return v >= edges[j] && (j === labels.length - 1 ? v <= edges[j + 1] : v < edges[j + 1]); });
    const m = sub.length ? metricFn(sub) : null;
    return { k, v: m ? m.value : null, n: sub.length };
  });
}
const LEN_EDGES = [10, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000];
const LEN_LABELS = LEN_EDGES.slice(0, -1).map((e, j) => `${e}–${LEN_EDGES[j + 1]}`);
const A_EDGES = [0, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const A_LABELS = A_EDGES.slice(0, -1).map((e, j) => `${e}–${A_EDGES[j + 1]}`);

// ----- Task 1 -----

async function viewTask1() {
  const dim = state.dim; const d = await load(1, dim);
  const page = h('<div></div>');
  page.append(pageHead('Trajectory Analysis', 'Anomalous exponent α',
    'Models that predict the anomalous exponent α of each trajectory, as in the Anomalous exponent tab of AnDi-J. Scored on the test trajectories of the 1st AnDi Challenge and ranked by mean absolute error (MAE) between predicted and true α; ± gives the 95% confidence interval.'));
  const content = h('<div></div>');
  page.append(await fFields(1, () => draw()), content);

  function draw() {
    const idx = idxF(d, state.f); content.innerHTML = '';
    if (!idx.length) { content.append(h('<div class="card empty">No trajectories match these filters. Try widening the ranges.</div>')); return; }
    const lb = lbTask1(d, idx, dim); const team = pickTeam('task1', lb);
    const tCard = card('Leaderboard', `${idx.length.toLocaleString()} trajectories · ${dim}D · click a row to show that model in the plots above`, '');
    table(body(tCard), {
      id: 'task1', rows: lb, selected: team, defaultSort: { key: 'rank', dir: 1 },
      cols: [
        { key: 'rank', label: 'Rank', cls: 'rank', html: r => rankBadge(r.rank), sort: r => r.rank },
        { key: 'team', label: 'Method', cls: 'team', html: r => teamLabel(r.team) },
        { key: 'mae', label: 'MAE', cls: 'num', html: r => metricCell(r.mae, lb, 'mae', true), sort: r => r.mae.value, dir: 1, title: 'Mean absolute error of α, 95% CI' },
        { key: 'n', label: 'Trajectories', cls: 'num', html: r => r.mae.n.toLocaleString() },
      ],
      onClick: t => { state.sel.task1 = t; draw(); scrollToPlots(content); },
    });

    const td = teamData(1, dim, d, team); const row = lb.find(r => r.team === team);
    content.append(detailHead(`${esc(team)}`, lb.map(r => r.team), team, t => { state.sel.task1 = t; draw(); },
      [[f3(row.mae.value), 'MAE'], [`#${row.rank}`, `of ${lb.length}`]]));
    const g1 = h('<div class="grid grid-2"></div>'), g2 = h('<div class="grid grid-2" style="margin-top:16px"></div>');
    const cHex = card('Predicted vs true α', 'Each hexagon counts trajectories; the dashed line is a perfect prediction.');
    const cErr = card('Error distribution', 'Histogram of predicted minus true α with a kernel density estimate.');
    const cMod = card('MAE by diffusion model');
    const cLen = card('MAE by trajectory length');
    const right = h('<div class="stack"></div>'); right.append(cErr, cMod);
    g1.append(cHex, right); g2.append(cLen, card('MAE by true α')); content.append(g1, g2);
    tCard.style.marginTop = '28px'; content.append(tCard);
    const cAlpha = g2.lastElementChild;
    const mae = (sub) => M.mae(td, d.alpha, sub);
    requestAnimationFrame(() => {
      C.hexbin(body(cHex), idx.map(i => d.alpha[i]), idx.map(i => td[i]));
      C.errorHist(body(cErr), idx.map(i => td[i] - d.alpha[i]));
      const byModel = MODELS.map((m, k) => { const sub = idx.filter(i => d.model[i] === k); return { k: m, v: sub.length ? mae(sub).value : null, n: sub.length }; });
      C.bars(body(cMod), byModel, { label: 'MAE', domain: MODELS });
      C.lines(body(cLen), [{ rows: binned(idx, i => d.length[i], LEN_EDGES, LEN_LABELS, mae) }], { label: 'MAE', xlabel: 'Length', domain: LEN_LABELS });
      C.lines(body(cAlpha), [{ rows: binned(idx, i => d.alpha[i], A_EDGES, A_LABELS, mae) }], { label: 'MAE', xlabel: 'True α', domain: A_LABELS });
    });
  }
  onLayout(page, draw);
  return page;
}

// ----- Task 2 -----

async function viewTask2() {
  const dim = state.dim; const d = await load(2, dim);
  const page = h('<div></div>');
  page.append(pageHead('Trajectory Analysis', 'Diffusion model',
    'Models that classify each trajectory into one of five diffusion models (ATTM, CTRW, FBM, LW, SBM), as in the Diffusion Model tab of AnDi-J. Scored on the test trajectories of the 1st AnDi Challenge and ranked by micro averaged F1 score; AUC is the micro averaged area under the ROC curve.'));
  const content = h('<div></div>');
  page.append(await fFields(2, () => draw(), { model: false }), content);

  function draw() {
    const idx = idxF(d, state.f, false); content.innerHTML = '';
    if (!idx.length) { content.append(h('<div class="card empty">No trajectories match these filters. Try widening the ranges.</div>')); return; }
    const lb = lbTask2(d, idx, dim); const team = pickTeam('task2', lb);
    const tCard = card('Leaderboard', `${idx.length.toLocaleString()} trajectories · ${dim}D · click a row to show that model in the plots above`, '');
    table(body(tCard), {
      id: 'task2', rows: lb, selected: team, defaultSort: { key: 'rank', dir: 1 },
      cols: [
        { key: 'rank', label: 'Rank', cls: 'rank', html: r => rankBadge(r.rank), sort: r => r.rank },
        { key: 'team', label: 'Method', cls: 'team', html: r => teamLabel(r.team) },
        { key: 'f1', label: 'F1 score', cls: 'num', html: r => metricCell(r.f1, lb, 'f1', false), sort: r => r.f1.value, dir: -1, title: 'Micro averaged F1, 95% CI' },
        { key: 'auc', label: 'AUC', cls: 'num', html: r => f3(r.auc), sort: r => r.auc, dir: -1 },
        { key: 'n', label: 'Trajectories', cls: 'num', html: r => r.f1.n.toLocaleString() },
      ],
      onClick: t => { state.sel.task2 = t; draw(); scrollToPlots(content); },
    });

    const td = teamData(2, dim, d, team); const row = lb.find(r => r.team === team);
    const roc = M.roc(td.prob, d.model, idx);
    content.append(detailHead(esc(team), lb.map(r => r.team), team, t => { state.sel.task2 = t; draw(); },
      [[f3(row.f1.value), 'F1 score'], [f3(roc.aucs.micro), 'AUC'], [`#${row.rank}`, `of ${lb.length}`]]));
    const g1 = h('<div class="grid grid-2"></div>'), g2 = h('<div class="grid grid-2" style="margin-top:16px"></div>');
    const cConf = card('Confusion matrix', 'Fraction of each true model assigned to each predicted model (rows sum to 1).');
    const cRoc = card('ROC curves', 'One vs rest for every model, plus micro and macro averages.');
    const legend = h('<div class="legend"></div>'); body(cRoc).before(legend); legend.style.padding = '10px 18px 0';
    const cLen = card('F1 score by trajectory length'), cA = card('F1 score by true α');
    g1.append(cConf, cRoc); g2.append(cLen, cA); content.append(g1, g2);
    tCard.style.marginTop = '28px'; content.append(tCard);
    const acc = (sub) => M.accuracy(td.pred, d.model, sub);
    requestAnimationFrame(() => {
      const cm = M.confusion(td.pred, d.model, idx);
      const cells = []; MODELS.forEach((t, i) => MODELS.forEach((p, j) => cells.push({ y: t, x: p, v: cm[i][j] })));
      C.heatmap(body(cConf), cells, { xlabel: 'Predicted model', ylabel: 'True model', xdomain: MODELS, ydomain: MODELS, label: 'Fraction', max: 1 });
      C.roc(body(cRoc), roc, legend);
      C.lines(body(cLen), [{ rows: binned(idx, i => d.length[i], LEN_EDGES, LEN_LABELS, acc) }], { label: 'F1', xlabel: 'Length', domain: LEN_LABELS, yDomain: [0, 1] });
      C.lines(body(cA), [{ rows: binned(idx, i => d.alpha[i], A_EDGES, A_LABELS, acc) }], { label: 'F1', xlabel: 'True α', domain: A_LABELS, yDomain: [0, 1] });
    });
  }
  onLayout(page, draw);
  return page;
}

// ----- Home -----

const EXT = 'target="_blank" rel="noopener"';
const ANDI1 = 'https://doi.org/10.1038/s41467-021-26320-w';
const ANDI2 = 'https://doi.org/10.1038/s41467-025-61949-x';
const REPO = 'https://github.com/gorkamunoz/andi-j';
const WIKI = 'https://github.com/gorkamunoz/andi-j/wiki';

function viewHome() {
  const page = h('<div class="home"></div>');
  page.append(pageHead('', 'Benchmark methods for anomalous diffusion',
    `This tool allows to benchmark methods on the analysis of diffusive single-particle trajectories of many different types.
    The benchmarks are based on the datasets and metrics of the two Anomalous Diffusion (AnDi) Challenges
     (<a href="${ANDI1}" ${EXT}>1st</a>, <a href="${ANDI2}" ${EXT}>2nd</a>). It also include comparisons to the bundled methods of the <a href="${REPO}" ${EXT}>AnDi-J Fiji plugin</a>.`));

  page.append(h(`<div class="grid grid-2">
    <section class="card home-card">
      <h2><a href="#/trajectory">Trajectory Analysis →</a></h2>
      <p>One label per trajectory: either the anomalous exponent α or the diffusion model among
         ATTM, CTRW, FBM, LW and SBM, as predicted by AnDi-J's Trajectory Analysis tab.
         <br> Datasets and methods available from the <a href="${ANDI1}" ${EXT}>1st AnDi Challenge</a>.</p>
    </section>
    <section class="card home-card">
      <h2><a href="#/segmentation">Trajectory segmentation →</a></h2>
      <p>Trajectories that switch between diffusive states. The goal is to predict the changepoints and, for every
         segment, the exponent α, the diffusion coefficient, and the diffusive state, as done in AnDi-J's
         Trajectory Segmentation tab.
         <br> Datasets and methods available from the <a href="${ANDI2}" ${EXT}>2nd AnDi Challenge</a>.</p>
    </section>
  </div>`));

  page.append(h(`<section class="card card-body home-refs">
    <h2>Learn more</h2>
      Find all the details on the benchmarks, datasets and methods in the <a href="${WIKI}" ${EXT}>AnDi-J Wiki</a>.
  </section>`));
  return page;
}
// The submission is kept in memory only, like the Trajectory Analysis uploads.
const UPLOAD_SEG = 'Your submission';

// Benchmark datasets of both submission pages (Zenodo record).
const DATASET_DOI = '10.5281/zenodo.23126182';
const DATASET_URL = `https://doi.org/${DATASET_DOI}`;
const datasetLink = (text) => `<a href="${DATASET_URL}" target="_blank" rel="noopener">${text}</a>`;
function datasetCard(what) {
  const c = card('Benchmark dataset', `Download the trajectories to run your method on.`);
  body(c).innerHTML = `<p class="small" style="margin:0">${what} is on Zenodo:
    ${datasetLink(`doi:${DATASET_DOI}`)}. Run your method on it, export the predictions in the format below
    and drop them here.</p>`;
  return c;
}

async function viewSegSubmit() {
  const page = h('<div></div>');
  page.append(pageHead('Trajectory segmentation', 'Submit a model',
    `Run your method on the ${datasetLink('2nd AnDi Challenge')}
     trajectory dataset and upload the predictions as a single .zip. Scoring happens in your browser with the
     challenge's own scorer: the archive is never sent anywhere. Your results appear as
     ${swatch('upload')}<b>“${UPLOAD_SEG}”</b> on the Analysis page until you reload.`));
  const grid = h('<div class="grid grid-main"></div>'); page.append(grid);

  const left = h('<div class="stack"></div>');
  const drop = h(`<label class="drop"><div class="drop-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/></svg></div>
    <b>Drop your submission .zip here</b><div class="muted small" style="margin-top:4px">or click to choose it · nothing leaves your computer</div>
    <input type="file" accept=".zip" hidden></label>`);
  const status = h('<div></div>');
  const fmtCard = card('Archive format', 'The same layout and file format as the challenge submission, zipped.');
  body(fmtCard).innerHTML = `
    <div class="format">track_2/
  exp_0/   fov_0.txt … fov_29.txt
  exp_1/   fov_0.txt … fov_29.txt
  …
  exp_11/  fov_0.txt … fov_29.txt</div>
    <p class="muted small" style="margin:10px 0 0">All twelve experiments and thirty fields of view are needed: the overall score is
    the average over every experiment weighted by its number of trajectories, which is how the leaderboard was built.
    A <code>track_2</code> folder at the root of the zip, or the experiment folders directly, both work.</p>
    <p class="muted small" style="margin:10px 0 0">One line per trajectory, comma separated, the challenge's single trajectory format:</p>
    <div class="format">traj_idx, D, α, state, cp, D, α, state, cp, …</div>
    <p class="muted small" style="margin:10px 0 0">One group of four per segment; the last changepoint of a trajectory is its length.</p>`;
  left.append(datasetCard('The single trajectory dataset of the 2nd AnDi Challenge (<code>track_2</code>, twelve experiments of thirty fields of view)'),
    drop, status, fmtCard);

  const right = card('Your submission', 'Scored with the challenge scorer, over all twelve experiments.');
  grid.append(left, right);

  const metrics = [
    { key: 'rmse_cp', label: 'RMSE (CP)', lower: true }, { key: 'jsc_cp', label: 'JI (CP)', lower: false },
    { key: 'mae_alpha', label: 'MAE (α)', lower: true }, { key: 'msle_k', label: 'MSLE (D)', lower: true },
    { key: 'f1_state', label: 'F1 (diffusive state)', lower: false },
  ];

  async function drawResult() {
    const b = body(right), up = state.segUpload;
    if (!up) { b.innerHTML = '<div class="empty">Nothing uploaded yet.</div>'; return; }
    const lb = await loadSeg('leaderboard');
    const rows = segRowsWithUpload(lb, up);
    const mine = rows.find(r => r.team === UPLOAD_SEG);
    b.innerHTML = `<div class="sub-item seg-sub"><div><b>${esc(up.name)}</b>
      <span class="muted small">${up.trajectories.toLocaleString()} trajectories · global rank ${mine.rank} of ${rows.length}</span></div>
      <div class="chips">${metrics.map(m =>
        `<span class="chip">${m.label} <b>${f3(mine[m.key])}</b> · rank ${mine[m.key + '_rank']}</span>`).join('')}</div>
      <div class="sub-actions"><a class="btn primary" href="#/segmentation">View on the benchmark</a><button class="btn" id="seg-rm">Remove</button></div></div>`;
    $('#seg-rm', b).addEventListener('click', () => { state.segUpload = null; status.innerHTML = ''; drawResult(); });
  }

  const say = (ok, html) => { status.innerHTML = `<div class="status ${ok ? 'ok' : 'err'}">${ok ? ICON_OK : ICON_ERR}<div>${html}</div></div>`; };

  async function handle(file) {
    if (!file) return;
    say(true, `Reading <b>${esc(file.name)}</b>…`);
    try {
      const files = await S.unzip(await file.arrayBuffer());
      const exps = S.collect(files);
      if (!exps.size) throw new Error('No exp_<n>/fov_<m>.txt files found in the archive.');
      say(true, `Scoring ${exps.size} experiments…`);
      // Let the browser paint the message before the scoring blocks it.
      await new Promise(r => setTimeout(r, 20));
      const truth = await loadSeg('truth');
      const res = S.scoreSubmission(truth, exps);
      state.segUpload = { name: file.name, ...res };
      say(true, `Scored <b>${esc(file.name)}</b>: ${res.trajectories.toLocaleString()} trajectories over ${res.perExp.length} experiments.`);
    } catch (e) {
      state.segUpload = null;
      say(false, `Could not score this archive. ${esc(e.message)}`);
    }
    drawResult();
  }

  $('input', drop).addEventListener('change', e => handle(e.target.files[0]));
  drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', e => { e.preventDefault(); drop.classList.remove('over'); handle(e.dataTransfer.files[0]); });
  drawResult();
  return page;
}

// The leaderboard rows plus the upload, with every rank and the MRR recomputed over the lot, the
// way the challenge does it (so uploading shifts the published ranks, as on the Trajectory pages).
function segRowsWithUpload(lb, up) {
  if (!up) return lb.rows;
  const rows = lb.rows.map(r => ({ ...r }));
  rows.push({ team: UPLOAD_SEG, group: 'upload', ...up.overall });
  const mrr = new Array(rows.length).fill(0);
  for (const m of lb.metrics) {
    const order = rows.map((r, i) => i).sort((a, b) =>
      m.lower ? rows[a][m.key] - rows[b][m.key] : rows[b][m.key] - rows[a][m.key]);
    let rank = 0;
    order.forEach((i, pos) => {
      // Competition ranking: ties share the best rank.
      if (pos === 0 || rows[i][m.key] !== rows[order[pos - 1]][m.key]) rank = pos + 1;
      rows[i][m.key + '_rank'] = rank;
      mrr[i] += 1 / rank;
    });
  }
  rows.forEach((r, i) => (r.mrr = mrr[i] / lb.metrics.length));
  const byMrr = rows.map((r, i) => i).sort((a, b) => rows[b].mrr - rows[a].mrr);
  let g = 0;
  byMrr.forEach((i, pos) => {
    if (pos === 0 || rows[i].mrr !== rows[byMrr[pos - 1]].mrr) g = pos + 1;
    rows[i].rank = g;
  });
  return rows.sort((a, b) => a.rank - b.rank || a.team.localeCompare(b.team));
}

// ----- Segmentation Analysis -----

// Everything on this page comes from data/segmentation/*.json, built by build_segmentation_data.py
// out of the 2nd AnDi Challenge results (single trajectory task, trajectory track), which is the
// task of AnDi-J's Segmentation Analysis tab. The AnDi-J default model is one more entry there and
// is ranked together with the challenge teams.
const ANDIJ_SEG = 'AnDi-J default';
const segCache = {};
const loadSeg = (name) => (segCache[name] ??= fetch(`data/segmentation/${name}.json`, { cache: 'no-cache' })
  .then(r => { if (!r.ok) throw new Error(`Could not load data/segmentation/${name}.json (${r.status})`); return r.json(); })
  .catch(e => { delete segCache[name]; throw e; }));

// Panel order of the paper figures, so the same metric always sits in the same column.
const SEG_ORDER = ['jsc_cp', 'rmse_cp', 'msle_k', 'mae_alpha', 'f1_state'];
const SEG_LOG = { msle_k: true };
const SEG_STATES = ['imm.', 'conf.', 'free', 'dir.'];
// The three metrics of the explorer panel, with the short names used on its axes and readout.
const SEG_EXPLORE = [
  { key: 'mae_alpha', short: 'MAE', lower: true },
  { key: 'msle_k', short: 'MSLE', lower: true, log: true },
  { key: 'f1_state', short: 'F1', lower: false },
];
const segAxis = (m) => `${m.label} ${m.lower ? '↓' : '↑'}`;
const med = (v) => { const s = [...v].sort((a, b) => a - b), h = s.length / 2; return s.length % 2 ? s[h | 0] : (s[h - 1] + s[h]) / 2; };

async function viewSegmentation() {
  const [lb, perExp, vsCp, single, clf] = await Promise.all(
    ['leaderboard', 'per_experiment', 'metrics_vs_cp', 'single_state', 'classification'].map(loadSeg));

  const metrics = Object.fromEntries(lb.metrics.map(m => [m.key, m]));
  const order = SEG_ORDER.map(k => metrics[k]);
  const best = vsCp.teams.filter(t => t !== ANDIJ_SEG); // the five best challenge teams
  const grey = () => C.css('--neutral-mark');
  const groupColor = (g) => (g === 'andij' ? C.andijColor()
    : g === 'upload' ? C.css(GROUPS.upload.color) : grey());
  const teamColor = (t) => (t === ANDIJ_SEG ? C.andijColor() : C.segSeries()[best.indexOf(t)] ?? grey());
  const singleStateExp = perExp.experiments.find(e => e.single_state).exp;

  const page = h('<div class="seg-page"></div>');
  page.append(pageHead('', 'Trajectory segmentation benchmark',
    `The Trajectory segmentation tab of AnDi-J predicts the changepoints at which diffusive changes happen in an input trajectory and gives every segment an anomalous
     exponent α, a diffusion coefficient D and a diffusive state. This page compares <b>the AnDi-J default model</b> (${swatch('andij')})
     with other methods available for AnDi-J (${swatch('model')}), the methods of the
     <a href="https://doi.org/10.1038/s41467-025-61949-x" target="_blank" rel="noopener">2nd AnDi Challenge</a>
     and your submissions (${swatch('upload')}). See the Submit tab for information on how to submit your predictions.`));

  // A card with one or more rows of equally sized chart panels.
  const panelCard = (title, sub, layout, legend, note, readout) => {
    const c = card(title, sub, 'card-body');
    if (legend) body(c).append(h('<div class="legend"></div>'));
    const out = [];
    for (const { grid, n, titles } of layout) {
      const wrap = h(`<div class="panels ${grid}"></div>`);
      for (let i = 0; i < n; i++) {
        const p = h(`<div class="panel">${titles ? `<div class="panel-head">${titles[i]}</div>` : ''}<div class="chart"></div></div>`);
        wrap.append(p); out.push($('.chart', p));
      }
      body(c).append(wrap);
    }
    if (readout) body(c).append(h('<div class="pick-readout"></div>'));
    if (note) body(c).append(h(`<p class="small muted" style="margin:12px 0 0">${note}</p>`));
    page.append(c);
    return out;
  };

  const dot = (col, txt) => `<span><i class="dot" style="background:${col}"></i>${txt}</span>`;
  const hollow = (txt) => `<span><i class="ring"></i>${txt}</span>`;
  const andijLegend = () => dot(C.andijColor(), ANDIJ_SEG);

  // Row 1: the two overall scatters. The last ranked method is left out of them, as in the paper
  // figures: its errors are an order of magnitude above the rest and would flatten the whole field.
  const outlier = lb.rows[lb.rows.length - 1].team;
  // The leaderboard, plus a scored submission when there is one (ranks recomputed over the lot).
  const allRows = () => segRowsWithUpload(lb, state.segUpload);
  const scatters = panelCard('Overall performance',
    `Average scores over all experiments in the benchmark dataset experiments. Select the desired metrics in the leftmost plot for visualization.`,
    [{ grid: 'grid-2', n: 2 }], true);

  // The left panel is a metric explorer: one row of buttons per axis picks what it plots. It reads
  // out on click rather than on hover, so only the AnDi-J default model carries a label.
  const explorer = scatters[0].parentElement;
  const axisRow = (ax) => `<div class="metric-toggle"><span class="field-label">${ax.toUpperCase()} axis</span><div class="seg" data-ax="${ax}">${
    SEG_EXPLORE.map(m => `<button data-k="${m.key}" class="${state.seg[ax] === m.key ? 'on' : ''}">${m.short}</button>`).join('')}</div></div>`;
  const toggles = h(`<div class="axis-pick">${axisRow('y')}${axisRow('x')}</div>`);
  const readout = h('<div class="pick-readout"></div>');
  explorer.prepend(toggles);
  explorer.append(readout);
  // Keep the two charts of the row on the same baseline despite the extra controls on the left.
  scatters[1].parentElement.prepend(h('<div class="axis-pick" aria-hidden="true"><span class="field-label">&nbsp;</span></div>'));
  toggles.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
    const ax = b.parentElement.dataset.ax, other = ax === 'x' ? 'y' : 'x', k = b.dataset.k;
    if (state.seg[ax] === k) return;
    // Picking the metric the other axis already shows swaps them, rather than plotting it twice.
    if (state.seg[other] === k) state.seg[other] = state.seg[ax];
    state.seg[ax] = k;
    toggles.querySelectorAll('.seg').forEach(g => g.querySelectorAll('button')
      .forEach(x => x.classList.toggle('on', state.seg[g.dataset.ax] === x.dataset.k)));
    drawOverall();
  }));
  // Row 2: one panel per metric, across the experiments.
  const perExpPanels = panelCard('Performance per experiment',
    'Every method on each experiment, with the median of the five best challenge teams as a reference.',
    [{ grid: 'grid-5', n: 5 }], true,
    `Experiments 3–5 and 6–7 each share the same underlying physical model. The changepoint metrics are undefined on
     experiment ${singleStateExp}, where trajectories have a single state and therefore no changepoint (dashed line).
     ${esc(outlier)} is left out here too, as in the overall performance panels.`, true);
  // Each panel of row 2 can be blown up to a 2x2 block, with the other four kept beside it.
  const perExpWrap = perExpPanels[0].closest('.panels');
  const layoutPanels = () => {
    const max = state.seg.max;
    perExpWrap.classList.toggle('grid-max', max != null);
    perExpWrap.classList.toggle('grid-5', max == null);
    perExpPanels.forEach((chart, i) => {
      chart.parentElement.classList.toggle('big', i === max);
      $('.panel-max', chart.parentElement).textContent = i === max ? '\u2715' : '\u2921';
    });
  };
  perExpPanels.forEach((chart, i) => {
    const b = h(`<button class="panel-max" title="Enlarge this plot">\u2921</button>`);
    b.addEventListener('click', () => { state.seg.max = state.seg.max === i ? null : i; drawPerExp(); });
    chart.parentElement.prepend(b);
  });

  // Row 3: the same metrics against how many changepoints a trajectory has.
  const cpPanels = panelCard('Performance vs. number of changepoints',
    'Scores restricted to the trajectories with a given number of changepoints, averaged over the experiments and weighted by their number of trajectories. Click a line to see which method it is.',
    [{ grid: 'grid-5', n: 5 }], false, null, true);
  // Row 4: the single state experiment.
  const ssPanels = panelCard('Single state experiment',
    `One of the benchmark's experiments contains trajectories with no changepoints, so every predicted changepoint is a false positive.
     This can be used to compare the accuracy of the method against non-segmentation methods of the Trajectory Analysis module.`,
    [{ grid: 'grid-2', n: 2 }], true, null, true);
  // Row 5: the diffusive state populations, experiment by experiment.
  const clfTitles = clf.experiments.map(e => `Experiment ${e.exp}`);
  const clfPanels = panelCard('Diffusive state classification',
    'Distribution of predicted diffusive state by the five best challenge teams and the AnDi-J default model, against the ground truth.',
    [{ grid: 'grid-5', n: 5, titles: clfTitles.slice(0, 5) }, { grid: 'grid-5', n: 4, titles: clfTitles.slice(5) }], true, null, true);

  // Ranking table.
  const cRank = card('Ranking', `Overall scoring over the benchmark dataset following the challenge's rankings:
    the mean reciprocal rank (MRR) over the five metrics.`, 'card-body');
  page.append(cRank);
  const PIN = { andij: ['var(--series-1)', 'AnDi-J default model'], upload: ['var(--series-2)', 'Your submission'],
    challenge: ['var(--neutral-mark)', '2nd AnDi Challenge participant'] };
  const segTeam = (r) => { const [c, t] = PIN[r.group] || PIN.challenge;
    return `<span class="mname"><i class="pin" style="background:${c}" title="${t}"></i>${esc(r.team)}${
      r.group === 'upload' ? '<span class="you">You</span>' : ''}</span>`; };
  const rankCell = (r, m) => `<span class="rankcell">${f3(r[m.key])}<span class="rk">(${r[m.key + '_rank']})</span></span>`;
  const drawTable = () => table(body(cRank), {
    id: 'segmentation', rows: allRows(), selected: state.sel.segmentation, defaultSort: { key: 'rank', dir: 1 },
    cols: [
      { key: 'rank', label: 'Rank', cls: 'rank', html: r => rankBadge(r.rank), sort: r => r.rank, dir: 1 },
      { key: 'team', label: 'Method', cls: 'team', html: segTeam },
      ...order.map(m => ({ key: m.key, label: segAxis(m), cls: 'num', html: r => rankCell(r, m), sort: r => r[m.key + '_rank'], dir: 1 })),
      { key: 'mrr', label: 'MRR ↑', cls: 'num', html: r => f3(r.mrr), sort: r => -r.mrr, dir: 1 },
    ],
    onClick: (team) => { state.sel.segmentation = state.sel.segmentation === team ? null : team; drawTable(); },
  });
  drawTable();

  const explorerRows = () => allRows().filter(r => r.team !== outlier).map(r => ({ ...r, color: groupColor(r.group) }));

  // Each section keeps its own selected method. Nothing reads out on hover any more: you click a
  // point, a line or a bar and the line under the section spells out what it is.
  const picked = (card) => state.seg.pick[card];
  const picker = (card, redraw) => (d) => {
    const team = d && (d.team ?? d.name ?? d);
    state.seg.pick[card] = team === picked(card) ? null : (team || null);
    redraw();
  };
  const say = (el, parts) => { el.innerHTML = parts || '<span class="muted">Click a method to read its scores.</span>'; };
  const readoutOf = (el) => $('.pick-readout', el.closest('.card'));

  // ----- Row 1: overall performance -----
  function drawOverall() {
    const rows = explorerRows();
    const by = (k) => SEG_EXPLORE.find(m => m.key === k);
    const ax = by(state.seg.x), ay = by(state.seg.y);
    const dir = (m) => (m.lower ? 'lower is better' : 'higher is better');
    const sel = picked('overall'), pick = picker('overall', drawOverall);
    // Both axes point so that better is right and up, which keeps the best methods in one corner.
    C.segScatter(scatters[0], rows, {
      x: ax.key, y: ay.key, xreverse: ax.lower, yreverse: ay.lower, xlog: ax.log, ylog: ay.log,
      xlabel: `${ax.short} (${dir(ax)}) →`, ylabel: `↑ ${ay.short} (${dir(ay)})`,
      hover: false, labels: 'emph', selected: sel, onPick: pick });
    C.segScatter(scatters[1], rows, { x: 'rmse_cp', y: 'jsc_cp', xreverse: true,
      xlabel: `${metrics.rmse_cp.label} (lower is better) →`, ylabel: `↑ ${metrics.jsc_cp.label} (higher is better)`,
      hover: false, labels: 'emph', selected: sel, onPick: pick });

    const hit = sel && rows.find(r => r.team === sel);
    $('.legend', scatters[0].closest('.card')).innerHTML = andijLegend()
      + hollow('2nd AnDi Challenge participant')
      + (state.segUpload ? dot(C.css(GROUPS.upload.color), UPLOAD_SEG) : '');
    say(readout, hit && `<b>${esc(hit.team)}</b>${order.map(m =>
      `<span>${C.plain(m.label)} = ${f3(hit[m.key])}</span>`).join('')}`);
  }

  // ----- Row 2: one panel per metric, across the experiments -----
  function drawPerExp() {
    const sel = picked('perexp'), pick = picker('perexp', drawPerExp);
    const exps = perExp.experiments.map(e => e.exp);
    $('.legend', perExpPanels[0].closest('.card')).innerHTML =
      andijLegend() + hollow('Median of the 5 best teams') + '<span><i class="faint"></i>Other participants</span>';
    layoutPanels();
    order.forEach((m, i) => {
      // A changepoint RMSE of exactly 0 means the method predicted no changepoint at all, which is
      // not a score; those entries are left out, as in the paper figures.
      const drop = (v) => m.key === 'rmse_cp' && v === 0;
      const cloud = [], middle = [], andij = [];
      for (const e of perExp.experiments) {
        const top = [];
        for (const [team, v] of Object.entries(e.values)) {
          if (team === outlier || drop(v[m.key])) continue;
          if (team === ANDIJ_SEG) { andij.push({ exp: e.exp, v: v[m.key], team }); continue; }
          cloud.push({ exp: e.exp, v: v[m.key], team });
          if (best.includes(team)) top.push(v[m.key]);
        }
        if (top.length) middle.push({ exp: e.exp, v: med(top) });
      }
      const mineRows = (state.segUpload?.perExp || []).filter(r => r.exp)
        .map(r => ({ exp: r.exp, v: r[m.key], team: UPLOAD_SEG }))
        .filter(r => !drop(r.v));
      C.segPerExp(perExpPanels[i], { rows: cloud, median: middle, andij, mine: mineRows, exps, label: segAxis(m),
        log: !!SEG_LOG[m.key], skip: m.key === 'rmse_cp' ? [singleStateExp] : [],
        height: state.seg.max === i ? 430 : 210, selected: sel, onPick: pick });
    });
    const hit = sel && allRows().find(r => r.team === sel);
    say(readoutOf(perExpPanels[0]), hit && `<b>${esc(hit.team)}</b>${order.map(m =>
      `<span>${C.plain(m.label)} = ${f3(hit[m.key])}</span>`).join('')}`);
  }

  // ----- Row 3: the same metrics against how many changepoints a trajectory has -----
  function drawCp() {
    const sel = picked('cp'), pick = picker('cp', drawCp);
    let shown = null;
    order.forEach((m, i) => {
      const mi = vsCp.metrics.findIndex(x => x.key === m.key);
      const from = m.key === 'rmse_cp' ? 1 : 0; // with no changepoint the changepoint RMSE is always 0
      const series = vsCp.teams.map((team, ti) => ({
        name: team, color: team === ANDIJ_SEG ? C.andijColor() : C.css('--neutral-mark'),
        emph: team === ANDIJ_SEG,
        rows: vsCp.changepoints.slice(from).map((k, ki) => ({ k, v: vsCp.values[ki + from][mi][ti] })),
      }));
      if (state.segUpload) series.push({
        name: UPLOAD_SEG, color: C.css(GROUPS.upload.color), emph: true,
        rows: state.segUpload.vsCp.filter(r => r.k >= from).map(r => ({ k: r.k, v: r[m.key] })),
      });
      if (sel) { const s = series.find(x => x.name === sel); if (s) shown = shown ?? s; }
      C.segLines(cpPanels[i], series, { label: segAxis(m), xlabel: 'Changepoints per trajectory',
        domain: [vsCp.changepoints[from], vsCp.changepoints[vsCp.changepoints.length - 1]],
        log: !!SEG_LOG[m.key], selected: sel, onPick: pick });
    });
    const hit = sel && allRows().find(r => r.team === sel);
    say(readoutOf(cpPanels[0]), hit && `<b>${esc(hit.team)}</b>${order.map(m =>
      `<span>${C.plain(m.label)} = ${f3(hit[m.key])}</span>`).join('')}`);
  }

  // ----- Row 4: the single state experiment -----
  function drawSingle() {
    const sel = picked('single'), pick = picker('single', drawSingle);
    const msd = single.rows.find(r => r.group === 'reference');
    const ssRows = single.rows.filter(r => r.fpr != null).map(r => ({ ...r, color: groupColor(r.group) }));
    const mine = state.segUpload?.perExp.find(r => r.fpr !== undefined);
    if (mine) ssRows.push({ team: UPLOAD_SEG, group: 'upload', color: groupColor('upload'),
      fpr: mine.fpr, msle_k: mine.msle_k, mae_alpha: mine.mae_alpha });
    $('.legend', ssPanels[0].closest('.card')).innerHTML =
      andijLegend() + hollow('2nd AnDi Challenge participant')
      + (mine ? dot(C.css(GROUPS.upload.color), UPLOAD_SEG) : '')
      + '<span><i class="dash"></i>MSD fit</span>';
    // Both metrics and the false positive rate are lower-is-better, so both axes run backwards and
    // the best methods end up in the top right corner, as in the overall performance panels.
    const fpr = 'False positive rate (lower is better) →';
    C.segScatter(ssPanels[0], ssRows, { x: 'fpr', y: 'msle_k', ylog: true, digits: 4,
      xreverse: true, yreverse: true,
      xlabel: fpr, ylabel: `↑ ${metrics.msle_k.label} (lower is better)`,
      rules: [{ v: msd.msle_k, name: msd.team }], hover: false, labels: 'emph', selected: sel, onPick: pick });
    C.segScatter(ssPanels[1], ssRows, { x: 'fpr', y: 'mae_alpha', ylog: true,
      xreverse: true, yreverse: true,
      xlabel: fpr, ylabel: `↑ ${metrics.mae_alpha.label} (lower is better)`,
      rules: [{ v: msd.mae_alpha, name: msd.team }], hover: false, labels: 'emph', selected: sel, onPick: pick });

    const hit = sel && ssRows.find(r => r.team === sel);
    say(readoutOf(ssPanels[0]), hit && `<b>${esc(hit.team)}</b>`
      + `<span>False positive rate = ${f3(hit.fpr)}</span>`
      + `<span>${C.plain(metrics.msle_k.label)} = ${f3(hit.msle_k, 4)}</span>`
      + `<span>${C.plain(metrics.mae_alpha.label)} = ${f3(hit.mae_alpha)}</span>`);
  }

  // ----- Row 5: the diffusive state populations, experiment by experiment -----
  function drawClf() {
    const sel = picked('clf'), pick = picker('clf', drawClf);
    $('.legend', clfPanels[0].closest('.card')).innerHTML =
      andijLegend() + hollow('2nd AnDi Challenge participant')
      + (state.segUpload ? dot(C.css(GROUPS.upload.color), UPLOAD_SEG) : '')
      + '<span><i class="dash"></i>Ground truth</span>';
    const teams = clf.teams.map(t => ({ name: t, color: t === ANDIJ_SEG ? C.andijColor() : C.css('--neutral-mark') }));
    if (state.segUpload) teams.push({ name: UPLOAD_SEG, color: C.css(GROUPS.upload.color) });
    clf.experiments.forEach((e, i) => {
      const bars = [];
      for (const team of clf.teams) clf.states.forEach((s, si) => bars.push({ team, state: s, v: e.values[team][si] }));
      const up = state.segUpload?.states.find(x => x.exp === e.exp);
      if (up) clf.states.forEach((s, si) => bars.push({ team: UPLOAD_SEG, state: s, v: up.values[si] }));
      C.segBars(clfPanels[i], { rows: bars, truth: clf.states.map((s, si) => ({ state: s, v: e.truth[si] })),
        states: clf.states, short: SEG_STATES, teams, ylabel: i % 5 === 0 ? 'Share of time points' : null,
        selected: sel, onPick: pick });
    });
    const hit = sel && clf.teams.includes(sel);
    say(readoutOf(clfPanels[0]), hit && `<b>${esc(sel)}</b>` + clf.experiments.map((e, i) =>
      `<span>Exp ${e.exp}: ${clf.states.map((s, si) => `${SEG_STATES[si]} ${(e.values[sel][si] * 100).toFixed(0)}%`).join(' / ')}</span>`).slice(0, 3).join(''));
  }

  function draw() { drawOverall(); drawPerExp(); drawCp(); drawSingle(); drawClf(); }

  onLayout(page, draw);
  return page;
}

// ----- Submit -----

const ICON_OK = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--good)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>';
const ICON_ERR = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--critical)" stroke-width="2.5" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v6M12 16.5v.5"/></svg>';

function parseRows(text) {
  const rows = [];
  for (const line of text.split(/\r?\n/)) {
    const s = line.trim(); if (!s) continue;
    const v = s.split(/[;,\s]+/).map(Number);
    if (v.some(Number.isNaN)) throw new Error(`Could not read the line "${s.slice(0, 60)}"`);
    rows.push(v);
  }
  if (!rows.length) throw new Error('The file is empty.');
  return rows;
}

function detectTask(filename, rows) {
  const m = filename.match(/task\s*_?([12])/i) || filename.match(/([12])\.txt$/i);
  if (m) return +m[1];
  const w = rows[0].length;
  if (w === 2) return 1;
  if (w === 6) return 2;
  throw new Error('Could not tell which task this file is for. Name it task1.txt (exponent) or task2.txt (model), or pick the task above.');
}

async function ingest(task, rows) {
  const width = task === 1 ? 2 : 6;
  const bad = rows.find(r => r.length !== width);
  if (bad) throw new Error(`${task === 1 ? 'Exponent' : 'Diffusion model'} files need ${width} values per line separated by ";", found a line with ${bad.length}.`);
  if (task === 2) {
    const notProb = rows.find(r => r.slice(1).some(x => x < 0 || x > 1));
    if (notProb) throw new Error('Diffusion model files need 5 probabilities between 0 and 1 after the dimension (is this a Task 3 file?).');
  }
  const dims = [], skipped = [];
  for (const dim of [1, 2, 3]) {
    const mine = rows.filter(r => r[0] === dim);
    if (!mine.length) continue;
    await load(task, dim); const n = DIM_N[`${task}_${dim}`];
    if (mine.length < n) { skipped.push(`${dim}D (${mine.length} of ${n} lines)`); continue; }
    const use = mine.slice(0, n);
    if (task === 1) state.upload[1][dim] = use.map(r => r[1]);
    if (task === 2) {
      state.upload[2][dim] = {
        pred: use.map(r => { let b = 1; for (let k = 2; k <= 5; k++) if (r[k] > r[b]) b = k; return b - 1; }),
        prob: [0, 1, 2, 3, 4].map(k => use.map(r => r[k + 1] * 100000)),
      };
    }
    dims.push(dim);
  }
  return { dims, skipped };
}

async function scoreSummary(task) {
  const out = [];
  for (const dim of [1, 2, 3]) {
    if (!state.upload[task][dim]) continue;
    const d = await load(task, dim);
    if (task === 1) { const lb = lbTask1(d, idxF(d, DEFAULT_F()), dim); const r = lb.find(x => x.team === UPLOAD); out.push({ dim, text: `MAE <b>${f3(r.mae.value)}</b>`, rank: r.rank, of: lb.length }); }
    if (task === 2) { const lb = lbTask2(d, idxF(d, DEFAULT_F(), false), dim, false); const r = lb.find(x => x.team === UPLOAD); out.push({ dim, text: `F1 <b>${f3(r.f1.value)}</b>`, rank: r.rank, of: lb.length }); }
  }
  return out;
}

async function viewSubmit() {
  const page = h('<div></div>');
  page.append(pageHead('Trajectory Analysis', 'Submit a model',
    `Run AnDi-J (or any other method) on the ${datasetLink('AnDi test dataset')} and upload the predictions. Scoring happens in your browser: the file is never sent anywhere. Your results appear as “Your submission” in every table until you reload the page.`));
  const grid = h('<div class="grid grid-main"></div>'); page.append(grid);

  const left = h('<div class="stack"></div>');
  const drop = h(`<label class="drop"><div class="drop-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/></svg></div>
    <b>Drop task1.txt or task2.txt here</b><div class="muted small" style="margin-top:4px">or click to choose files · one file per task</div>
    <input type="file" accept=".txt,.csv" multiple hidden></label>`);
  const status = h('<div></div>');
  const taskSel = selectField('Predictions for', ['auto', 1, 2], 'auto', () => {}, o => (o === 'auto' ? 'Detect from file' : o === '1' || o === 1 ? 'Exponent α (task 1)' : 'Diffusion model (task 2)'));
  const fmtCard = card('File format', 'Same format as the AnDi Challenge: one line per trajectory, values separated by “;”, first value is the dimension.');
  body(fmtCard).innerHTML = `
    <ol class="steps">
      <li><b>task1.txt</b>, exponent: <code>dim; α</code></li>
      <li><b>task2.txt</b>, model: <code>dim; p(attm); p(ctrw); p(fbm); p(lw); p(sbm)</code></li>
    </ol>
    <div class="format">1;0.734\n1;1.215\n2;0.402\n…</div>
    <p class="muted small" style="margin:10px 0 0">Lines must follow the order of the test set. You can submit predictions for any of the dimensions (1D, 2D, 3D); the results are shown for the dimensions you include, and the others can be left out. The AnDi-J default model is only available for 2D at the moment.</p>`;
  const opts = h('<div class="card filters" style="margin:0"></div>'); opts.append(taskSel);
  left.append(datasetCard('The test set of the 1st AnDi Challenge (Tasks 1 and 2, 1D, 2D and 3D)'),
    drop, opts, status, fmtCard);

  const right = card('Your submissions', 'Scored on the full test set of each dimension.');
  grid.append(left, right);

  async function drawSubs() {
    const b = body(right);
    const tasks = [1, 2].filter(t => Object.keys(state.upload[t]).length);
    if (!tasks.length) { b.innerHTML = '<div class="empty">Nothing uploaded yet.</div>'; return; }
    b.innerHTML = '<div class="sub-list"></div>';
    for (const t of tasks) {
      const s = await scoreSummary(t);
      const info = state.uploadInfo[t];
      const item = h(`<div class="sub-item"><div class="grow"><b>${t === 1 ? 'Exponent α' : 'Diffusion model'}</b> <span class="muted small">${esc(info?.filename ?? '')}</span>
        <div class="chips" style="margin-top:6px">${s.map(x => `<span class="chip">${x.dim}D · ${x.text} · rank <b>${x.rank}</b>/${x.of}</span>`).join('')}</div></div>
        <a class="btn" href="#/trajectory/${t === 1 ? 'alpha' : 'model'}">View</a><button class="btn" data-rm="${t}">Remove</button></div>`);
      item.querySelector('[data-rm]').addEventListener('click', () => { state.upload[t] = {}; delete state.uploadInfo[t]; status.innerHTML = ''; drawSubs(); });
      $('.sub-list', b).append(item);
    }
  }

  async function handle(files) {
    status.innerHTML = '';
    for (const file of files) {
      const msg = h(`<div class="status"><div class="spinner"></div><div>Scoring <b>${esc(file.name)}</b>…</div></div>`); status.append(msg);
      try {
        const rows = parseRows(await file.text());
        const pick = taskSel.querySelector('select').value;
        const task = pick === 'auto' ? detectTask(file.name, rows) : +pick;
        const { dims, skipped } = await ingest(task, rows);
        if (!dims.length) throw new Error(`No complete dimension found in this file.${skipped.length ? ' Too few lines for ' + skipped.join(', ') + '.' : ''}`);
        state.uploadInfo[task] = { filename: file.name };
        ['task' + task].forEach(v => (state.sel[v] = UPLOAD));
        msg.className = 'status ok';
        msg.innerHTML = `${ICON_OK}<div><b>${esc(file.name)}</b> scored for the ${task === 1 ? 'exponent' : 'diffusion model'} in ${dims.map(x => x + 'D').join(', ')}.${skipped.length ? ` <span class="muted">Skipped ${skipped.join(', ')}.</span>` : ''}</div>`;
      } catch (e) {
        msg.className = 'status err';
        msg.innerHTML = `${ICON_ERR}<div><b>${esc(file.name)}</b>: ${esc(e.message)}</div>`;
      }
    }
    drawSubs();
  }

  const input = drop.querySelector('input');
  input.addEventListener('change', () => { handle([...input.files]); input.value = ''; });
  drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', e => { e.preventDefault(); drop.classList.remove('over'); handle([...e.dataTransfer.files]); });
  drawSubs();
  return page;
}

// ---------- Routing and theme ----------

function route() {
  let v = location.hash.replace(/^#\/?/, '').replace(/\/$/, '') || 'overview';
  if (ALIASES[v]) { location.replace(`#/${ALIASES[v]}`); return; }
  state.view = VIEWS[v] ? v : 'overview';
  renderView();
  window.scrollTo(0, 0);
}
window.addEventListener('hashchange', route);

$('#theme-btn').addEventListener('click', () => {
  const root = document.documentElement;
  const dark = root.dataset.theme ? root.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  root.dataset.theme = dark ? 'light' : 'dark';
  try { localStorage.setItem('andij-theme', root.dataset.theme); } catch (e) {}
  renderView();
});
matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', () => renderView());

route();
