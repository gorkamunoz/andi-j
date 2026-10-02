# General

- The tab and everything related to this tab (also title) should be "Trajectory segmentation" not "Segmentation Analysis".

- In all bubles that arise in hover (now clicking, see below first), only put the name of the metric and its value, do not put an arrow or lower / highger better

- Differentiate more the different sections:  increase the line width to the squares separating each section, also a bit more space (just a bit) between sections.

# Intro text:

The text should be: 

[every before is ok] This page compares <b>the AnDi-J default model</b> (${swatch('andij')})
with other methods available for AnDi-J (green color), the methods of the <a href="https://doi.org/10.1038/s41467-025-61949-x" target="_blank" rel="noopener">2nd
AnDi Challenge</a> and your submissions (orange). [delete next sentence]. See Submission tab for information on how to submit your predictions.

- Add a placeholder for the submission Tab, blank currently. Call this tab analysis. Also, 


# Overall performance

- Implement the same no names, only when clicking on the JSC vs RMSE plot.

# Performance vs. number of changepoints

- New feature: put a small button in each plot, if clicked, it makes the plot bigger and puts the other 4 plots on the side, so the increased plot is 2x2 of its current size, and then the rest of plots are of same size but in a 4x4 grid.

- Do not show info on hover, just in clicking on of the points, when the plot is maximized, if not it is clumsy.


# Performance vs. number of changepoints

- Do not show different participants in color, always all in grey, and no legend. Eliminate the how giving you details, it is too much. When clicking on of the line, it highlights it a bit and the window gives you the number.


# Single state experiment

- Do not show the experiment number in the title

- Corrected text: 

One of the benchmark's experiment contains trajectories with no changepoints, so every predicted changepoint is a false positive.  This can be used to compare the accuracy of the method against non-segmentation methods of the Trajectory Analysis module.

- Again no names shown, only when clicked. Again, simplified metric showing in the bublle (no highger better, etc)

# Diffusion type classification

- Text:

Distribution of predicted diffusive state by the five best challenge teams and the AnDi-J default model, against the ground truth.

- Similar as above, put the challenge participants in grey, only shown when clicking.


# Ranking

- Text:

Overall scoring over the benchmark dataset following the challenge's rankings: the mean reciprocal rank (MRR) over the five metrics.

