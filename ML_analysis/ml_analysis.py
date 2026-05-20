from keras.models import load_model

# Load model for predicting anomalous exponents
model_exp = load_model('GRU_exponents_fbm_alpha=0.05-0.1_Tmax=30.h5')


# Example on predicting andi datasets

import numpy as np
from andi_datasets.datasets_theory import datasets_theory as DT
from andi_datasets.utils_trajectories import normalize

N = 100; T = 30; exponents = np.arange(0.1,1,0.05)

dif = np.zeros((2, N*len(exponents)))

for idx, model in enumerate([2, 0]):
    dataset = DT().create_dataset(T = T, N_models = N, exponents = exponents, models = [model], 
                                  load_trajectories = False)
    np.random.shuffle(dataset)
    X_e = normalize(dataset[:, 2:]).reshape(dataset.shape[0], dataset.shape[1]-2, 1) # shape is (BS, T, 1)
    Y_e = dataset[:, 1] 
    
    expo_p = model_exp.predict(X_e)#[X_e, X_e])
    dif[idx] = (expo_p.flatten()-Y_e)

for mod, error in zip(['FBM','ATTM'], np.abs(dif).mean(1)):
    print(mod, f': MAE = {np.round(error,3)}')