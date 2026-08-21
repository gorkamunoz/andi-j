# AnDi-J

**AnDi-J** is a [Fiji](https://fiji.sc/)/ImageJ plugin for **anomalous-diffusion analysis of single-particle tracking (SPT) data**, combining statistical and machine-learning (ML) analysis. It works at two scales: per-trajectory observables, and per-frame predictions for fine-grained segmentation of trajectories with expected diffusion changes.

📖 **For the full documentation, see the [AnDi-J Wiki](https://github.com/gorkamunoz/andi_fiji/wiki).**

---

## Features

AnDi-J is organised around two menu commands under **`Plugins ▸ AnDi`**:

| Command | Purpose |
|---|---|
| [**Trajectory Analysis**](https://github.com/gorkamunoz/andi_fiji/wiki/Trajectory-Analysis) | Per-trajectory observables: MSD analysis, diffusion coefficient *D*, anomalous exponent *α*, diffusion-model classification, turning angles and power-spectral density..
| [**Trajectory Segmentation**](https://github.com/gorkamunoz/andi_fiji/wiki/Trajectory-Segmentation) | Per-frame predictions of *α*, *D* and diffusive state (immobile, confined, free diffusion, or directed)along each trajectory, as well as  diffusion change-point detection. |

---

## Installation

### Recommended

1. Go to the [**Releases**](https://github.com/gorkamunoz/andi_fiji/releases) page of this repository.
2. Under the latest release, download the file **`andi-j-X.Y.Z.jar`**.
3. This plugin uses **ONNX Runtime** for its machine-learning predictions. You can download it directly from Maven Central:
   [`onnxruntime-1.17.0.jar`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.17.0/onnxruntime-1.17.0.jar)
4. Copy **both** `andi-j-X.Y.Z.jar` and `onnxruntime-1.17.0.jar` into the
   **`Fiji.app/jars/`** folder. 
5. **Restart Fiji.**
6. The plugin is now available under **Plugins › AnDi-J**.

### Build from scratch

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

**Debug mode:** AnDi-J keeps its diagnostics quiet by default. To see them, enable ImageJ's debug mode:

**`Edit ▸ Options ▸ Misc… ▸ Debug mode`**

and open **`Window ▸ Console`**. With debug mode on, the plugin prints information about the ML-models (ONNX model input/output tensor shapes and CPU/GPU usage). Genuine anomalies (e.g. a custom model returning an unexpected output type) are always reported, debug mode or not.

#### GPU / CUDA support
ML execution is prepared to run both in CPU / GPU. The later requires building the plugin from scracth against `onnxruntime_gpu` (with a matching CUDA/cuDNN install). For that, you must change `pom.xml` onxxruntim call to:

```xml
<artifactId>onnxruntime_gpu</artifactId>
<version>1.xx.0</version>
```
where `xx` depends on your current CUDA installation. Then, just follow the build from scratch recipe above, copying this time the relevant `onnxruntime_gpu` .jar file.

---

## Citation

If you use this plugin in your work, please cite:

> *[citation placeholder – add your paper or the AnDi challenge reference here]*

---

## License

[MIT](LICENSE) — © 2025 Gorka Muñoz-Gil
