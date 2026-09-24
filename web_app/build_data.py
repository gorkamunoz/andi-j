"""Build the benchmark data in data/*.json.

1) Reference methods (1st AnDi Challenge), from ../csv/task{1,2}_{1,2,3}d.csv:
       python build_data.py

2) Add (or replace) a model from prediction files in the AnDi Challenge format
   (task1.txt: "dim;alpha", task2.txt: "dim;p_attm;p_ctrw;p_fbm;p_lw;p_sbm"):
       python build_data.py --add "AnDi-J default" task1.txt task2.txt --group andij
       python build_data.py --add "My model" task1.txt task2.txt --group model
   Either file can be omitted with "-". The group sets the color on the site
   (andij = AnDi-J default model, model = other available models) and is stored in data/methods.json.
   Methods not listed in methods.json are shown as 1st AnDi Challenge entries.
"""
import argparse
import json
import numpy as np
import pandas as pd

P = 'acfls'


def r(a, k):
    a = np.round(np.asarray(a, dtype=float), k)
    return [int(x) if float(x).is_integer() else float(x) for x in a]


def build_reference():
    for dim in [1, 2, 3]:
        d = pd.read_csv(f'../csv/task1_{dim}d.csv')
        j = {'model': d.model.astype(int).tolist(), 'alpha': r(d.alpha, 2), 'length': d.length.astype(int).tolist(),
             'snr': r(d.loc_error, 2), 'teams': {t: r(d[t], 4) for t in d.columns[6:]}}
        json.dump(j, open(f'data/task1_{dim}d.json', 'w'), separators=(',', ':'))

        d = pd.read_csv(f'../csv/task2_{dim}d.csv')
        teams = [c for c in d.columns[6:] if '_' not in c or c.rsplit('_', 1)[1] not in list(P)]
        j = {'model': d.model.astype(int).tolist(), 'alpha': r(d.alpha, 2), 'length': d.length.astype(int).tolist(),
             'snr': r(d.loc_error, 2),
             'teams': {t: {'pred': d[t].astype(int).tolist(),
                           'prob': [np.round(d[f'{t}_{s}'].values * 1e5).astype(int).tolist() for s in P]} for t in teams}}
        json.dump(j, open(f'data/task2_{dim}d.json', 'w'), separators=(',', ':'))
        print(f'{dim}D reference data written')
    json.dump({}, open('data/methods.json', 'w'))


def set_group(name, group):
    fn = 'data/methods.json'
    try:
        meta = json.load(open(fn))
    except FileNotFoundError:
        meta = {}
    meta[name] = {'group': group}
    json.dump(meta, open(fn, 'w'), indent=2)


def add_method(name, file1, file2, group):
    set_group(name, group)
    for task, path in [(1, file1), (2, file2)]:
        if path in (None, '-'):
            continue
        rows = pd.read_csv(path, sep=';', header=None)
        for dim in [1, 2, 3]:
            fn = f'data/task{task}_{dim}d.json'
            j = json.load(open(fn))
            n = len(j['alpha'])
            mine = rows[rows[0] == dim]
            if len(mine) < n:
                print(f'  task{task} {dim}D: only {len(mine)} of {n} lines, skipped')
                continue
            mine = mine.head(n)
            if task == 1:
                j['teams'][name] = r(mine[1], 4)
            else:
                probs = mine[[1, 2, 3, 4, 5]].to_numpy()
                j['teams'][name] = {'pred': probs.argmax(axis=1).astype(int).tolist(),
                                    'prob': [np.round(probs[:, k] * 1e5).astype(int).tolist() for k in range(5)]}
            json.dump(j, open(fn, 'w'), separators=(',', ':'))
            print(f'  task{task} {dim}D: {name} added')


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--add', nargs=3, metavar=('NAME', 'TASK1_FILE', 'TASK2_FILE'))
    ap.add_argument('--group', choices=['andij', 'model'], default='model')
    a = ap.parse_args()
    if a.add:
        add_method(*a.add, a.group)
    else:
        build_reference()
