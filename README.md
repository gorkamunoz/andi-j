<p align="center">
<img width="100" src="utils/images/logo.png">
</p>
<h3 align="center">

</h3>

</p>


<h1 align="center">
AnDi-J
</h1>
<h4 align="center">
Diffusion trajectory analysis and segmentation in Fiji
</h4>


<p align="center">
<a href="https://github.com/gorkamunoz/andi-j/releases"><img alt="Version" src="https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fgorkamunoz%2Fandi-j%2Fmain%2Fpom.xml&query=%2F%2A%5Blocal-name%28%29%3D%27project%27%5D%2F%2A%5Blocal-name%28%29%3D%27version%27%5D&label=version&color=blue"></a>
<a href="https://github.com/gorkamunoz/andi-j/wiki"><img alt="Documentation" src="https://img.shields.io/badge/documentation-wiki-green"></a>
<a href="https://gorkamunoz.github.io/andi-j/"><img alt="Benchmarks" src="https://img.shields.io/badge/benchmarks-online-orange"></a>
</p>

> **Disclaimer:** AnDi-J is currently in beta version. Expect broken proceeses. Please open an [Issue](https://github.com/gorkamunoz/andi-j/issues) to notify of any problem or request enhacements!

**AnDi-J** is a [Fiji](https://fiji.sc/)/ImageJ plugin for **anomalous-diffusion analysis of single-particle tracking (SPT) data**, combining statistical and machine-learning (ML) analysis. It works at two scales: per-trajectory observables, and per-frame predictions for fine-grained segmentation of trajectories with expected diffusion changes.

📖 **For the full documentation, see the [AnDi-J Wiki](https://github.com/gorkamunoz/andi-j/wiki).**

---

## Features

AnDi-J is organised around two menu commands under **`Plugins ▸ AnDi-J`**:

| Command | Purpose |
|---|---|
| [**Trajectory Analysis**](https://github.com/gorkamunoz/andi-j/wiki/Trajectory-Analysis) | Per-trajectory observables: MSD analysis, diffusion coefficient *D*, anomalous exponent *α*, diffusion-model classification, turning angles and power spectral density (PSD). |
| [**Trajectory Segmentation**](https://github.com/gorkamunoz/andi-j/wiki/Trajectory-Segmentation) | Per-frame predictions of *α*, *D* and diffusive state (immobile, confined, free diffusion, or directed) along each trajectory, as well as diffusion change-point detection. |

---

## Installation

### From the update site

The easiest way to install AnDi-J, and to keep it up to date, is to [follow](https://imagej.net/update-sites/following) the **AnDi-J** [update site](https://imagej.net/list-of-update-sites/):

1. Start **Fiji** (or [download and install it](https://imagej.net/software/fiji/downloads) first).
2. Select **Help › Update...** from the menu bar.
3. Click on the **Manage update sites** button.
4. Scroll down the list and tick the checkbox for the **AnDi-J** update site, then click **Close**. If **AnDi-J** is missing from the list, click **Update URLs** to refresh it.
5. Click **Apply changes** to install the plugin.
6. Restart Fiji.

The plugin is then available under **Plugins › AnDi-J**.

> The update site installs AnDi-J together with the **CPU** build of ONNX Runtime. For GPU (CUDA) acceleration, available only on Windows and Linux, replace `onnxruntime-1.26.0.jar` with `onnxruntime_gpu-1.26.0.jar` ([link](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime_gpu/1.26.0/onnxruntime_gpu-1.26.0.jar)) in the `Fiji.app/jars/` folder (see the requirements below).

### Manual installation

1. Go to the [**Releases**](https://github.com/gorkamunoz/andi-j/releases) page of this repository.
2. Under the latest plugin release, download the file **`andi-j-X.Y.Z.jar`**. It already contains the two default ML models.
3. AnDi-J uses **ONNX Runtime** for its machine-learning predictions, on **CPU** or **GPU** (CUDA). The GPU is much faster, and needs CUDA Toolkit 12.x and cuDNN 9.x for CUDA 12 (see [Other CUDA versions](#other-cuda-versions) otherwise). Download **one** of the two ONNX Runtime jars from Maven Central:
   - CPU: [`onnxruntime-1.26.0.jar`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.26.0/onnxruntime-1.26.0.jar)
   - GPU/CUDA (Windows and Linux only): [`onnxruntime_gpu-1.26.0.jar`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime_gpu/1.26.0/onnxruntime_gpu-1.26.0.jar)
4. Copy the `andi-j` jar and the ONNX Runtime jar into the **`Fiji.app/jars/`** folder. Keep a single ONNX Runtime jar in that folder.
5. **Restart Fiji.**
6. The plugin is now available under **Plugins › AnDi-J**.

With the GPU jar, AnDi-J runs the models on the GPU when CUDA is available and on the CPU otherwise.

### Build from source

```bash
git clone https://github.com/gorkamunoz/andi-j.git
```

Download the two default ML models from the latest model release on the [Releases](https://github.com/gorkamunoz/andi-j/releases) page and place them in `andi-j/src/main/resources/models/`. The plugin loads them by name, so rename the files, if needed, to exactly:

- `andi-j-analysis-latest.onnx` (Trajectory Analysis model)
- `andi-j-seg-latest.onnx` (Trajectory Segmentation model)

Then:

```bash
cd andi-j
mvn clean package
cp target/andi-j-X.Y.Z.jar Fiji.app/jars/   # not the -sources.jar
```

The build downloads the **GPU** build of ONNX Runtime (Windows and Linux only) into your local Maven cache, so you can copy it from there:

```bash
cp ~/.m2/repository/com/microsoft/onnxruntime/onnxruntime_gpu/1.26.0/onnxruntime_gpu-1.26.0.jar Fiji.app/jars/
```

For the CPU build, download `onnxruntime-1.26.0.jar` as in [Manual installation](#manual-installation).

Requires Java 8+ and Maven. The build uses the [pom-scijava](https://github.com/scijava/pom-scijava) parent POM.

**Debug mode:** AnDi-J keeps its diagnostics quiet by default. To see them, enable ImageJ's debug mode:

**`Edit ▸ Options ▸ Misc… ▸ Debug mode`**

and open **`Window ▸ Console`**. With debug mode on, the plugin prints information about the ML-models (ONNX model input/output tensor shapes and CPU/GPU usage). Genuine anomalies (e.g. a custom model returning an unexpected output type) are always reported, debug mode or not.

### Other CUDA versions

If your machine has a different CUDA toolkit than the one ONNX Runtime 1.26.0 needs, use the matching `onnxruntime_gpu` release instead:

1. Check your installed CUDA version: `nvcc --version`.
2. Look up which `onnxruntime_gpu` release supports that CUDA version in the
   [ONNX Runtime CUDA Execution Provider compatibility table](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html).
3. Download the matching jar from Maven Central:
   `https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime_gpu/<version>/onnxruntime_gpu-<version>.jar`
4. Put it in `Fiji.app/jars/` in place of the ONNX Runtime jar that is there.
5. Restart Fiji.

The plugin uses whichever ONNX Runtime jar is in `Fiji.app/jars/`, so no rebuild is needed. AnDi-J is tested with ONNX Runtime 1.26.0.


---

## Citation

If you use this plugin in your work, please cite:

> *soon*

---

## License

[MIT](LICENSE) — © 2025 Gorka Muñoz-Gil
