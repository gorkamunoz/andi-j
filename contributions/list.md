# AnDi-J models

Models available for AnDi-J and their scores on the [benchmark website](https://gorkamunoz.github.io/andi-j/).
Trajectory Analysis scores are on the 2D test set of the 1st AnDi Challenge; Trajectory
Segmentation scores on the single trajectory task of the 2nd AnDi Challenge. A dash means the
model does not do that task. To add a model, see
[Contributing a model](https://github.com/gorkamunoz/andi-j/wiki/ML-Models#contributing-a-model).

<table>
  <thead>
    <tr>
      <th rowspan="2">Model</th>
      <th rowspan="2">Version</th>
      <th colspan="2">Trajectory Analysis</th>
      <th colspan="5">Trajectory Segmentation</th>
      <th rowspan="2">Download</th>
    </tr>
    <tr>
      <th>α MAE ↓</th>
      <th>Model F1 ↑</th>
      <th>JI (CP) ↑</th>
      <th>RMSE (CP) ↓</th>
      <th>α MAE ↓</th>
      <th>MSLE (D) ↓</th>
      <th>F1 (diffusive state) ↑</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>AnDi-J default (analysis)</td>
      <td>v1</td>
      <td>0.137</td>
      <td>0.892</td>
      <td>–</td>
      <td>–</td>
      <td>–</td>
      <td>–</td>
      <td>–</td>
      <td>Bundled with the plugin (<a href="https://github.com/gorkamunoz/andi-j/releases">Releases</a>)</td>
    </tr>
    <tr>
      <td>AnDi-J default (segmentation)</td>
      <td>v4</td>
      <td>–</td>
      <td>–</td>
      <td>0.684</td>
      <td>1.755</td>
      <td>0.175</td>
      <td>0.018</td>
      <td>0.919</td>
      <td>Bundled with the plugin (<a href="https://github.com/gorkamunoz/andi-j/releases">Releases</a>)</td>
    </tr>
  </tbody>
</table>
