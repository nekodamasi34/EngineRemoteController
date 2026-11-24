"""Export the pinned Microsoft checkpoint and compare CPU detections on real images."""

import argparse
import hashlib
from pathlib import Path
import shutil
import urllib.request

import cv2
import numpy as np
import onnx
import onnxruntime as ort
import torch
from ultralytics import YOLO
from ultralytics.data.augment import LetterBox


NAME = "MDV6-yolov10-c"
URL = f"https://zenodo.org/records/15398270/files/{NAME}.pt?download=1"
WEIGHT_SHA256 = "21ee78a2d4887128e2a4920937d3295b493f44d788d13e1a635378c16dd74ef7"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("images", type=Path, nargs="+", help="Real validation images (include an animal)")
    parser.add_argument("--work", type=Path, default=Path(".ai-work"))
    parser.add_argument("--output", type=Path, default=Path(f"app/src/main/assets/{NAME}.onnx"))
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    weights = args.work / f"{NAME}.pt"
    if not weights.exists():
        temporary = weights.with_suffix(".download")
        with urllib.request.urlopen(URL, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(weights)
    raw = weights.read_bytes()
    if hashlib.sha256(raw).hexdigest() != WEIGHT_SHA256:
        raise ValueError(f"Official checkpoint checksum mismatch. Remove {weights} and retry.")
    print("Weights SHA-256:", hashlib.sha256(raw).hexdigest())
    torch.set_num_threads(2)
    model = YOLO(str(weights))
    print("Classes:", model.names)
    assert model.names == {0: "animal", 1: "person", 2: "vehicle"}
    exported = Path(model.export(format="onnx", imgsz=640, batch=1, opset=17, half=False,
                                 simplify=False, dynamic=False, nms=False, device="cpu"))
    graph = onnx.load(exported)
    # Ultralytics adds the current local time; remove it from the packaged artifact.
    for i, entry in enumerate(graph.metadata_props):
        if entry.key == "date":
            del graph.metadata_props[i]
            break
    onnx.checker.check_model(graph)
    onnx.save(graph, exported)
    assert not any(node.op_type == "NonMaxSuppression" for node in graph.graph.node)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(exported), options, providers=["CPUExecutionProvider"])
    inputs, outputs = session.get_inputs(), session.get_outputs()
    print("Input/output:", [(node.name, node.shape, node.type) for node in inputs + outputs])
    assert [(n.name, n.shape, n.type) for n in inputs] == [("images", [1, 3, 640, 640], "tensor(float)")]
    assert [(n.name, n.shape, n.type) for n in outputs] == [("output0", [1, 300, 6], "tensor(float)")]
    animal_found = False
    for path in args.images:
        image = cv2.imread(str(path))
        if image is None:
            raise ValueError(f"Unreadable image: {path}")
        pred = model.predict(image, imgsz=640, rect=False, conf=0.30, device="cpu", verbose=False)[0]
        rgb = LetterBox(new_shape=(640, 640))(image=image)[:, :, ::-1]
        tensor = np.ascontiguousarray(rgb.transpose(2, 0, 1)[None], dtype=np.float32) / 255
        rows = session.run(None, {"images": tensor})[0][0]
        rows = rows[rows[:, 4] > 0.30].copy()
        h, w = image.shape[:2]
        gain = min(640 / w, 640 / h)
        left = round((640 - round(w * gain)) / 2 - 0.1)
        top = round((640 - round(h * gain)) / 2 - 0.1)
        rows[:, [0, 2]] = np.clip((rows[:, [0, 2]] - left) / gain, 0, w)
        rows[:, [1, 3]] = np.clip((rows[:, [1, 3]] - top) / gain, 0, h)
        reference = pred.boxes.data.cpu().numpy()
        np.testing.assert_array_equal(rows[:, 5], reference[:, 5])
        np.testing.assert_allclose(rows[:, 4], reference[:, 4], atol=0.002, rtol=0)
        np.testing.assert_allclose(rows[:, :4], reference[:, :4], atol=2, rtol=0)
        animal_found |= bool(np.any(rows[:, 5] == 0))
        print(f"{path}: {len(rows)} matching detections {rows.tolist()}")
    if not animal_found:
        raise ValueError("Validation needs at least one confirmed animal detection.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.resolve() != exported.resolve():
        shutil.copyfile(exported, args.output)
    print(f"{args.output}: {args.output.stat().st_size} bytes, SHA-256 {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
