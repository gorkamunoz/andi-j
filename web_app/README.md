# AnDi-J benchmarks (static website)

Benchmarks for the models used by the AnDi-J Fiji / ImageJ plugin. New models are compared with the
AnDi-J default model, other available models and, for completeness, the methods of the
1st Anomalous Diffusion (AnDi) Challenge (Muñoz-Gil et al., Nat. Commun. 12, 6253, 2021).

Pages: Overview (placeholder), Trajectory Analysis (benchmark, anomalous exponent, diffusion model,
submit) and Trajectory segmentation (analysis, submission). The Trajectory Analysis benchmark covers the two
predictions of AnDi-J's Trajectory Analysis tab:

* **Anomalous exponent α** (Task 1 of the challenge), scored by mean absolute error (MAE).
* **Diffusion model** among ATTM, CTRW, FBM, LW and SBM (Task 2), scored by micro averaged F1.

The Trajectory segmentation page benchmarks AnDi-J's Trajectory segmentation tab against the single
trajectory task of the [2nd AnDi Challenge](https://doi.org/10.1038/s41467-025-61949-x): the
changepoints of a trajectory (RMSE and Jaccard similarity) and, for every segment, its anomalous
exponent (MAE), diffusion coefficient (MSLE) and diffusion type (F1). Unlike the Trajectory
Analysis pages it is a read only summary of the challenge results, plus a Submission page that
scores an uploaded archive in the browser.

The left hand panel of its "Overall performance" card is a metric explorer: one row of buttons per
axis picks which of MAE, MSLE and F1 that axis plots (choosing the metric the other axis already
shows swaps the two). Both axes always point so that better is right and up. It reads out on click
rather than on hover, so only the AnDi-J default model is labelled; clicking any point spells out
its scores under the plot. Every section works the same way: nothing reads out on hover, a click
selects a method and the line under the section gives its numbers. Each panel of "Performance per
experiment" also has a button that blows it up to a 2x2 block with the other four beside it.

Plain HTML, CSS and JavaScript: no build step, no server. Uploaded prediction files are scored in
the visitor's browser and never leave their computer.

## Structure

* `index.html`: page shell and navigation.
* `css/style.css`: design tokens (light and dark themes, AnDi-J logo colors) and components.
* `js/metrics.js`: scoring (MAE, F1, confusion matrix, ROC/AUC, KDE), checked against
  pandas / scikit-learn (differences below 1e-4).
* `js/charts.js`: charts, built with Observable Plot.
* `js/app.js`: views and routing, filters, tables, upload, method groups.
* `data/task{1,2}_{1,2,3}d.json`: ground truth and every method's predictions per task and dimension.
* `data/methods.json`: group (color) of each added model.
* `data/segmentation/*.json`: the Trajectory segmentation results (leaderboard, per experiment,
  per number of changepoints, single state experiment, diffusion type populations) and `truth.json`,
  the challenge ground truth, loaded only by the Submission page.
* `js/seg_score.js`: the challenge scorer ported to JavaScript, plus a small zip reader.
* `assets/`: AnDi-J logo, favicon and `df_single_state.csv` (input of `build_segmentation_data.py`).
* `vendor/`: local copies of d3 7.9 and Observable Plot 0.6.17 (no CDN needed).

## Adding models

Run the model on the AnDi Challenge test trajectories, export its predictions in the challenge format
(`task1.txt`: `dim;alpha`, `task2.txt`: `dim;p_attm;p_ctrw;p_fbm;p_lw;p_sbm`), then:

    cd web_app
    python build_data.py --add "AnDi-J default" path/to/task1.txt path/to/task2.txt --group andij
    python build_data.py --add "Some model" path/to/task1.txt path/to/task2.txt --group model

`--group` sets the color on the site and is stored in `data/methods.json`:
`andij` (blue, AnDi-J default model) or `model` (green, other available models). Methods not listed
there are shown in grey as 1st AnDi Challenge entries; uploads on the Submit page are orange.
Running `python build_data.py` with no arguments rebuilds the challenge data from a `csv/` folder
next to `web_app` (the original challenge CSVs, not included in this repo; this removes added models).
Adding models does not need them.

## Submitting a segmentation model

The Submission page takes one `.zip` laid out like the challenge submission folder
(`track_2/exp_<0-11>/fov_<0-29>.txt`, or the `exp_` folders at the root) and scores it in the
visitor's browser; the archive is never uploaded. `js/seg_score.js` is a port of
`andi_datasets.utils_challenge.error_SingleTraj_dataset`, including scipy's
`linear_sum_assignment` (its exact tie breaking matters: the cost matrices are full of ties and the
metrics depend on which of the equally cheap assignments is picked). Scoring the AnDi-J default
model's own submission through it reproduces that model's official per experiment and overall
scores to floating point round off (worst difference 2e-15), in about 40 ms.

All twelve experiments are needed, including the three that failed (0, 1 and 5) and are never
plotted, because the overall score is the average over every experiment weighted by its number of
trajectories, which is how the published leaderboard was computed. A scored submission is ranked
together with the participants and shown in orange on the Analysis page until the page is reloaded.

## Rebuilding the segmentation data

The Trajectory segmentation page reads only `data/segmentation/*.json`, so the site needs nothing else
at run time. Those files are built once from the challenge result folder (submissions, per
experiment scores and references of the challenge phase) with `andi_datasets` installed:

    cd web_app
    conda run -n andi python build_segmentation_data.py --source /path/to/challenge_phase/

`--only truth` rebuilds just the ground truth the Submission page needs.
It also reads `assets/df_single_state.csv` (per-team scores on the single state experiment) and takes about five minutes, most of it rescoring the submissions by number of
changepoints. `--only leaderboard per_experiment single_state` rebuilds just the light files.
The AnDi-J default model is the `model_v2.2` submission and is ranked together with the
participants; the five best challenge teams are the ones shown in the panels that only keep the top
performers.

## Run locally

    cd web_app
    python -m http.server 8000     # then open http://localhost:8000

(Opening index.html directly from disk does not work, browsers block loading the data files.)

## Publish on GitHub Pages

The workflow `.github/workflows/pages.yml` (at the root of the andi-j repo) publishes this folder.

1. One time: on GitHub, Settings > Pages > Build and deployment > Source: "GitHub Actions".
2. Merge or push changes to `main`. Any push to `main` that touches `web_app/` redeploys the site
   (it can also be run by hand from the Actions tab).
3. The site is served at https://gorkamunoz.github.io/andi-j/

## Notes on the scores

* Scores are computed from the per-trajectory predictions for whatever filters are active.
* ± values are 95% confidence intervals (normal approximation).
* F1 is the micro averaged F1 (equal to accuracy); AUC is micro averaged.
* On the Trajectory segmentation page the scores come from the 2nd AnDi Challenge leaderboards and
  submissions, not from the visitor's browser, so the filters of the Trajectory Analysis pages do
  not apply there. A changepoint RMSE of exactly 0 means a method predicted no changepoint at all
  and is left out of the panels, as in the challenge paper figures.
