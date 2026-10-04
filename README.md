<p align="center">
<img width="100" src="utils/images/logo.png">
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

> **Disclaimer:** AnDi-J is currently in beta version. Expect broken processes. Please open an [Issue](https://github.com/gorkamunoz/andi-j/issues) to report any problem or request enhancements!

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

AnDi-J is available in Fiji through the **AnDi-J** update site: select **Help › Update…**, click **Manage update sites**, tick **AnDi-J**, then **Apply changes** and restart Fiji. The plugin appears under **Plugins › AnDi-J**.

Other installation options (manual installation, GPU acceleration, building from source) are described in the [wiki](https://github.com/gorkamunoz/andi-j/wiki#installation).

---

## Citation

If you use this plugin in your work, please cite:

> *soon*

---

## License

[MIT](LICENSE) — © 2026 Gorka Muñoz-Gil
