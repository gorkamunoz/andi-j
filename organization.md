
- Trajectory Analysis
    - After clicking, we choose files. Each file is tagged with the name of the file (e.g exp_1.csv -> exp_1). Multiple files are OK, each tagged properly
    - Gives back a window with few tabs:
        - "TMSD Visualization": Here we plot the tMSD. There are buttons for each experiment, so the plot can be turn on and off. For each experiment, two buttons: Average and All trajs. . The former places / deletes the average tmsd over the experiment. The later shows all trajectories. Trajectories are only solid lines but with much higher transparency than the average. Each experiment has its own color, shared for averaged and all trajectories.
        - "Diffusion coefficient": based on the diffusion coefficient computed same as andi_datasets.analysis.msd_analysis.get_diff_coeff. The user can choose the t_lags by introducing a list ("[1,2,3,4]"). The default is [1,2]. Plot the histogram of the diffusion coefficients for each experiment. Also there is a vertical line with the average. There is an option for choosing the number of bins of the histogram. For each experiment, the user can choose between "2D", which means the D computed from the 2D trajectories, "X" computed only with the X component and "Y", only with the Y component. There is also a small table showing the averages of D for each experiment and columns 2D, X and Y based on the previous.
        - "Anomalous exponent": Same features as previous tab but computes the anomalous exponent based on andi_datasets.analysis.msd_analysis.get_exponent. 
        - "Full analysis": placeholder for now
- Trajectory segmentation (placeholder)

