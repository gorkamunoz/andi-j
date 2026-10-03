<!-- Contributing a model to AnDi-J. For any other change, replace this text with a description of the pull request. -->

## Model contribution

This pull request adds `contributions/<id>.yml`, filled in from
[`contributions/template.yml`](https://github.com/gorkamunoz/andi-j/blob/main/contributions/template.yml).

**Method:** <!-- id and version, e.g. my-method v1 -->

### Checklist

- [ ] The pull request only adds `contributions/<id>.yml` (for a new version of a listed method, it keeps the `id` and increases `version`).
- [ ] The method's code is open source, at the repository given in `code`.
- [ ] The ONNX file(s) are in a public record with a DOI (Zenodo or another archive), given in `tasks`.
- [ ] The model follows the I/O contract in [ML Models](https://github.com/gorkamunoz/andi-j/wiki/ML-Models): input `(1, T, 2)` raw positions with a dynamic `T`, and the output names and shapes of its module.
- [ ] The model loads and runs in Fiji with **Load** (AnDi-J version given in `plugin_version`).
- [ ] `scores` holds the results from the Submit pages of the [benchmark website](https://gorkamunoz.github.io/andi-j/), with the metric names of the template.
- [ ] The weights have an explicit `license`.

### Notes

<!-- Anything the maintainers should know: training data, known limitations, hardware used. -->
