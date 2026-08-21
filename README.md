# AnDi-J

**AnDi-J** is a [Fiji](https://fiji.sc/)/ImageJ plugin for **anomalous-diffusion analysis of single-particle tracking (SPT) data**, combining statistical and machine-learning (ML) analysis. It works at two scales: per-trajectory observables, and per-frame predictions for fine-grained segmentation of trajectories with expected diffusion changes.

📖 **For the full documentation of every tab and feature, see the [AnDi-J Wiki](https://github.com/gorkamunoz/andi_fiji/wiki).**

---

## Features

AnDi-J is organised around two menu commands under **`Plugins ▸ AnDi`**:

| Command | Purpose |
|---|---|
| [**Trajectory Analysis**](https://github.com/gorkamunoz/andi_fiji/wiki/Trajectory-Analysis) | Per-trajectory observables: MSD analysis, diffusion coefficient *D*, anomalous exponent *α*, the *D*–*α* plane, diffusion-model classification, and turning angles. |
| [**Trajectory Segmentation**](https://github.com/gorkamunoz/andi_fiji/wiki/Trajectory-Segmentation) | Per-frame predictions of *α* and *D* along each trajectory, change-point detection into homogeneous segments, and the diffusive state (immobile, confined, free diffusion, or directed) of each segment. |

Both commands share the same **Data Manager** for loading and grouping data, and both can analyse several experiments (conditions) at once, each with its own colour, overlaid for comparison.

---

## Input format

The plugin reads the **Spots CSV export from TrackMate** (tested with TrackMate ≥ 7).  
Required columns (case-insensitive): `TRACK_ID`, `POSITION_X`, `POSITION_Y`, `FRAME`.


---

## Installation

### For users

1. Go to the [**Releases**](https://github.com/gorkamunoz/andi_fiji/releases) page of this repository.
2. Under the latest release, download the file **`andi-j-X.Y.Z.jar`**.
3. This plugin uses **ONNX Runtime** for its machine-learning predictions
   (anomalous exponent, diffusion model, and trajectory segmentation). Download
   it directly from Maven Central — no account or build tool needed:
   [`onnxruntime-1.17.0.jar`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.17.0/onnxruntime-1.17.0.jar)
4. Copy **both** `andi-j-X.Y.Z.jar` and `onnxruntime-1.17.0.jar` into the
   **`Fiji.app/jars/`** folder  
   *(on Windows: `C:\Users\<you>\Fiji.app\jars\`)*
5. **Restart Fiji.**
6. The plugin is now available under **Plugins › AnDi**.

### For developers

```bash
git clone https://github.com/gorkamunoz/andi_fiji.git
cd andi_fiji
mvn clean package
# then copy target/andi-j-*.jar into Fiji.app/jars/
```

`mvn clean package` also resolves `onnxruntime-1.17.0.jar` into your local
Maven cache, so you can copy it from there instead of downloading it again:

```bash
cp ~/.m2/repository/com/microsoft/onnxruntime/onnxruntime/1.17.0/onnxruntime-1.17.0.jar Fiji.app/jars/
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
