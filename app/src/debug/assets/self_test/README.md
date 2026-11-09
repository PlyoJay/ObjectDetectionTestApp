known.png is the app's lossless final 640x640 inference input from the connected
test device's debug_detection/20261006_161158_421/input/frame_000205.png.
It contains real bollards, with RGB 114 side padding. No image edits were made.

Historical fixture verification model (no longer bundled): best.tflite, SHA-256:
eb605f541257c2ee216e8198d796172201435e03ba5f2d16d9c7bc9372602281
PC LiteRT 2.2.0, RGB /255, NCHW, confidence 0.2, class-wise NMS 0.45:
maximum class score 0.9288212657, 10 candidates >=0.2, one final detection.

This is a correctness regression fixture, not a model accuracy benchmark.
The asset and self-test Activity are included only in the debug APK.

The debug Self Test now defaults to best_512.tflite (YOLO11s); pass
`--es model yolo11n_512.tflite` for YOLO11n. Both resize this 640px fixture to
512px before inference. The historical score/count above is not a golden
expectation for either current model. See docs/yolo-two-model-cleanup-20261109.md.
