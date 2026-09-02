# ObjectDetectionTestApp Evaluation Capture

The app includes test-only capture and recording controls on the camera screen.

## Files

Files are saved under the app-specific external files directory, so Android 10+
does not require shared storage write permission.

The original and overlay JPEG files are also published through MediaStore and
can be viewed directly in the phone gallery:

```text
Pictures/GOTORO/Original/{timestamp}_original.jpg
Pictures/GOTORO/Overlay/{timestamp}_overlay.jpg
```

The evaluation JSON remains in the app-specific directory because it is meant
for PC-side analysis rather than the photo gallery.

Typical device path:

```text
/storage/emulated/0/Android/data/com.samin.objectdetection/files/ObjectDetectionTestApp/
```

Capture output:

```text
ObjectDetectionTestApp/captures/{timestamp}.jpg
ObjectDetectionTestApp/captures/{timestamp}.json
ObjectDetectionTestApp/captures/{timestamp}_overlay.jpg
```

Recording output:

```text
ObjectDetectionTestApp/recordings/{timestamp}_screen.mp4
ObjectDetectionTestApp/recordings/{timestamp}_detections.jsonl
```

## Evaluation Format

Each JSON capture and each JSONL line stores one analyzed frame:

- `frameTimestampMs`
- `imageWidth`, `imageHeight`
- `roiApplied` and `roi`
- `coordinateSpace: "original_image"`
- `detections[]`

Each detection contains:

- `label`
- `confidence`
- `frameTimestampMs`
- `imageWidth`, `imageHeight`
- `bbox.left`, `bbox.top`, `bbox.right`, `bbox.bottom`

Bounding boxes are stored in the original analyzed image coordinate space, not
the Android PreviewView coordinate space. If ROI/crop is used before inference,
the saved bbox coordinates are mapped back to the original frame. These files
can be matched against ground-truth annotations by timestamp to compute
precision, false-positive rate, miss rate, and bbox IoU.

## Field-test defaults

- The full camera frame is used for inference (`useCenterSquareCrop=false`).
- The legacy center-square ROI can be restored for A/B testing by setting
  `useCenterSquareCrop=true` in `DetectionConfig`.
- Detector diagnostics are enabled by default and use the `BollardDiagnostics`
  log tag.
- Bollard filtering uses confidence 0.20, area ratio 0.002, width ratio 0.010,
  and height ratio 0.010.
