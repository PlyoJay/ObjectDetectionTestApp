"""Inspect real Interpreter tensors, embedded export metadata, and graph boundaries."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
from collections import Counter
from importlib.metadata import version
import numpy as np
import tflite
from ai_edge_litert.interpreter import Interpreter


def inspect(path):
    data = path.read_bytes()
    model = tflite.Model.GetRootAsModel(data, 0)
    graph = model.Subgraphs(0)
    interpreter = Interpreter(model_path=str(path))
    interpreter.allocate_tensors()
    def details(d):
        q = d["quantization_parameters"]
        return {"name": d["name"], "shape": d["shape"].tolist(),
                "shapeSignature": d["shape_signature"].tolist(), "bytes": int(np.prod(d["shape"])) * np.dtype(d["dtype"]).itemsize,
                "dtype": np.dtype(d["dtype"]).name,
                "scale": float(d["quantization"][0]), "zeroPoint": int(d["quantization"][1]),
                "scales": q["scales"].tolist(), "zeroPoints": q["zero_points"].tolist()}
    report = {"model": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
              "inspectionRuntime": {"package": "ai-edge-litert", "version": version("ai-edge-litert"),
                                    "scope": "host inspection, not Android benchmark"},
              "inputs": [details(d) for d in interpreter.get_input_details()],
              "outputs": [details(d) for d in interpreter.get_output_details()]}
    try:
        with zipfile.ZipFile(path) as archive:
            report["exportMetadata"] = json.loads(archive.read("metadata.json"))
    except (zipfile.BadZipFile, KeyError):
        report["exportMetadata"] = None
    builtin_names = {v: k for k, v in vars(tflite.BuiltinOperator).items() if isinstance(v, int)}
    type_names = {v: k for k, v in vars(tflite.TensorType).items() if isinstance(v, int)}
    ops = Counter()
    versions = {}
    conv_macs = 0
    matrix_shapes = []
    for i in range(graph.OperatorsLength()):
        op = graph.Operators(i)
        code = model.OperatorCodes(op.OpcodeIndex())
        name = builtin_names.get(code.BuiltinCode(), str(code.BuiltinCode()))
        if code.CustomCode():
            name += ":" + code.CustomCode().decode()
        ops[name] += 1
        versions.setdefault(name, set()).add(int(code.Version()))
        # One multiply-accumulate per kernel element and output element. Excludes all other ops.
        if name == "CONV_2D":
            weights = graph.Tensors(int(op.Inputs(1))).ShapeAsNumpy()
            output = graph.Tensors(int(op.Outputs(0))).ShapeAsNumpy()
            conv_macs += int(np.prod(output, dtype=np.int64)) * int(np.prod(weights[1:], dtype=np.int64))
        if name == "BATCH_MATMUL":
            matrix_shapes.append({"operator": i,
                "inputs": [graph.Tensors(int(j)).ShapeAsNumpy().tolist() for j in op.InputsAsNumpy()],
                "outputs": [graph.Tensors(int(j)).ShapeAsNumpy().tolist() for j in op.OutputsAsNumpy()]})
    tensors = [graph.Tensors(i) for i in range(graph.TensorsLength())]
    report["graphSummary"] = {
        "subgraphs": model.SubgraphsLength(), "operators": graph.OperatorsLength(),
        "operatorCounts": dict(sorted(ops.items())),
        "operatorVersions": {k: sorted(v) for k, v in sorted(versions.items())},
        "tensorTypes": dict(sorted(Counter(type_names.get(t.Type(), str(t.Type())) for t in tensors).items())),
        "quantizedTensors": sum(t.Quantization() is not None and t.Quantization().ScaleLength() > 0 for t in tensors),
        "dynamicSignatureTensors": sum(any(t.ShapeSignature(j) < 0 for j in range(t.ShapeSignatureLength())) for t in tensors),
        "conv2dMacsOnly": conv_macs,
        "batchMatmulShapes": matrix_shapes,
        "note": "Static FlatBuffer inventory, not Android delegation coverage or a latency measurement. MACs exclude non-CONV_2D ops."
    }
    def tensor(index):
        t = graph.Tensors(int(index))
        entry = {"index": int(index), "name": t.Name().decode(), "shape": t.ShapeAsNumpy().tolist()}
        b = model.Buffers(t.Buffer()).DataAsNumpy()
        if isinstance(b, np.ndarray) and 0 < b.size <= 64 and t.Type() in (0, 2):
            entry["constant"] = np.frombuffer(b.tobytes(), dtype=np.float32 if t.Type() == 0 else np.int32).tolist()
        return entry
    boundaries = list(range(min(3, graph.OperatorsLength()))) + list(range(max(3, graph.OperatorsLength()-5), graph.OperatorsLength()))
    report["graphBoundaries"] = []
    for index in boundaries:
        op = graph.Operators(index)
        report["graphBoundaries"].append({"index": index,
            "builtinCode": model.OperatorCodes(op.OpcodeIndex()).BuiltinCode(),
            "inputs": [tensor(i) for i in op.InputsAsNumpy() if i >= 0],
            "outputs": [tensor(i) for i in op.OutputsAsNumpy()]})
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="+", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = [inspect(p) for p in args.models]
    text = json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False)
    print(text)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text, encoding="utf-8")
