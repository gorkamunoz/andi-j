// Scoring of a Trajectory segmentation submission, in the visitor's browser.
//
// This is a port of andi_datasets.utils_challenge.error_SingleTraj_dataset, the scorer the 2nd AnDi
// Challenge used, so an upload is measured exactly the way the published leaderboard was. It is
// checked against the AnDi-J default model's official per-experiment scores (see README).
// Uploaded files never leave the computer.

const S = {};

// Error bounds of the challenge scorer (_get_error_bounds), and the metric caps.
const ERR_ALPHA = 2, ERR_D = 1e5, ERR_S = 0, ERR_CP = 10;
const MAX_ALPHA_ERR = 1.999;                                  // |bound_alpha[0] - bound_alpha[1]|
const D_LO = 1e-12, D_HI = 1e6;                               // models_phenom().bound_D
const MAX_MSLE = (Math.log1p(D_LO) - Math.log1p(D_HI)) ** 2;

// ---------- Hungarian algorithm ----------

// Minimum cost assignment on a rectangular matrix. This is a port of scipy's
// linear_sum_assignment (rectangular_lsap.cpp, Crouse 2016) rather than a generic Hungarian
// solver, because the cost matrices here are full of ties and the metric depends on which of the
// equally cheap assignments is chosen: scipy prefers an unassigned column and scans the remaining
// columns back to front, so those rules have to be reproduced exactly.
const solveLsap = (cost, nr, nc) => {
  const INF = Infinity;
  const u = new Float64Array(nr), v = new Float64Array(nc);
  const shortest = new Float64Array(nc);
  const path = new Int32Array(nc).fill(-1);
  const col4row = new Int32Array(nr).fill(-1), row4col = new Int32Array(nc).fill(-1);
  const SR = new Uint8Array(nr), SC = new Uint8Array(nc);
  const remaining = new Int32Array(nc);

  for (let curRow = 0; curRow < nr; curRow++) {
    let minVal = 0, i = curRow, numRemaining = nc, sink = -1;
    for (let it = 0; it < nc; it++) remaining[it] = nc - it - 1;
    SR.fill(0); SC.fill(0); shortest.fill(INF); path.fill(-1);

    while (sink === -1) {
      let index = -1, lowest = INF;
      SR[i] = 1;
      for (let it = 0; it < numRemaining; it++) {
        const j = remaining[it];
        const r = minVal + cost[i * nc + j] - u[i] - v[j];
        if (r < shortest[j]) { path[j] = i; shortest[j] = r; }
        // On a tie, prefer a column that is still free: it ends the search straight away.
        if (shortest[j] < lowest || (shortest[j] === lowest && row4col[j] === -1)) {
          lowest = shortest[j]; index = it;
        }
      }
      minVal = lowest;
      if (minVal === INF) return null;            // infeasible
      const j = remaining[index];
      if (row4col[j] === -1) sink = j; else i = row4col[j];
      SC[j] = 1;
      numRemaining--;
      remaining[index] = remaining[numRemaining];
    }

    u[curRow] += minVal;
    for (let k = 0; k < nr; k++) if (SR[k] && k !== curRow) u[k] += minVal - shortest[col4row[k]];
    for (let j = 0; j < nc; j++) if (SC[j]) v[j] -= minVal - shortest[j];

    while (true) {
      const k = path[sink];
      row4col[sink] = k;
      const tmp = col4row[k]; col4row[k] = sink; sink = tmp;
      if (k === curRow) break;
    }
  }
  return col4row;
};

S.assign = (cost, nr, nc) => {
  if (nr === 0 || nc === 0) return [[], []];
  if (nr > nc) {
    // scipy solves the transpose when there are more rows than columns, then sorts by row.
    const t = new Float64Array(nr * nc);
    for (let i = 0; i < nr; i++) for (let j = 0; j < nc; j++) t[j * nr + i] = cost[i * nc + j];
    const col4row = solveLsap(t, nc, nr);
    const pairs = [];
    for (let k = 0; k < nc; k++) pairs.push([col4row[k], k]);
    pairs.sort((a, b) => a[0] - b[0]);
    return [pairs.map(x => x[0]), pairs.map(x => x[1])];
  }
  const col4row = solveLsap(cost, nr, nc);
  const rows = []; for (let k = 0; k < nr; k++) rows.push(k);
  return [rows, Array.from(col4row)];
};

// ---------- segments ----------

// create_binary_segment as half open intervals: segment i covers [start, end) of the trajectory.
const segments = (cp, T) => {
  const c = [0, ...cp], out = [];
  for (let i = 0; i < c.length - 1; i++) {
    const a = Math.min(c[i] + 1, T), b = Math.min(c[i + 1] + 1, T);
    out.push([a, Math.max(a, b)]);
  }
  const a = Math.min(c[c.length - 1] + 1, T);
  out.push([a, Math.max(a, T)]);
  out[0][0] = 0;                       // the original sets index 0 of the first segment as well
  return out;
};

// Jaccard index between two segments, from their overlap (the arrays of the original are contiguous
// runs of ones, so this is interval arithmetic). Two empty segments score 0, as in the original.
const jaccard = (g, p) => {
  const tp = Math.max(0, Math.min(g[1], p[1]) - Math.max(g[0], p[0]));
  const fp = (p[1] - p[0]) - tp, fn = (g[1] - g[0]) - tp;
  return tp + fp + fn === 0 ? 0 : tp / (tp + fp + fn);
};

// segment_assignment with T = None: the last changepoint is the trajectory length.
const segmentAssignment = (GT, preds) => {
  const Tg = GT[GT.length - 1], g = GT.length > 1 ? GT.slice(0, -1) : GT;
  const Tp = preds[preds.length - 1], p = preds.length > 1 ? preds.slice(0, -1) : preds;
  const sg = segments(g, Tg), sp = segments(p, Tp);
  const n = sg.length, m = sp.length, cost = new Float64Array(n * m);
  for (let i = 0; i < n; i++) for (let j = 0; j < m; j++) cost[i * m + j] = 1 - jaccard(sg[i], sp[j]);
  return S.assign(cost, n, m);
};

// segment_property_errors: pair every ground truth segment with the prediction it was assigned to.
const pairSegments = (tCp, tA, tD, tS, pCp, pA, pD, pS, out) => {
  const noGT = tCp.length === 1, noPred = pCp.length === 1;
  const [rowInd, colInd] = segmentAssignment(tCp, pCp);
  if (noGT || noPred) {
    let gi = 0, pi = 0;
    if (noGT && !noPred) { gi = 0; pi = colInd[0]; }
    // The original indexes the ground truth by the position in the assignment, not by the row it
    // holds; with more ground truth segments than predicted ones the two differ, so copy it exactly.
    else if (noPred && !noGT) { const k = colInd.indexOf(0); gi = k; pi = colInd[k]; }
    out.a.push([tA[gi], pA[pi]]); out.d.push([tD[gi], pD[pi]]); out.s.push([tS[gi], pS[pi]]);
    return;
  }
  for (let seg = 0; seg < tA.length; seg++) {
    const k = rowInd.indexOf(seg);
    if (k === -1) continue;                       // this ground truth segment got no prediction
    const j = colInd[k];
    out.a.push([tA[seg], pA[j]]); out.d.push([tD[seg], pD[j]]); out.s.push([tS[seg], pS[j]]);
  }
};

// ---------- metrics ----------

// ensemble_changepoint_error: RMSE over the matched changepoints and the Jaccard index over all.
const changepointError = (trueCps, predCps, threshold = ERR_CP) => {
  let TP = 0, FP = 0, FN = 0, emptyGT = 0, numCpGT = 0, sq = 0, nSq = 0;
  for (let t = 0; t < trueCps.length; t++) {
    const gt = trueCps[t], pr = predCps[t];
    numCpGT += gt.length;
    const n = gt.length, m = pr.length;
    let rowInd = [], colInd = [];
    if (n && m) {
      const cost = new Float64Array(n * m);
      for (let i = 0; i < n; i++) for (let j = 0; j < m; j++) cost[i * m + j] = Math.abs(gt[i] - pr[j]);
      [rowInd, colInd] = S.assign(cost, n, m);
    }
    for (let k = 0; k < rowInd.length; k++) {
      const d = gt[rowInd[k]] - pr[colInd[k]];
      if (Math.abs(d) < threshold) { TP++; sq += d * d; nSq++; } else { FP++; FN++; }
    }
    if (m > n) FP += m - n; else if (m < n) FN += n - m;
    if (rowInd.length === 0 && m === n) emptyGT++;
  }
  if (TP + FP + FN === 0) return numCpGT === 0 ? [0, 1] : [threshold, 0];
  const rmse = nSq > 0 ? Math.sqrt(sq / nSq) : 0;
  return [rmse, (TP + emptyGT) / (TP + emptyGT + FP + FN)];
};

// ---------- the scorer ----------

// `truth` and `pred` are arrays of trajectories, aligned by position (the official scorer sorts
// each field of view by traj_idx and concatenates them, which is what the caller does).
// A trajectory is { cp: [...], a: [...], d: [...], s: [...] }, cp ending with the length.
S.score = (truth, pred) => {
  const trueCps = [], predCps = [], out = { a: [], d: [], s: [] };
  for (let i = 0; i < truth.length; i++) {
    const T = truth[i], P = pred[i];
    if (!P || P.cp.length === 0) {
      // No prediction for this trajectory: the scorer substitutes the maximum error.
      trueCps.push([10 + ERR_CP]); predCps.push([10]);
      out.a.push([ERR_ALPHA, 0]); out.d.push([1 + ERR_D, 1]); out.s.push([10, 0]);
      continue;
    }
    trueCps.push(T.cp.slice(0, -1)); predCps.push(P.cp.slice(0, -1));
    pairSegments(T.cp, T.a, T.d, T.s, P.cp, P.a, P.d, P.s, out);
  }
  // Predictions that are not numbers, or out of bounds, are replaced by the maximum error.
  let sumA = 0;
  for (const [g, p] of out.a) sumA += Math.abs(g - (Number.isFinite(p) && p <= 2 && p >= 0 ? p : g + ERR_ALPHA));
  const mae = Math.min(sumA / out.a.length, MAX_ALPHA_ERR);

  let sumD = 0;
  for (const [g0, p0] of out.d) {
    let g = Math.abs(g0), p = Number.isFinite(p0) ? Math.abs(p0) : Math.abs(g0) + ERR_D;
    if (g <= D_LO) g = D_LO;
    if (p <= D_LO) p = D_LO;
    sumD += (Math.log1p(g) - Math.log1p(p)) ** 2;
  }
  const msle = Math.min(sumD / out.d.length, MAX_MSLE);

  let ok = 0;
  for (const [g, p] of out.s) {
    const pp = (!Number.isFinite(p) || p > 4 || p < 0) ? ERR_S : p;
    if (Math.trunc(g) === Math.trunc(pp)) ok++;
  }
  const f1 = ok / out.s.length;

  const [rmse, jsc] = changepointError(trueCps, predCps);
  return { rmse_cp: rmse, jsc_cp: jsc, mae_alpha: mae, msle_k: msle, f1_state: f1 };
};

// ---------- reading the submission ----------

// One line is `traj_idx, D, alpha, state, cp, D, alpha, state, cp, ...`, as in the challenge
// format. Lines whose length is not 0 or 1 modulo 4 are dropped, as the official parser does.
S.parse = (text) => {
  const out = [];
  for (const line of text.split('\n')) {
    const t = line.trim();
    if (!t) continue;
    const v = t.split(',').map(Number);
    if (v.some(Number.isNaN) || (v.length % 4 !== 0 && v.length % 4 !== 1)) continue;
    const traj = { idx: v[0], d: [], a: [], s: [], cp: [] };
    for (let i = 1; i < v.length; i += 4) traj.d.push(v[i]);
    for (let i = 2; i < v.length; i += 4) traj.a.push(v[i]);
    for (let i = 3; i < v.length; i += 4) traj.s.push(v[i]);
    for (let i = 4; i < v.length; i += 4) traj.cp.push(v[i]);
    out.push(traj);
  }
  return out;
};

// ---------- reading the .zip ----------

// Minimal reader for the central directory of a zip, inflating with the browser's own
// DecompressionStream so that no library has to be shipped.
S.unzip = async (buffer) => {
  const dv = new DataView(buffer), bytes = new Uint8Array(buffer);
  let eocd = -1;
  for (let i = buffer.byteLength - 22; i >= 0 && i >= buffer.byteLength - 22 - 65536; i--) {
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('Not a .zip file (no end of central directory record).');
  const count = dv.getUint16(eocd + 10, true);
  let p = dv.getUint32(eocd + 16, true);
  const files = new Map();
  const dec = new TextDecoder();
  for (let k = 0; k < count; k++) {
    if (dv.getUint32(p, true) !== 0x02014b50) throw new Error('Damaged .zip central directory.');
    const method = dv.getUint16(p + 10, true);
    const compSize = dv.getUint32(p + 20, true);
    const nameLen = dv.getUint16(p + 28, true), extraLen = dv.getUint16(p + 30, true);
    const commentLen = dv.getUint16(p + 32, true);
    const local = dv.getUint32(p + 42, true);
    const name = dec.decode(bytes.subarray(p + 46, p + 46 + nameLen));
    p += 46 + nameLen + extraLen + commentLen;
    if (name.endsWith('/')) continue;
    const lNameLen = dv.getUint16(local + 26, true), lExtraLen = dv.getUint16(local + 28, true);
    const start = local + 30 + lNameLen + lExtraLen;
    const raw = bytes.subarray(start, start + compSize);
    files.set(name, { method, raw });
  }
  const out = new Map();
  for (const [name, f] of files) {
    if (f.method === 0) { out.set(name, dec.decode(f.raw)); continue; }
    if (f.method !== 8) throw new Error(`Unsupported compression in ${name} (only store and deflate).`);
    const stream = new Blob([f.raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    out.set(name, await new Response(stream).text());
  }
  return out;
};

// Finds `exp_<n>/fov_<m>.txt` anywhere in the zip, so it works whether the archive holds the
// track_2 folder itself or a folder containing it.
S.collect = (files) => {
  const exps = new Map();
  for (const [path, text] of files) {
    const m = path.match(/(?:^|\/)exp_(\d+)\/fov_(\d+)\.txt$/);
    if (!m || path.split('/').some(part => part.startsWith('.') || part === '__MACOSX')) continue;
    const exp = +m[1], fov = +m[2];
    if (!exps.has(exp)) exps.set(exp, new Map());
    exps.get(exp).set(fov, text);
  }
  return exps;
};

// ---------- scoring a whole submission ----------

// truth.json stores each trajectory flat as [cp, alpha, D, state, ...], one group per segment.
const decodeTruth = (flat) => {
  const t = { cp: [], a: [], d: [], s: [] };
  for (let i = 0; i < flat.length; i += 4) {
    t.cp.push(flat[i]); t.a.push(flat[i + 1]); t.d.push(flat[i + 2]); t.s.push(flat[i + 3]);
  }
  return t;
};

// Scores every experiment of an unzipped submission and averages them the way the challenge does:
// weighted by the number of trajectories, over all twelve experiments. `onStep` reports progress.
S.scoreSubmission = (truth, exps, onStep) => {
  const perExp = [], byCp = [], states = [];
  for (const e of truth.experiments) {
    const fovs = exps.get(e.raw);
    if (!fovs) throw new Error(`The archive has no exp_${e.raw} folder (all twelve are needed).`);
    const pred = [];
    for (let f = 0; f < NUM_FOVS; f++) {
      const text = fovs.get(f);
      if (text === undefined) throw new Error(`The archive has no exp_${e.raw}/fov_${f}.txt.`);
      const rows = S.parse(text);
      rows.sort((a, b) => a.idx - b.idx);
      for (const r of rows) pred.push(r);
    }
    const trajs = e.trajs.map(decodeTruth);
    const row = { exp: e.exp, raw: e.raw, n: trajs.length, ...S.score(trajs, pred) };
    // On the single state experiment every predicted changepoint is a false positive.
    if (e.single_state) {
      let cps = 0;
      for (const p of pred) cps += p.cp.length;
      const fp = cps - pred.length;
      row.fpr = fp / (fp + pred.length);
    }
    perExp.push(row);

    // Scores restricted to the trajectories with a given number of changepoints, for the
    // "performance vs. number of changepoints" panels. Every trajectory falls in exactly one bin,
    // so the whole sweep costs about as much as one more pass over the experiment.
    if (!CP_SKIP.includes(e.raw)) {
      for (let k = 0; k < CP_BINS; k++) {
        const ti = [], pi = [];
        for (let i = 0; i < trajs.length; i++) {
          if (trajs[i].cp.length !== k + 1) continue;      // n changepoints means n + 1 entries
          ti.push(trajs[i]); pi.push(pred[i]);
        }
        if (!ti.length) continue;
        const m = S.score(ti, pi);
        (byCp[k] = byCp[k] || []).push({ n: ti.length, ...m });
      }
    }

    // Share of time points given to each diffusive state, for the classification panels.
    if (e.exp) {
      const pops = new Array(4).fill(0);
      for (const p of pred) {
        let prev = 0;
        for (let j = 0; j < p.cp.length; j++) {
          const st = Math.trunc(p.s[j]);
          if (st >= 0 && st < 4) pops[st] += p.cp[j] - prev;
          prev = p.cp[j];
        }
      }
      const tot = pops.reduce((a, b) => a + b, 0);
      states.push({ exp: e.exp, values: tot ? pops.map(v => v / tot) : pops });
    }
    if (onStep) onStep(perExp.length, truth.experiments.length);
  }

  // Weighted average over the experiments, one per number of changepoints.
  const vsCp = [];
  for (let k = 0; k < CP_BINS; k++) {
    const rows = byCp[k];
    if (!rows || !rows.length) continue;
    const w = rows.reduce((a, r) => a + r.n, 0), entry = { k, n: w };
    for (const key of METRIC_KEYS) entry[key] = rows.reduce((a, r) => a + r[key] * r.n, 0) / w;
    vsCp.push(entry);
  }
  const total = perExp.reduce((a, r) => a + r.n, 0);
  const overall = {};
  for (const k of METRIC_KEYS) overall[k] = perExp.reduce((a, r) => a + r[k] * r.n, 0) / total;
  return { overall, perExp, vsCp, states, trajectories: total };
};

const NUM_FOVS = 30;
const CP_BINS = 9;
const CP_SKIP = [0, 1, 5, 10];          // failed experiments, and the one without changepoints
const METRIC_KEYS = ['rmse_cp', 'jsc_cp', 'mae_alpha', 'msle_k', 'f1_state'];
