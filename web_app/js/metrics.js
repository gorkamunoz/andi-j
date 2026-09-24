// Scoring logic for the AnDi Challenge, ported from the original Python (pandas / scikit-learn).
// All functions work on plain arrays and a list of row indices (the rows kept by the filters).

const MODELS = ['attm', 'ctrw', 'fbm', 'lw', 'sbm'];

const M = {};

M.mean = (a) => { let s = 0; for (const v of a) s += v; return a.length ? s / a.length : NaN; };

M.std = (a) => {
  if (a.length < 2) return 0;
  const m = M.mean(a); let s = 0;
  for (const v of a) s += (v - m) ** 2;
  return Math.sqrt(s / (a.length - 1));
};

// ---------- Task 1: anomalous exponent ----------

// Mean absolute error with a 95% confidence interval (normal approximation).
M.mae = (pred, truth, idx) => {
  const err = idx.map(i => Math.abs(pred[i] - truth[i]));
  const n = err.length;
  return { value: M.mean(err), ci: n > 1 ? 1.96 * M.std(err) / Math.sqrt(n) : NaN, n };
};

// ---------- Task 2: model classification ----------

// Micro averaged F1 in a single label multiclass problem equals the accuracy.
M.accuracy = (pred, truth, idx) => {
  const n = idx.length; let ok = 0;
  for (const i of idx) if (pred[i] === truth[i]) ok++;
  const p = n ? ok / n : NaN;
  return { value: p, ci: n ? 1.96 * Math.sqrt(p * (1 - p) / n) : NaN, n };
};

// Confusion matrix normalized over the true class (rows = true, cols = predicted).
M.confusion = (pred, truth, idx, k = 5) => {
  const c = Array.from({ length: k }, () => new Array(k).fill(0));
  for (const i of idx) c[truth[i]][pred[i]]++;
  return c.map(row => { const s = row.reduce((a, b) => a + b, 0); return row.map(v => (s ? v / s : null)); });
};

// ROC curve (same points as sklearn.roc_curve with drop_intermediate=False).
M.rocCurve = (y, score) => {
  const order = Array.from(score.keys()).sort((a, b) => score[b] - score[a]);
  const P = y.reduce((a, b) => a + b, 0), N = y.length - P;
  const fpr = [0], tpr = [0];
  let tp = 0, fp = 0;
  for (let j = 0; j < order.length; j++) {
    const i = order[j];
    if (y[i]) tp++; else fp++;
    if (j === order.length - 1 || score[order[j + 1]] !== score[i]) {
      fpr.push(N ? fp / N : 0); tpr.push(P ? tp / P : 0);
    }
  }
  return { fpr, tpr };
};

M.auc = (x, y) => { let s = 0; for (let i = 1; i < x.length; i++) s += (x[i] - x[i - 1]) * (y[i] + y[i - 1]) / 2; return s; };

// Linear interpolation, like numpy.interp (xp increasing).
M.interp = (x, xp, fp) => {
  let j = 0;
  return x.map(v => {
    if (v < xp[0]) return fp[0];
    if (v >= xp[xp.length - 1]) return fp[fp.length - 1];
    while (xp[j + 1] < v) j++;
    // numpy picks the last of repeated xp values at a step, so move to it
    while (j + 1 < xp.length - 1 && xp[j + 1] === v) j++;
    const x0 = xp[j], x1 = xp[j + 1];
    return x1 === x0 ? fp[j + 1] : fp[j] + (fp[j + 1] - fp[j]) * (v - x0) / (x1 - x0);
  });
};

// One vs rest ROC for every class present, plus micro and macro averages.
// prob: array of 5 arrays (scores for attm, ctrw, fbm, lw, sbm), scaled 0..100000.
M.roc = (prob, truth, idx) => {
  const classes = [...new Set(idx.map(i => truth[i]))].sort((a, b) => a - b);
  const curves = {}, aucs = {};
  const yMicro = [], sMicro = [];
  for (const c of classes) {
    const y = idx.map(i => (truth[i] === c ? 1 : 0));
    const s = idx.map(i => prob[c][i]);
    curves[c] = M.rocCurve(y, s);
    aucs[c] = M.auc(curves[c].fpr, curves[c].tpr);
    for (let j = 0; j < y.length; j++) { yMicro.push(y[j]); sMicro.push(s[j]); }
  }
  curves.micro = M.rocCurve(yMicro, sMicro);
  aucs.micro = M.auc(curves.micro.fpr, curves.micro.tpr);
  const allFpr = [...new Set(classes.flatMap(c => curves[c].fpr))].sort((a, b) => a - b);
  const meanTpr = new Array(allFpr.length).fill(0);
  for (const c of classes) M.interp(allFpr, curves[c].fpr, curves[c].tpr).forEach((v, j) => (meanTpr[j] += v / classes.length));
  curves.macro = { fpr: allFpr, tpr: meanTpr };
  aucs.macro = M.auc(allFpr, meanTpr);
  return { classes, curves, aucs };
};

// ---------- Task 3: segmentation ----------

M.rmse = (pred, truth, idx) => {
  const n = idx.length; if (!n) return { value: NaN, ci: NaN, n: 0 };
  const sq = idx.map(i => (pred[i] - truth[i]) ** 2);
  const mse = M.mean(sq);
  // delta method for the CI of sqrt(mean)
  const ci = n > 1 && mse > 0 ? 1.96 * M.std(sq) / Math.sqrt(n) / (2 * Math.sqrt(mse)) : NaN;
  return { value: Math.sqrt(mse), ci, n };
};

// ---------- Helpers ----------

// Histogram density (like numpy.histogram(density=True)).
M.histogram = (values, bins = 50, lo, hi) => {
  lo = lo ?? Math.min(...values); hi = hi ?? Math.max(...values);
  const w = (hi - lo) / bins; const counts = new Array(bins).fill(0);
  for (const v of values) { if (v < lo || v > hi) continue; counts[Math.min(bins - 1, Math.floor((v - lo) / w))]++; }
  const total = values.length * w;
  return counts.map((c, k) => ({ x0: lo + k * w, x1: lo + (k + 1) * w, density: total ? c / total : 0 }));
};

// Gaussian kernel density estimate with Scott's bandwidth (same as scipy.stats.gaussian_kde).
M.kde = (values, xs) => {
  const n = values.length; const sd = M.std(values);
  const h = sd * Math.pow(n, -1 / 5) || 1e-3;
  const c = 1 / (n * h * Math.sqrt(2 * Math.PI));
  return xs.map(x => { let s = 0; for (const v of values) s += Math.exp(-0.5 * ((x - v) / h) ** 2); return { x, y: s * c }; });
};

if (typeof module !== 'undefined') module.exports = { M, MODELS };
