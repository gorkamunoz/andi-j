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
<a href="https://doi.org/10.5281/zenodo.23147104"><img alt="DOI" src="https://zenodo.org/badge/DOI/10.5281/zenodo.23147104.svg"></a>
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

If this plugin was useful for your work, please cite:

> G. Muñoz-Gil, *AnDi-J: diffusion analysis of single-particle tracking data in Fiji*, bioRxiv (2026). https://doi.org/10.64898/2026.10.14.756868

```bibtex
@article {andij_paper,
	author    = {Mu{\~n}oz-Gil, Gorka},
	title     = {{AnDi-J}: diffusion analysis of single-particle tracking data in Fiji},
	year      = {2026},
	doi       = {10.64898/2026.10.14.756868},
	publisher = {Cold Spring Harbor Laboratory},
	URL       = {https://www.biorxiv.org/content/early/2026/10/14/2026.10.14.756868},
	journal   = {bioRxiv}
}
```

---

## License

[MIT](LICENSE) — © 2026 Gorka Muñoz-Gil
