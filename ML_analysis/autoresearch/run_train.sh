#!/bin/bash
cd programs/ && conda run -n kistep bash -c "python train.py > run.log 2>&1"