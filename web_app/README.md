# AnDi-J benchmarks (static website)

Benchmarks for the models used by the AnDi-J Fiji / ImageJ plugin. New models are compared with the
AnDi-J default model, other available models and, for completeness, the methods of the
1st Anomalous Diffusion (AnDi) Challenge (Muñoz-Gil et al., Nat. Commun. 12, 6253, 2021).

Pages: Overview (placeholder), Trajectory Analysis (benchmark, anomalous exponent, diffusion model,
submit) and Segmentation Analysis (placeholder). The Trajectory Analysis benchmark covers the two
predictions of AnDi-J's Trajectory Analysis tab:

* **Anomalous exponent α** (Task 1 of the challenge), scored by mean absolute error (MAE).
* **Diffusion model** among ATTM, CTRW, FBM, LW and SBM (Task 2), scored by micro averaged F1.

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
* `assets/`: AnDi-J logo and favicon.
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
* Trajectory segmentation (AnDi-J's segmentation module, Task 3 of the challenge) is not benchmarked.
