Place the converted ONNX model here before building:

    GRU_exponents.onnx

To generate it, run from ML_analysis/:

    python convert_to_onnx.py

Then rebuild the Maven project:

    mvn clean package

The model will be bundled into the JAR as a classpath resource
and loaded automatically when the ML method is selected.
If the file is absent the user will be prompted to load a model
manually via the "Load…" button.
