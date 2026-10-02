# CI for new method contributions

**Status:** planned, not implemented.

Goal: let anyone propose a new model for AnDi-J by opening a pull request that contains a single
description file. A GitHub Action validates the file, downloads the model, benchmarks it and adds the
results to the public benchmark website (`web_app/`). The maintainers never host model weights: each
contributor keeps the ONNX file on their own (immutable) storage.

## 1. Description file

One JSON file per method, `models/<id>.json`, checked against a JSON Schema
(`models/schema.json`). JSON is read natively by the web app, needs no extra tooling in CI, and with a
schema, contributors get autocompletion and validation in their editor.

Example (analysis module):

```json
{
  "schema_version": 1,
  "id": "fci-tcn-v2",
  "name": "FCI-TCN v2",
  "version": "2.0.0",
  "authors": [
    { "name": "Jane Doe", "affiliation": "Univ. X", "orcid": "0000-0000-0000-0000" }
  ],
  "contact": "jane@x.org",
  "description": "One or two sentences shown on the wiki and on the benchmark page.",
  "reference": { "doi": "10.xxxx/...", "code": "https://github.com/..." },
  "license": "MIT",
  "module": "analysis",
  "tasks": {
    "alpha": { "url": "https://zenodo.org/records/.../alpha.onnx", "sha256": "..." },
    "model": { "url": "https://zenodo.org/records/.../model.onnx", "sha256": "..." }
  },
  "min_plugin_version": "0.1.1"
}
```

For `"module": "segmentation"`, `tasks` holds a single entry:

```json
"tasks": {
  "segmentation": {
    "url": "https://zenodo.org/records/.../seg.onnx",
    "sha256": "...",
    "outputs": ["alpha", "logD", "cp", "model_type"]
  }
}
```

### Fields

| Field | Required | Notes |
|---|---|---|
| `schema_version` | yes | Allows the format to change later without breaking older files. |
| `id` | yes | Stable slug: file name, key in `web_app/data`, plugin identifier. |
| `name` | yes | Display name. |
| `version` | yes | A new version is a new entry; previous results stay reproducible. |
| `authors` | yes | List of `{name, affiliation, orcid?}`. |
| `contact` | yes | Who to reach when the model link breaks. |
| `description` | yes | Short text for the wiki and the website. |
| `reference` | no | Publication DOI and/or code repository. |
| `license` | yes | Plugin users download and run the weights, so the terms must be explicit. |
| `module` | yes | `analysis` or `segmentation`. |
| `tasks` | yes | Analysis: `alpha` and/or `model` (one ONNX file each). Segmentation: `segmentation`. |
| `tasks.*.url` | yes | Direct download link to the ONNX file. Must be immutable (see below). |
| `tasks.*.sha256` | yes | Pins the exact file that was benchmarked. |
| `tasks.segmentation.outputs` | yes (seg.) | Which outputs the model provides, as read by `CustomSegPredictor`. |
| `min_plugin_version` | no | In case the model contract changes. |

### Hosting rules

Because the weights are not stored in this repository, a URL could later serve a different file.
The `sha256` field detects this, and URLs must point to immutable locations:

- Zenodo records (recommended: they also give a DOI),
- GitHub release assets,
- Hugging Face URLs that include a commit hash.

Mutable links (Dropbox, Google Drive, `main` branch URLs) are rejected.

The ONNX files must follow the model contract already used for custom models
(`CustomAnalysisPredictor`, `CustomSegPredictor`): input `(1, T, 2)` float32 with raw x, y
positions, so any normalization must be part of the ONNX graph.

## 2. GitHub Action

### Pipeline

1. **Validate** `models/<id>.json` against the schema (`check-jsonschema`).
2. **Download** each ONNX file, with a size cap and a timeout.
3. **Verify** the sha256.
4. **Check** the model: `onnx.checker`, then the input/output names and shapes against the contract.
5. **Run** the model on the 2D challenge test trajectories.
6. **Score** with the existing metrics (MAE / F1 for analysis; RMSE, Jaccard, MAE, MSLE, F1 for
   segmentation, using the challenge scorer).
7. **Publish** the results to `web_app/data` (`build_data.py --add`, and an equivalent for segmentation).

### Two stages (fork PRs)

PRs from forks run with a read-only token and no secrets, so the action cannot push to them. Do **not**
work around this with `pull_request_target` combined with checking out the PR's code (a known
security hole). Instead:

- **On `pull_request`:** steps 1 to 6. Upload the metrics as an artifact and post them as a PR comment,
  so reviewers see the numbers before approving.
- **On `push` to `main` (after merge):** re-run the evaluation and commit the new data to `web_app/data`.
  Use a `concurrency:` group so two merges do not race on the same files.

### Benchmark what users run

The plugin post-processes the network outputs in Java (e.g. turning the per-frame `cp` output into
segments and aggregating each segment). Re-implementing that in Python risks drift between the
benchmark and the plugin. Preferred approach: a small headless Java entry point (e.g.
`mvn exec:java -Dexec.mainClass=...Benchmark`) that reuses `CustomAnalysisPredictor` and
`CustomSegPredictor` to write predictions in the challenge format. Python then only scores them.

### Data

- Ground truth is already in the repository (`web_app/data/task*.json`,
  `web_app/data/segmentation/truth.json`).
- The input trajectories must be downloaded (e.g. from the challenge Zenodo records) and cached with
  `actions/cache`.
- The plugin only handles 2D trajectories, so contributed models are benchmarked in 2D only; the
  site's 1D/3D views will not include them.

### Safety and cost

- ONNX cannot execute arbitrary code (unlike pickle), but a model can still be very large or slow:
  cap the file size, set `timeout-minutes`, and run on CPU with onnxruntime.
- Small models should run in minutes on a free GitHub runner.

### Link rot

A weekly scheduled workflow re-checks every `url` and `sha256` in `models/`, and flags unavailable
models on the website and wiki (e.g. by opening an issue that mentions the `contact`).

## 3. Notes

- Results are computed by CI, not reported by the contributor, so the public leaderboard numbers can
  be trusted. The browser Submit pages remain the private check before opening a PR.
- The test ground truth is public, so CI cannot prevent overfitting to it.
- Possible extension: the plugin reads `models/*.json` as a registry to list approved models and
  download them on demand, verifying the hash.

## Website: "Bring your own method" block

This block was on the website Overview page (`viewHome` in `web_app/js/app.js`, between the two
benchmark cards and the References card). It was removed until the pipeline is live; bringing it
back is the first step of this plan. Its styles (`.home-steps`) are still in `web_app/css/style.css`.

```js
  page.append(h(`<section class="card card-body home-steps">
    <h2>Bring your own method</h2>
    Are you developing a new method for anomalous diffusion? You can contribute to the advancement of the field by sharing it with the community. The process is simple:
    <ol>
      <li><b>Benchmark it privately.</b> Run your method on the challenge test trajectories and upload the predictions
          on a Submit page of your choice (analysis or segmentation). Everything is private, scoring happens in your browser with the challenge's own metrics.</li>
      <li><b>Propose it.</b> Open a pull request on the <a href="${REPO}" ${EXT}>AnDi-J repository</a> with a short
          description file and the model weights. </li>
      <li><b>Share it.</b> Once approved, the method is documented on the <a href="${REPO}/wiki" ${EXT}>plugin's wiki</a>,
          added to these public benchmarks, and can be loaded in AnDi-J in place of the default model.</li>
    </ol>
  </section>`));
```

## To do

- [ ] Bring the "Bring your own method" block above back into the website Overview page.
- [ ] Write `models/schema.json` and one example file.
- [ ] Headless Java benchmark entry point (analysis and segmentation).
- [ ] Download and cache the challenge test trajectories in CI.
- [ ] `pull_request` workflow: validate, evaluate, comment.
- [ ] `push` workflow: evaluate and commit to `web_app/data`.
- [ ] Weekly link and hash check.
- [ ] Contributor guide on the wiki.
