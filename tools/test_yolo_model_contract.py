"""Read-only regression checks against the actual bundled TFLite tensors and export names.

Run: .venv/Scripts/python.exe -m unittest discover -s tools -p test_yolo_model_contract.py -v
"""
import json
from pathlib import Path
import unittest
import zipfile

from ai_edge_litert.interpreter import Interpreter
import numpy as np


ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
LEGACY = ["bollard", "person", "bicycle", "car", "motorcycle", "bus", "truck", "stop sign", "bench"]
CURRENT = LEGACY + ["cat", "dog"]


class YoloModelContractTest(unittest.TestCase):
    def test_actual_models_match_verified_names_and_static_float_tensor_contracts(self):
        self.assertEqual(CURRENT, ASSETS.joinpath("labels.txt").read_text(encoding="utf-8").splitlines())
        for filename, size, candidates, labels in (
            ("best.tflite", 640, 8400, LEGACY), ("best_512.tflite", 512, 5376, CURRENT)
        ):
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


if __name__ == "__main__":
    unittest.main()
