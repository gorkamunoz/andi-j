# AnDi Fiji Plugin

A [Fiji/ImageJ](https://fiji.sc) plugin for analysing single-particle tracking (SPT) trajectories exported from [TrackMate](https://imagej.net/plugins/trackmate/).  
It computes the **time-averaged Mean Squared Displacement (tMSD)**, **diffusion coefficient *D***, and **anomalous exponent *α*** for each trajectory, following the algorithms of the [AnDi-datasets](https://github.com/AnDiChallenge/ANDI_datasets) library.

---

## Features

| Tab | What it does |
|-----|-------------|
| **TMSD Visualization** | Plots the tMSD curve for each trajectory and/or its average. Log or linear axes, configurable lag range. |
| **Diffusion coefficient** | Histogram of *D* values (linear fit of tMSD vs *t*). Independent 2D / X / Y components. Average table, CSV export. |
| **Anomalous exponent** | Histogram of *α* values (log-log fit of tMSD vs *t*). Same controls as the *D* tab. |
| **Full analysis** | 2-D scatter plot *α* vs *D*, one point per trajectory. Log *D* axis by default. |

All analysis tabs share the same interaction model:

- **Per-experiment colour coding** – each loaded CSV gets a distinct colour.
- **2D / X / Y component selection** – analyse the full displacement or its X/Y projections independently.
- **Zoom & pan** – drag a rectangle to zoom in, drag in pan mode to move, double-click to reset.
- **Save** buttons export CSV files with per-trajectory results.

---

## Input format

The plugin reads the **Spots CSV export from TrackMate** (tested with TrackMate ≥ 7).  
Required columns (case-insensitive): `TRACK_ID`, `POSITION_X`, `POSITION_Y`, `FRAME`.

> Multiple CSV files can be loaded at once; each file becomes one labelled experiment.

---

## Installation

### For users

1. During beta testing: go to folder [**Target**](https://github.com/gorkamunoz/andi_fiji/tree/main/target)
1. (When released, no beta testing) Go to the [**Releases**](https://github.com/gorkamunoz/andi_fiji/releases) page of this repository.
2. Under the latest release, download the file **`andi-fiji-X.Y.Z.jar`**.
3. Drag the `.jar` file into the **`Fiji.app/plugins/`** folder  
   *(on Windows: `C:\Users\<you>\Fiji.app\plugins\`)*
4. **Restart Fiji.**
5. The plugin is now available under **Plugins › AnDi**.

### For developers

```bash
git clone https://github.com/gorkamunoz/andi_fiji.git
cd andi_fiji
mvn clean package
# then copy target/andi-fiji-*-SNAPSHOT.jar into Fiji.app/plugins/
```

Requires Java 8+ and Maven. The build uses the [pom-scijava](https://github.com/scijava/pom-scijava) parent POM.

---

## Usage

1. **Plugins › AnDi › Trajectory Analysis**
2. Select one or more TrackMate CSV files.
3. The analysis window opens with all tabs pre-populated using default parameters.
4. Adjust parameters (lag ranges, bins, frame interval) and click **Compute** / **Compute D** / **Compute α** to rerun with custom settings.

---

## Citation

If you use this plugin in your work, please cite:

> *[citation placeholder – add your paper or the AnDi challenge reference here]*

---

## License

[MIT](LICENSE) — © 2025 Gorka Muñoz-Gil
