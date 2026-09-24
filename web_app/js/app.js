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
const methodsReady = fetch('data/methods.json').then(r => (r.ok ? r.json() : {})).catch(() => ({})).then(m => (METHODS = m || {}));
const groupOf = (t) => (t === UPLOAD ? 'upload' : METHODS[t]?.group in GROUPS ? METHODS[t].group : 'challenge');
const DIM_N = {}; // number of test trajectories per task and dimension, filled when data loads

const DEFAULT_F = () => ({ model: 'All', snr: 'All', alpha: [0, 2], length: [10, 1000] });

const state = {
  view: 'overview',
  dim: 1,
  f: DEFAULT_F(),        // filters shared by all views
  sel: { overview: null, task1: null, task2: null },
  sort: {},
  upload: { 1: {}, 2: {} }, // upload[task][dim] -> predictions shaped like a team's data
  uploadInfo: {},                  // uploadInfo[task] -> { filename, dims, skipped }
};

// ---------- Data ----------

const cache = {};
function load(task, dim) {
  const key = `${task}_${dim}`;
  if (!cache[key]) {
    cache[key] = fetch(`data/task${task}_${dim}d.json`)
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
};
const ALIASES = { task1: 'trajectory/alpha', task2: 'trajectory/model', submit: 'trajectory/submit' }; // old links
let renderToken = 0;

async function renderView() {
  const token = ++renderToken;
  const main = $('#main');
  const section = state.view.split('/')[0];
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.view === section));
  $('#subnav').hidden = section !== 'trajectory';
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
    <div class="scatter-tools"><span class="legend" id="sc-legend"></span><button class="btn small" id="sc-reset">Reset view</button></div></div>
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

// ----- Placeholder pages -----

// Overview and Segmentation Analysis are left blank for now.
function viewHome() { return h('<div class="blank"></div>'); }
function viewSegmentation() { return h('<div class="blank"></div>'); }

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
    'Run AnDi-J (or any other method) on the <a href="https://drive.google.com/drive/folders/1h2cg1RNoKbhTiiPSNCHyav2NaRA6TGdR?usp=sharing" target="_blank" rel="noopener">AnDi test dataset</a> and upload the predictions. Scoring happens in your browser: the file is never sent anywhere. Your results appear as “Your submission” in every table until you reload the page.'));
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
    <p class="muted small" style="margin:10px 0 0">Lines must follow the order of the test set. Dimensions you did not attempt can be left out.</p>`;
  const opts = h('<div class="card filters" style="margin:0"></div>'); opts.append(taskSel);
  left.append(drop, opts, status, fmtCard);

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
