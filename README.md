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


<!-- <p align="center">
<a href="https://github.com/gorkamunoz/andi-j/releases"><img alt="Version" src="https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fgorkamunoz%2Fandi-j%2Fmain%2Fpom.xml&query=%2F%2A%5Blocal-name%28%29%3D%27project%27%5D%2F%2A%5Blocal-name%28%29%3D%27version%27%5D&label=version&color=blue"></a>
</p> -->

> **Disclaimer:** AnDi-J is currently in beta version. Expect broken proceeses. Please open an [Issue](https://github.com/gorkamunoz/andi-j/issues) to notify of any problem or request enhacements!

**AnDi-J** is a [Fiji](https://fiji.sc/)/ImageJ plugin for **anomalous-diffusion analysis of single-particle tracking (SPT) data**, combining statistical and machine-learning (ML) analysis. It works at two scales: per-trajectory observables, and per-frame predictions for fine-grained segmentation of trajectories with expected diffusion changes.

📖 **For the full documentation, see the [AnDi-J Wiki](https://github.com/gorkamunoz/andi-j/wiki).**

---

## Features

AnDi-J is organised around two menu commands under **`Plugins ▸ AnDi-J`**:

| Command | Purpose |
|---|---|
| [**Trajectory Analysis**](https://github.com/gorkamunoz/andi-j/wiki/Trajectory-Analysis) | Per-trajectory observables: MSD analysis, diffusion coefficient *D*, anomalous exponent *α*, diffusion-model classification, turning angles and power-spectral density..
| [**Trajectory Segmentation**](https://github.com/gorkamunoz/andi-j/wiki/Trajectory-Segmentation) | Per-frame predictions of *α*, *D* and diffusive state (immobile, confined, free diffusion, or directed)along each trajectory, as well as  diffusion change-point detection. |

---

## Installation

### Recommended

1. Go to the [**Releases**](https://github.com/gorkamunoz/andi-j/releases) page of this repository.
2. Under the latest plugin release, download the file **`andi-j-X.Y.Z.jar`**.
3. This plugin uses **ONNX Runtime** for its machine-learning predictions. It provides support both **CPU** and **GPU** (CUDA) support. The latter allows for much faster computations. The current release requires CUDA Toolkit 12.x and cuDNN 9.x for CUDA 12 (see below for other CUDA versions). You can download the corresponding file directly from Maven Central:
   - CPU:
   [`onnxruntime-1.26.0.jar`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.26.0/onnxruntime-1.26.0.jar)
   - GPU/CUDA: [`onnxruntime_gpu-1.26.0.jar`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime_gpu/1.26.0/onnxruntime_gpu-1.26.0.jar) 
   
4. Copy **both** `andi-j` and `onnxruntime` jars into the
   **`Fiji.app/jars/`** folder. 
5. **Restart Fiji.**
6. The plugin is now available under **Plugins › AnDi-J**.

### Build from scratch

```bash
git clone https://github.com/gorkamunoz/andi-j.git
```
You will then need to download the two default ML models (see [Releases](https://github.com/gorkamunoz/andi-j/releases), look for the latest model release). Place these models in `andi-j/src/main/resources/models/`. Then:

```bash
cd andi-j
mvn clean package
# then copy target/andi-j-*.jar into Fiji.app/jars/
```

`mvn clean package` also resolves `onnxruntime-1.26.0.jar` into your local
Maven cache, so you can copy it from there instead of downloading it again:

```bash
cp ~/.m2/repository/com/microsoft/onnxruntime/onnxruntime_gpu/1.26.0/onnxruntime-1.26.0.jar Fiji.app/jars/
```

Requires Java 8+ and Maven. The build uses the [pom-scijava](https://github.com/scijava/pom-scijava) parent POM.

**Debug mode:** AnDi-J keeps its diagnostics quiet by default. To see them, enable ImageJ's debug mode:

**`Edit ▸ Options ▸ Misc… ▸ Debug mode`**

and open **`Window ▸ Console`**. With debug mode on, the plugin prints information about the ML-models (ONNX model input/output tensor shapes and CPU/GPU usage). Genuine anomalies (e.g. a custom model returning an unexpected output type) are always reported, debug mode or not.

#### Custom CUDA installation

If your machine has a different CUDA toolkit installed than the recommended one, you must find the matching `onnxruntime_gpu` release: 

1. Check your installed CUDA version: `nvcc --version` .
2. Look up which `onnxruntime_gpu` release supports that CUDA version in the
   [ONNX Runtime CUDA Execution Provider compatibility table](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html).
3. Download the matching jar from Maven Central:
   `https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime_gpu/<version>/onnxruntime_gpu-<version>.jar`
4. Place that file in `Fiji.app/jars/`.
5. Update the `andi-j` `pom.xml` file with the downloaded version: 
```xml
<artifactId>onnxruntime_gpu</artifactId>
<version>1.xx.0</version>
```
6. Build the plugin from scratch. 
5. Restart Fiji.



---

## Citation

If you use this plugin in your work, please cite:

> *soon*

---

## License

[MIT](LICENSE) — © 2025 Gorka Muñoz-Gil
