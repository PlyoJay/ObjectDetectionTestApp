"""Read-only regression checks against the actual bundled TFLite tensors and export names.

Run: .venv/Scripts/python.exe -m unittest discover -s tools -p test_yolo_model_contract.py -v
"""
import json
import hashlib
from pathlib import Path
import unittest
import zipfile

from ai_edge_litert.interpreter import Interpreter
import numpy as np
import tflite


ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
CURRENT = ["bollard", "person", "bicycle", "car", "motorcycle", "bus", "truck", "stop sign", "bench",
           "cat", "dog", "cardboard_box"]
MODELS = {
    "best_512.tflite": "591509278b3a1fe114a0084d4841b948a93b637c22005b7becf06309cb906667",
    "yolo11n_512.tflite": "fa8f25724210a15c4259c82cbf8d87a4169d0ab7f1bb644a6620173aa52bae32",
}


class YoloModelContractTest(unittest.TestCase):
    def test_actual_models_match_verified_names_and_static_float_tensor_contracts(self):
        self.assertEqual(CURRENT, ASSETS.joinpath("labels.txt").read_text(encoding="utf-8").splitlines())
        for filename in MODELS:
            size, candidates, labels = 512, 5376, CURRENT
            with self.subTest(model=filename):
                path = ASSETS / filename
                with zipfile.ZipFile(path) as archive:
                    metadata = json.loads(archive.read("metadata.json"))
                self.assertEqual({str(i): name for i, name in enumerate(labels)}, metadata["names"])
                self.assertEqual("Detect", metadata["head"])
                self.assertEqual("detect", metadata["task"])
                self.assertFalse(metadata["end2end"])
                interpreter = Interpreter(model_path=str(path), num_threads=1)
                interpreter.allocate_tensors()
                inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
                self.assertEqual(1, len(inputs))
                self.assertEqual(1, len(outputs))
                for tensor, expected in ((inputs[0], [1, 3, size, size]),
                                         (outputs[0], [1, 4 + len(labels), candidates])):
                    self.assertEqual(expected, tensor["shape"].tolist())
                    self.assertEqual(expected, tensor["shape_signature"].tolist())
                    self.assertEqual(np.float32, tensor["dtype"])
                interpreter.set_tensor(inputs[0]["index"], np.zeros(inputs[0]["shape"], dtype=np.float32))
                interpreter.invoke()
                output = interpreter.get_tensor(outputs[0]["index"])
                self.assertTrue(np.isfinite(output).all())
                self.assertTrue(((output[:, 4:, :] >= 0) & (output[:, 4:, :] <= 1)).all())

    def test_only_two_verified_models_remain_and_baseline_is_unchanged(self):
        self.assertEqual(set(MODELS), {p.name for p in ASSETS.glob("*.tflite")})
        for filename, digest in MODELS.items():
            self.assertEqual(digest, hashlib.sha256(ASSETS.joinpath(filename).read_bytes()).hexdigest())

    def test_both_graphs_emit_normalized_xywh_and_twelve_sigmoid_scores_without_nms(self):
        for filename in MODELS:
            with self.subTest(model=filename):
                self.check_graph(filename)

    def check_graph(self, filename):
        model = tflite.Model.GetRootAsModel(ASSETS.joinpath(filename).read_bytes(), 0)
        self.assertEqual(1, model.SubgraphsLength())
        graph = model.Subgraphs(0)
        ops = [graph.Operators(i) for i in range(graph.OperatorsLength())]
        def code(op):
            return model.OperatorCodes(op.OpcodeIndex()).BuiltinCode()
        codes = {code(op) for op in ops}
        self.assertNotIn(tflite.BuiltinOperator.NON_MAX_SUPPRESSION_V4, codes)
        self.assertNotIn(tflite.BuiltinOperator.NON_MAX_SUPPRESSION_V5, codes)
        self.assertTrue(all(not model.OperatorCodes(op.OpcodeIndex()).CustomCode() for op in ops))
        producers = {int(index): op for op in ops for index in op.OutputsAsNumpy()}
        # RGB /255 belongs to the app. The graph only transposes NCHW -> NHWC,
        # pads spatially, then convolves; no graph-side input rescale/channel swap.
        transpose, pad, conv = ops[:3]
        self.assertEqual(tflite.BuiltinOperator.TRANSPOSE, code(transpose))
        self.assertEqual(int(graph.Inputs(0)), int(transpose.Inputs(0)))
        permutation = graph.Tensors(int(transpose.Inputs(1)))
        self.assertEqual([0, 2, 3, 1], np.frombuffer(
            model.Buffers(permutation.Buffer()).DataAsNumpy().tobytes(), dtype=np.int32).tolist())
        self.assertEqual(tflite.BuiltinOperator.PAD, code(pad))
        self.assertEqual(int(transpose.Outputs(0)), int(pad.Inputs(0)))
        self.assertEqual(tflite.BuiltinOperator.CONV_2D, code(conv))
        self.assertEqual(int(pad.Outputs(0)), int(conv.Inputs(0)))
        concat = producers[int(graph.Outputs(0))]
        self.assertEqual(tflite.BuiltinOperator.CONCATENATION, code(concat))
        xywh, scores = [int(i) for i in concat.InputsAsNumpy()]
        self.assertEqual([1, 4, 5376], graph.Tensors(xywh).ShapeAsNumpy().tolist())
        self.assertEqual([1, 12, 5376], graph.Tensors(scores).ShapeAsNumpy().tolist())
        self.assertEqual(tflite.BuiltinOperator.LOGISTIC, code(producers[scores]))
        normalize = producers[xywh]
        self.assertEqual(tflite.BuiltinOperator.MUL, code(normalize))
        scale = graph.Tensors(int(normalize.Inputs(1)))
        self.assertEqual([1 / 512], np.frombuffer(model.Buffers(scale.Buffer()).DataAsNumpy().tobytes(),
                                               dtype=np.float32).tolist())


if __name__ == "__main__":
    unittest.main()
