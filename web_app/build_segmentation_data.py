"""Builds data/segmentation/*.json from the 2nd AnDi Challenge results.

The Segmentation Analysis page only reads those JSON files, so the site stays self-contained and
this script never has to run again unless the underlying results change. It needs the challenge
result folder (submissions, per-experiment scores, references) and `andi_datasets`:

    conda run -n andi python build_segmentation_data.py [--source /path/to/challenge_phase/]

Everything is Task 1 (single trajectory) of track 2 (trajectories), which is what AnDi-J's
Segmentation Analysis tab does. The AnDi-J default model is the `model_v2.2` submission and is
treated as one more entry, ranked together with the challenge teams.
"""

import argparse
import json
import pathlib
import string

import numpy as np
import pandas as pd
from scipy.stats import rankdata

pd.options.mode.chained_assignment = None

SOURCE = pathlib.Path('/media/gorka/DATA/andi2_data/datasets_phases_challenge/challenge_phase')
OUT = pathlib.Path(__file__).parent / 'data' / 'segmentation'

TRACK = 2          # trajectories
NUM_FOVS = 30
ANDIJ = 'model_v2.2'          # submission folder / score file of the AnDi-J default model
ANDIJ_NAME = 'AnDi-J default'

# Experiments kept in the analysis (0, 1 and 5 failed) in the order used in the paper figures:
# EXP_ORDER[display index] = raw experiment number.
EXP_ORDER = [6, 7, 2, 3, 4, 8, 9, 10, 11]
SINGLE_STATE_EXP = 10                       # no changepoints, so RMSE (CP) is undefined there
EXP_GROUPS = [[2, 3, 4], [5, 6]]            # display indices sharing a physical model

# Metric keys as they appear in scores_*.txt / df_track_*.csv, and how the site names them.
METRICS = [
    {'key': 'rmse_cp', 'txt': 'cp', 'csv': 'RMSE CP', 'label': 'RMSE (CP)', 'lower': True},
    {'key': 'jsc_cp', 'txt': 'JI', 'csv': 'JSC CP', 'label': 'JSC (CP)', 'lower': False},
    {'key': 'mae_alpha', 'txt': 'alpha', 'csv': 'alpha', 'label': 'MAE (α)', 'lower': True},
    {'key': 'msle_k', 'txt': 'D', 'csv': 'K', 'label': 'MSLE (K)', 'lower': True},
    {'key': 'f1_state', 'txt': 'state', 'csv': 'state', 'label': 'F1 (diff. type)', 'lower': False},
]
STATE_LABELS = ['Immobile', 'Confined', 'Free', 'Directed']


def read_mapping(source):
    """letter -> team name, from team_mapping.txt."""
    out = {}
    for line in (source / 'team_mapping.txt').read_text().splitlines():
        if ': ' in line:
            letter, team = line.strip().split(': ')
            out[letter.strip()] = team.strip()
    return out


def read_scores(source, entry):
    """Overall track 2 / task 1 scores of one entry, from its scores_*.txt (full precision)."""
    out = {}
    for line in (source / 'scores_post15' / f'scores_{entry}.txt').read_text().splitlines():
        key, value = line.strip().split(': ')
        if key.startswith(f'tr{TRACK}.ta1.'):
            out[key[len(f'tr{TRACK}.ta1.'):]] = float(value)
    return out


def ranks(values, lower_better):
    """Competition ranking (ties share the best rank), as scipy's rankdata(method='min')."""
    v = np.asarray(values, dtype=float)
    return rankdata(v if lower_better else -v, method='min').astype(int).tolist()


# ---------- 1. Leaderboard ----------

def build_leaderboard(source, mapping):
    entries = [(letter, mapping[letter]) for letter in string.ascii_uppercase if letter in mapping]
    entries.append((ANDIJ, ANDIJ_NAME))

    rows = []
    for entry, team in entries:
        scores = read_scores(source, entry)
        row = {'team': team, 'letter': entry, 'group': 'andij' if entry == ANDIJ else 'challenge'}
        row.update({m['key']: scores[m['txt']] for m in METRICS})
        rows.append(row)

    # Mean reciprocal rank over the five metrics, then the global rank from it (as in the challenge).
    mrr = np.zeros(len(rows))
    for m in METRICS:
        rk = ranks([r[m['key']] for r in rows], m['lower'])
        for row, r in zip(rows, rk):
            row[m['key'] + '_rank'] = r
        mrr += 1 / np.asarray(rk)
    mrr /= len(METRICS)
    for row, value, rank in zip(rows, mrr, ranks(-mrr, lower_better=True)):
        row['mrr'], row['rank'] = float(value), rank

    rows.sort(key=lambda r: (r['rank'], r['team']))
    return {'metrics': [{k: m[k] for k in ('key', 'label', 'lower')} for m in METRICS], 'rows': rows}


# ---------- 2. Metrics per experiment ----------

def build_per_experiment(source, leaderboard):
    """Every entry's five metrics on each of the nine experiments, from the per-experiment scores."""
    per_entry = {}
    for row in leaderboard['rows']:
        path = source / 'scores_post15' / f'df_track_{TRACK}_task_1_{row["letter"]}.csv'
        df = pd.read_csv(path).set_index('Exp')
        per_entry[row['team']] = df

    exps = []
    for display, exp in enumerate(EXP_ORDER):
        entry = {'exp': display + 1, 'raw': exp, 'single_state': exp == SINGLE_STATE_EXP,
                 'num_trajs': int(per_entry[leaderboard['rows'][0]['team']].loc[exp, 'num_trajs']),
                 'values': {}}
        for team, df in per_entry.items():
            entry['values'][team] = {m['key']: float(df.loc[exp, m['csv']]) for m in METRICS}
        exps.append(entry)

    return {'experiments': exps, 'groups': EXP_GROUPS,
            'metrics': [{k: m[k] for k in ('key', 'label', 'lower')} for m in METRICS]}


# ---------- 3. Metrics vs number of changepoints ----------

def load_df(path):
    from andi_datasets.utils_challenge import load_file_to_df
    return load_file_to_df(str(path))


def build_metrics_vs_cp(source, teams):
    """Scores restricted to the trajectories that have exactly n changepoints, for n = 0 … 8.

    Same filtering as the notebook: single-state (10) and the failed experiments (0, 1, 5) are
    dropped, and each experiment contributes with the weight of its number of trajectories.
    """
    from andi_datasets.utils_challenge import error_SingleTraj_dataset

    exps = [e for e in range(12) if e not in (0, 1, 5, SINGLE_STATE_EXP)]
    num_cps = list(range(9))                       # changepoints per trajectory
    per_exp = np.zeros((len(num_cps), len(exps), len(METRICS), len(teams)))
    weights = np.zeros((len(num_cps), len(exps)))

    for idxe, exp in enumerate(exps):
        trues = [load_df(source / 'reference_challenge_phase' / f'track_{TRACK}' / f'exp_{exp}'
                         / f'traj_labs_fov_{fov}.txt') for fov in range(NUM_FOVS)]
        preds = {entry: [load_df(source / 'submissions_post15' / entry / f'track_{TRACK}'
                                 / f'exp_{exp}' / f'fov_{fov}.txt') for fov in range(NUM_FOVS)]
                 for _, entry in teams}

        for idxc, num_cp in enumerate(num_cps):
            # `changepoints` holds one entry per segment end, so n changepoints means n + 1 entries.
            keep = [t.loc[t['changepoints'].apply(len) == num_cp + 1, 'traj_idx'].values.astype(int)
                    for t in trues]
            true_exp = concat_fovs(trues, keep)
            weights[idxc, idxe] = true_exp.shape[0]
            if true_exp.shape[0] == 0:
                continue
            for idxt, (_, entry) in enumerate(teams):
                pred_exp = concat_fovs(preds[entry], keep)
                per_exp[idxc, idxe, :, idxt] = error_SingleTraj_dataset(
                    pred_exp, true_exp, prints=False, disable_tqdm=True)
        print(f'  exp {exp} done', flush=True)

    # Weighted average over experiments, one average per number of changepoints.
    values = np.zeros((len(num_cps), len(METRICS), len(teams)))
    for idxc in range(len(num_cps)):
        if weights[idxc].sum() > 0:
            values[idxc] = np.average(per_exp[idxc], weights=weights[idxc], axis=0)

    return {'changepoints': num_cps,
            'teams': [team for team, _ in teams],
            'metrics': [{k: m[k] for k in ('key', 'label', 'lower')} for m in METRICS],
            'values': values.tolist(),
            'num_trajs': weights.sum(axis=1).astype(int).tolist()}


def concat_fovs(dfs, keep):
    """Keeps the selected trajectories of each field of view and renumbers them across the whole
    experiment, so that predictions and ground truth stay aligned (as the notebook does)."""
    out, offset = [], 0
    for df, idx in zip(dfs, keep):
        sub = df[df['traj_idx'].isin(idx)].copy()
        sub['traj_idx'] = np.arange(sub.shape[0]) + offset
        offset += sub.shape[0]
        out.append(sub)
    return pd.concat(out, ignore_index=True)


# ---------- 4. Single state experiment ----------

def build_single_state(source, mapping, assets):
    """MAE (α) and MSLE (K) against the false positive rate on the single-state experiment.

    On that experiment no trajectory has a changepoint, so every predicted changepoint is a false
    positive: FPR = FP / (FP + number of trajectories).
    """
    df = pd.read_csv(assets / 'df_single_state.csv', index_col=0)
    rows = []
    for _, r in df.iterrows():
        team = r['Team']
        if team == 'MSD':
            rows.append({'team': 'MSD fit', 'group': 'reference', 'fpr': None,
                         'mae_alpha': float(r['alpha']), 'msle_k': float(r['K'])})
        else:
            rows.append({'team': mapping[team], 'group': 'challenge', 'fpr': float(r['FPR']),
                         'mae_alpha': float(r['alpha']), 'msle_k': float(r['K'])})

    scores = pd.read_csv(source / 'scores_post15' / f'df_track_{TRACK}_task_1_{ANDIJ}.csv')
    scores = scores[scores.Exp == SINGLE_STATE_EXP].iloc[0]
    rows.append({'team': ANDIJ_NAME, 'group': 'andij', 'fpr': andij_fpr(source),
                 'mae_alpha': float(scores['alpha']), 'msle_k': float(scores['K'])})
    return {'rows': rows}


def andij_fpr(source):
    num_trajs, num_cps = 0, 0
    for fov in range(NUM_FOVS):
        preds = load_df(source / 'submissions_post15' / ANDIJ / f'track_{TRACK}'
                        / f'exp_{SINGLE_STATE_EXP}' / f'fov_{fov}.txt')
        num_trajs += preds.shape[0]
        num_cps += len(np.concatenate(preds.changepoints.values))
    fp = num_cps - num_trajs      # one changepoint entry per trajectory is the trajectory end
    return float(fp / (fp + num_trajs))


# ---------- 5. Diffusion type classification ----------

def build_classification(source, teams):
    """Fraction of time points assigned to each diffusion type, per experiment and entry."""
    exps = []
    for display, exp in enumerate(EXP_ORDER):
        true_pops = np.zeros(len(STATE_LABELS))
        pred_pops = {}
        for idxt, (team, entry) in enumerate(teams):
            pops = np.zeros(len(STATE_LABELS))
            for fov in range(NUM_FOVS):
                preds = load_df(source / 'submissions_post15' / entry / f'track_{TRACK}'
                                / f'exp_{exp}' / f'fov_{fov}.txt')
                accumulate(preds, pops)
                if idxt == 0:
                    trues = load_df(source / 'reference_challenge_phase' / f'track_{TRACK}'
                                    / f'exp_{exp}' / f'traj_labs_fov_{fov}.txt')
                    accumulate(trues, true_pops)
            pred_pops[team] = (pops / pops.sum()).tolist()
        exps.append({'exp': display + 1, 'raw': exp,
                     'truth': (true_pops / true_pops.sum()).tolist(), 'values': pred_pops})
        print(f'  exp {exp} done', flush=True)
    return {'states': STATE_LABELS, 'teams': [team for team, _ in teams], 'experiments': exps}


def accumulate(df, pops):
    """Adds the length of every segment of `df` to the population of its diffusion type."""
    for idx in range(df.shape[0]):
        cps = np.append(0, df.changepoints.loc[idx])
        for length, state in zip(cps[1:] - cps[:-1], df.states.loc[idx]):
            pops[int(state)] += length


# ---------- 6. Ground truth, so the browser can score an uploaded submission ----------

def build_truth(source):
    """Every experiment's labels, in the order the official scorer concatenates them.

    Each trajectory becomes a flat array [cp, alpha, D, state, cp, alpha, D, state, ...], one group
    per segment, with the last changepoint being the trajectory length (as in the challenge format).
    """
    # All twelve experiments: the overall score is the average over every one of them, weighted by
    # its number of trajectories, so an upload can only be compared with the published leaderboard
    # if the three failed experiments (0, 1 and 5) are scored as well. Only nine are ever plotted.
    exps = []
    for exp in range(12):
        trajs = []
        for fov in range(NUM_FOVS):
            df = load_df(source / 'reference_challenge_phase' / f'track_{TRACK}' / f'exp_{exp}'
                         / f'traj_labs_fov_{fov}.txt')
            df = df.sort_values('traj_idx')
            for _, r in df.iterrows():
                flat = []
                for cp, a, d, st in zip(r.changepoints, r.alphas, r.Ds, r.states):
                    flat += [int(cp), float(a), float(d), int(st)]
                trajs.append(flat)
        display = EXP_ORDER.index(exp) + 1 if exp in EXP_ORDER else None
        exps.append({'exp': display, 'raw': exp, 'single_state': exp == SINGLE_STATE_EXP,
                     'trajs': trajs})
        print(f'  exp {exp}: {len(trajs)} trajectories (panel {display})', flush=True)
    return {'experiments': exps, 'states': STATE_LABELS}


# ---------- main ----------

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source', type=pathlib.Path, default=SOURCE, help='challenge_phase folder')
    ap.add_argument('--only', nargs='*', help='only rebuild these files (without .json)')
    args = ap.parse_args()
    source, assets = args.source, pathlib.Path(__file__).parent / 'assets'
    OUT.mkdir(parents=True, exist_ok=True)

    def wanted(name):
        return not args.only or name in args.only

    mapping = read_mapping(source)
    leaderboard = build_leaderboard(source, mapping)
    if wanted('leaderboard'):
        write('leaderboard', leaderboard)
    if wanted('per_experiment'):
        write('per_experiment', build_per_experiment(source, leaderboard))

    # The heavy plots keep the five best challenge teams (by the official challenge ranking) and
    # add the AnDi-J default model to them.
    official = pd.read_csv(source / 'final_leaderboards' / f'Final_leaderboard_trajs_single.csv')
    inverse = {team: letter for letter, team in mapping.items()}
    top = [(team, inverse[team]) for team in official.sort_values('Global rank').Team.values[:5]]
    teams = top + [(ANDIJ_NAME, ANDIJ)]

    if wanted('single_state'):
        write('single_state', build_single_state(source, mapping, assets))
    if wanted('classification'):
        print('classification…')
        write('classification', build_classification(source, teams))
    if wanted('metrics_vs_cp'):
        print('metrics vs changepoints…')
        write('metrics_vs_cp', build_metrics_vs_cp(source, teams))
    if wanted('truth'):
        print('ground truth…')
        write('truth', build_truth(source))


def write(name, payload):
    path = OUT / f'{name}.json'
    path.write_text(json.dumps(payload, separators=(',', ':')))
    print(f'wrote {path} ({path.stat().st_size / 1024:.0f} kB)')


if __name__ == '__main__':
    main()
