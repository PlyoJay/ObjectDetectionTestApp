"""Run the same TFLite using final Android PNG or exact input.bin; compare raw outputs.

No Ultralytics preprocessing, auto-resize, sigmoid, or objectness is applied.
The checked-in YOLO11 exports use normalized xywh + class scores, RGB /255.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter


def decode(values, details):
    if np.issubdtype(values.dtype, np.floating):
        return values.astype(np.float32)
    scale, zero = details["quantization"]
    if scale <= 0:
        raise ValueError("Quantized tensor requires a positive per-tensor scale")
    return (values.astype(np.float32) - zero) * scale


def statistics(values, details, layout=None):
    real = decode(values, details)
    finite = real[np.isfinite(real)]
    result = {"shape": list(values.shape), "dtype": values.dtype.name,
        "scale": float(details["quantization"][0]), "zeroPoint": int(details["quantization"][1]),
        "min": float(finite.min()) if finite.size else None,
        "max": float(finite.max()) if finite.size else None,
        "mean": float(finite.mean(dtype=np.float64)) if finite.size else None,
        "nonFinite": int(real.size-finite.size), "sha256": hashlib.sha256(values.tobytes()).hexdigest()}
    if layout:
        result["channelMeansRGB"] = real.mean(axis=(0,2,3) if layout == "NCHW" else (0,1,2), dtype=np.float64).tolist()
    return result


def parse_output(real, classes, width, height, conf, iou_threshold, cap):
    if real.ndim != 3 or real.shape[0] != 1:
        raise ValueError(f"Unsupported raw YOLO output {real.shape}")
    first, last = real.shape[1] == 4 + classes, real.shape[2] == 4 + classes
    if first == last:
        raise ValueError("Output axis must uniquely match 4 + class count; objectness/end2end are unsupported")
    rows = real[0].T if first else real[0]
    scores = np.where(np.isfinite(rows[:,4:]), rows[:,4:], 0).max(axis=1)
    ids = np.argmax(np.where(np.isfinite(rows[:,4:]), rows[:,4:], 0), axis=1)
    raw = {"candidateCount": len(rows), "maxClassScore": float(max(0, scores.max()))}
    for threshold in (0.01,0.05,0.1,0.2):
        raw[f"candidateAbove{threshold:g}"] = int((scores >= threshold).sum())
    candidates = []
    for index in np.flatnonzero(scores >= conf):
        cx,cy,w,h = rows[index,:4]
        if not np.isfinite([cx,cy,w,h]).all() or w <= 0 or h <= 0:
            continue
        box = np.clip([cx-w/2,cy-h/2,cx+w/2,cy+h/2],0,1) * [width,height,width,height]
        if box[2] <= box[0] or box[3] <= box[1]:
            continue
        candidates.append({"candidate": int(index), "classId": int(ids[index]),
                           "confidence": float(scores[index]), "bboxInputPixels": box.tolist()})
    candidates.sort(key=lambda d: -d["confidence"])
    pending = candidates[:cap]
    def iou(a,b):
        l,t = np.maximum(a[:2],b[:2]); r,bt = np.minimum(a[2:],b[2:])
        intersection = max(0,r-l)*max(0,bt-t)
        return intersection / ((a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection+1e-6)
    kept = []
    while pending:
        best = pending.pop(0); kept.append(best)
        pending = [other for other in pending if best["classId"] != other["classId"] or
                   iou(best["bboxInputPixels"],other["bboxInputPixels"]) <= iou_threshold]
    raw["confidencePassed"] = int((scores >= conf).sum())
    raw["detections"] = len(kept)
    return raw, kept


def run(args):
    interpreter = Interpreter(model_path=str(args.model), num_threads=4)
    interpreter.allocate_tensors()
    inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError("Only one input / raw detection output supported")
    inp, out = inputs[0], outputs[0]
    shape = tuple(inp["shape"])
    if len(shape) != 4 or shape[0] != 1 or (shape[1] == 3) == (shape[3] == 3):
        raise ValueError(f"Unsupported/ambiguous RGB input shape: {shape}")
    layout = "NCHW" if shape[1] == 3 else "NHWC"
    height,width = shape[2:] if layout == "NCHW" else shape[1:3]
    if args.tensor:
        values = np.fromfile(args.tensor,dtype=inp["dtype"]).reshape(shape)
    else:
        with Image.open(args.image) as image:
            if image.size != (width,height):
                raise ValueError(f"Use final {width}x{height} Android input.png; got {image.size}. No second letterbox is applied.")
            rgb = np.array(image.convert("RGB"), dtype=np.float32) / np.float32(255)
        real = rgb.transpose(2,0,1)[None] if layout == "NCHW" else rgb[None]
        if np.issubdtype(inp["dtype"], np.integer):
            scale,zero = inp["quantization"]
            if scale <= 0:
                raise ValueError("Invalid quantization scale")
            bounds = np.iinfo(inp["dtype"])
            values = np.clip(np.floor(real / scale + zero + 0.5),bounds.min,bounds.max).astype(inp["dtype"])
        else:
            values = real.astype(inp["dtype"])
    values = np.ascontiguousarray(values)
    if args.simulate_old_layout:
        if layout != "NCHW":
            raise ValueError("Old writer simulation is only meaningful for the new NCHW model")
        values = np.ascontiguousarray(values.transpose(0,2,3,1)).reshape(shape)
    interpreter.set_tensor(inp["index"],values)
    interpreter.invoke()
    output = interpreter.get_tensor(out["index"])
    real = decode(output,out)
    raw,detections = parse_output(real,args.classes,width,height,args.conf,args.iou,args.max_candidates)
    report = {"modelSha256": hashlib.sha256(args.model.read_bytes()).hexdigest(),
        "layout": layout, "simulateOldLayout": args.simulate_old_layout,
        "input": statistics(values,inp,layout), "output": statistics(output,out), "raw": raw,
        "detections": detections}
    if args.android_report:
        android = json.loads(args.android_report.read_text(encoding="utf-8"))
        model_matches = android["modelSha256"] == report["modelSha256"]
        input_matches = android["input"]["sha256"] == report["input"]["sha256"]
        report["comparison"] = {"modelMatches": model_matches, "inputMatches": input_matches}
        if not model_matches or not input_matches:
            raise ValueError("Golden comparison requires the same model and input tensor checksum")
    if args.android_output:
        expected = np.fromfile(args.android_output,dtype=out["dtype"]).reshape(output.shape)
        difference = np.abs(decode(expected,out).astype(np.float64)-real.astype(np.float64))
        close = bool(np.allclose(decode(expected,out),real,atol=args.atol,rtol=args.rtol,equal_nan=False))
        report.setdefault("comparison",{}).update({"outputAllClose": close,
            "outputMaxAbsError": float(difference.max()), "outputMeanAbsError": float(difference.mean())})
    args.directory.mkdir(parents=True,exist_ok=True)
    (args.directory / "input.bin").write_bytes(values.tobytes())
    (args.directory / "output_0.bin").write_bytes(output.tobytes())
    (args.directory / "report.json").write_text(json.dumps(report,indent=2,allow_nan=False),encoding="utf-8")
    print(json.dumps({"layout":layout,"input":report["input"],"raw":raw,
                      "comparison":report.get("comparison"),"directory":str(args.directory)},indent=2))
    if args.android_output and not report["comparison"]["outputAllClose"]:
        raise SystemExit("Raw outputs differ beyond the configured tolerances; inspect report.json")
    if len(detections) < args.min_detections:
        raise SystemExit(f"Expected at least {args.min_detections} detections, got {len(detections)}")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model",type=Path,default=Path("app/src/main/assets/best.tflite"))
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument("--image",type=Path); source.add_argument("--tensor",type=Path)
    p.add_argument("--android-report",type=Path); p.add_argument("--android-output",type=Path)
    p.add_argument("--directory",type=Path,required=True)
    p.add_argument("--simulate-old-layout",action="store_true")
    p.add_argument("--classes",type=int,default=1)
    p.add_argument("--conf",type=float,default=0.2); p.add_argument("--iou",type=float,default=0.45)
    p.add_argument("--max-candidates",type=int,default=100)
    p.add_argument("--min-detections",type=int,default=0)
    p.add_argument("--atol",type=float,default=1e-5); p.add_argument("--rtol",type=float,default=1e-4)
    run(p.parse_args())
