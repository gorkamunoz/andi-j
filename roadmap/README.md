# Roadmap

Planned features for AnDi-J and its benchmark website. Larger items link to their own design note.

| Feature | Description | Status |
|---|---|---|
| [CI for new method contributions](CI-new-method-contribution.md) | Contributors propose a model with a JSON description file in a pull request; a GitHub Action validates it, downloads the ONNX, benchmarks it and adds the results to the website. | Planned |
| Batched segmentation inference | Re-export the bundled segmentation model with a dynamic batch axis and run trajectories of equal length in one call, on top of the parallel runs. | Planned |
| Optional test-time augmentation for the default segmentation model | Let the user switch off the 8-view averaging of the bundled segmentation model for a ~8× faster, slightly less accurate prediction (a no-TTA export, or a model input that selects the number of views). | Planned |
