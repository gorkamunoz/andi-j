# autoresearch

This is an experiment to have the LLM do its own research.

## Setup

To set up a new experiment, work with the user to:

1. **Agree on a run tag**: propose a tag based on today's date (e.g. `mar5`). The branch `autoresearch_kistep/<tag>` must not already exist — this is a fresh run.
2. **Create the branch**: `git checkout -b autoresearch_kistep/<tag>` from current master.
3. **Read the in-scope files**: The repo is small. Read these files for full context:
   - `README.md` — repository context.
   - `train.py` — the file you modify. Model architecture, optimizer, training loop.
   - `prepare.py` — the file that is used to evaluate
5. **Initialize results.tsv**: Create `results.tsv` with just the header row. The baseline will be recorded after the first run. You will also see that there is an existing results_previous_mse that relate to a previous experiment. BE AWARE: these results are with MSE loss, while now you work on MAE loss. The model available in train.py is the best model of the previous experiment.
6. **Confirm and go**: Confirm setup looks good.

Once you get confirmation, kick off the experimentation.

## Experimentation

Each experiment runs on a single GPU. The training script runs for a **fixed time budget of 5 minutes** (wall clock training time, excluding startup/compilation). To launch a training, you need to run: `conda run -n kistep bash -c "python train.py > run.log 2>&1`

**What you CAN do:**
- Modify `train.py` — this is the only file you edit. Everything is fair game: model architecture, optimizer, hyperparameters, training loop, batch size, model size, etc.

**What you CANNOT do:**
- Modify the backbone of the model (see the train.py). This is a pretrain model that already gives you an advantage, new architecture has to be build on top.
- Install new packages or add dependencies. You can only use what's already in `pyproject.toml`.
- Modify the evaluation harness. The `evaluate` function in `prepare.py` gives you the MAE metric.

**The goal is simple: get the lowest MAE** Since the time budget is fixed, you don't need to worry about training time — it's always 5 minutes. Everything is fair game: change the architecture, the optimizer, the hyperparameters, the batch size, the model size. The only constraint is that the code runs without crashing and finishes within the time budget. 

About the architecture: always use the backbone as a pre-trained model, and either do as the current baseline where I add a simple linear head, or fine-tune parts of it or the whole.  The current head is has already been quite optimize but does not reach the best possible results, so there is marging to improve.

**Important:** The goal is to minimize the average MAE. Moreover, the output also gives the MAE as a function trajectory length T (from 10 to 50, 50 to 100,.. up to 200, which is the biggest trajectory) and also a detailed on the MAE for every diffusion model in the dataset, with a small noise validation dataset with T = 100. 

Here is an example of the output you will have:

attm (avg=0.242): {0.1: 0.57, 0.31: 0.22, 0.52: 0.12, 0.73: 0.13, 0.94: 0.17}

The avg=xxx is the average over all exponents for that diffusion model. Then, the list is the MAE for each specific anomalous exponent.


Aside of minimizinz the MAE, you also have two subgoals:
1) That the MAE is more or less uniform across the exponents within a model
1) that the model is also good for the shorter trajectories. You have full freedom to find ways to do this (i.e. fine-tune on shorter trajectories, create specialized datasets, etc)


VRAM is a soft constraint. Some increase is acceptable for meaningful MAE gains, but it should not blow up dramatically.


**Simplicity criterion**: All else being equal, simpler is better. A small improvement that adds ugly complexity is not worth it. Conversely, removing something and getting equal or better results is a great outcome — that's a simplification win. When evaluating whether to keep a change, weigh the complexity cost against the improvement magnitude. A 0.001 MAE improvement that adds 20 lines of hacky code? Probably not worth it. A 0.001 MAE improvement from deleting code? Definitely keep. An improvement of ~0 but much simpler code? Keep.

**The first run**: Your very first run should always be to establish the baseline, so you will run the training script as is.

## Output format

Once the script finishes it prints a summary like this:

```
---
mae: 0.269056
mae_10_50:  0.286344
mae_50_100:  0.267158
mae_100_150:  0.263170
mae_150_200:  0.261998
attm (avg=0.243): {0.1: 0.57, 0.31: 0.2, 0.52: 0.15, 0.73: 0.15, 0.94: 0.15}
ctrw (avg=0.13): {0.1: 0.11, 0.31: 0.11, 0.52: 0.14, 0.73: 0.15, 0.94: 0.14}
fbm (avg=0.219): {0.1: 0.12, 0.31: 0.77, 0.52: 0.45, 0.73: 0.23, 0.94: 0.25, 1.15: 0.14, 1.36: 0.07, 1.57: 0.06, 1.78: 0.06, 1.99: 0.04}
lw (avg=0.137): {1.15: 0.14, 1.36: 0.13, 1.57: 0.11, 1.78: 0.12, 1.99: 0.19}
sbm (avg=0.174): {0.1: 0.41, 0.31: 0.25, 0.52: 0.15, 0.73: 0.09, 0.94: 0.25, 1.15: 0.19, 1.36: 0.13, 1.57: 0.14, 1.78: 0.07, 1.99: 0.06}
total_seconds:    33.1
Non-backbone parameters: 65,793
```

Note that the script is configured to always stop after 5 minutes, so depending on the computing platform of this computer the numbers might look different. 

## Logging results

When an experiment is done, log it to `results.tsv` (tab-separated, NOT comma-separated — commas break in descriptions).

The TSV has a header row and 5 columns:

```
commit	mae	mae_10_50 mae_150_200 attm ctrw fbm lw sbm status	description
```

1. git commit hash (short, 7 chars)
2. averaged MAE for whole validation batch — use 0.0 for crashes
3 and 4. MAE for short (10 to 50) and long (150 to 200) — use 0.0 for crashes
5 to 9. MAE per diffusion model
4. status: `keep`, `discard`, or `crash`
5. short text description of what this experiment tried

Example:

```
commit	mae mae_150_200 attm ctrw fbm lw sbm status	description
a1b2c3d	0.2231 0.224231 0.234234 0.2342 0.5345 0.245231 0.2765231	keep	baseline
b2c3d4e	0.2131 0.245231 0.5234 0.221 0.1322 0.224231 0.234234 keep	increase LR to 0.04
c3d4e5f	1.005000 1.005000 1.005000 1.005000 1.005000 1.005000 1.005000	discard	switch to GeLU activation
d4e5f6g	0. 0. 0. 0. 0.	0. 0. crash	double model width (OOM)
```

## The experiment loop

The experiment runs on a dedicated branch (e.g. `autoresearch/jun8` or `autoresearch/jun8-gpu0`).

LOOP FOREVER:

1. Look at the git state: the current branch/commit we're on
2. Tune `train.py` with an experimental idea by directly hacking the code.
3. git commit
4. Run the experiment:  `conda run -n kistep bash -c "python train.py > run.log 2>&1` (redirect everything — do NOT use tee or let output flood your context)
5. Read out the results: `grep "^mae:\|^mae_10_50:\|^mae_150_200:\|^attm:\|^ctrw:\|^fbm:\|^lw:\|^sbm" run.log`
6. If the grep output is empty, the run crashed. Run `tail -n 50 run.log` to read the Python stack trace and attempt a fix. If you can't get things to work after more than a few attempts, give up.
7. Record the results in the tsv (NOTE: do not commit the results.tsv file, leave it untracked by git)
8. If mae improved (lower), you "advance" the branch, keeping the git commit
9. If the mae stayed the same and you improved the mae_10_50, you "advance" the branch, keeping the git commit
10. If the mae got a bit worse at the expense of making the mae of one model better, keeping the git commit
9. If mae is equal or worse, you git reset back to where you started

The idea is that you are a completely autonomous researcher trying things out. If they work, keep. If they don't, discard. And you're advancing the branch so that you can iterate. If you feel like you're getting stuck in some way, you can rewind but you should probably do this very very sparingly (if ever).

**Timeout**: Each experiment should take ~5 minutes total (+ a few seconds for startup and eval overhead). If a run exceeds 10 minutes, kill it and treat it as a failure (discard and revert).

**Crashes**: If a run crashes (OOM, or a bug, or etc.), use your judgment: If it's something dumb and easy to fix (e.g. a typo, a missing import), fix it and re-run. If the idea itself is fundamentally broken, just skip it, log "crash" as the status in the tsv, and move on.

**NEVER STOP**: Once the experiment loop has begun (after the initial setup), do NOT pause to ask the human if you should continue. Do NOT ask "should I keep going?" or "is this a good stopping point?". The human might be asleep, or gone from a computer and expects you to continue working *indefinitely* until you are manually stopped. You are autonomous. If you run out of ideas, think harder — read papers referenced in the code, re-read the in-scope files for new angles, try combining previous near-misses, try more radical architectural changes. The loop runs until the human interrupts you, period.

As an example use case, a user might leave you running while they sleep. If each experiment takes you ~5 minutes then you can run approx 12/hour, for a total of about 100 over the duration of the average human sleep. The user then wakes up to experimental results, all completed by you while they slept!