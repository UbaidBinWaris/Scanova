"""Builds the app's flattening model, assets/models/uvdoc-mesh.onnx, from UVDoc's official weights.

UVDoc (Verhoeven, Magne, Sorkine-Hornung; SIGGRAPH Asia 2023), MIT licence,
https://github.com/tanguymagne/UVDoc. The published ONNX conversion takes the
whole photo and returns the flattened image; the app instead wants the page
*mesh*, so it can store it in the page's recipe and flatten at full resolution
without the model. This script exports the PyTorch model with a fixed input
(1 x 3 x 712 x 488, RGB scaled to 0..1) and its two outputs:

  mesh     1 x 2 x 45 x 31   x, y of each mesh point in -1..1 of the input (align_corners)
  surface  1 x 3 x 45 x 31   UVDoc's 3D estimate of the page (not used by the app)

and stores the weights as float16 with a Cast to float32 in front of each
(half the size; the maths stays float32). Measured on Book100 through OpenCV's
dnn module: MS-SSIM 0.4537 (float32 weights) and 0.4536 (float16 weights);
largest mesh difference 1.9e-4 (docs/ai-image-research.md).

Run, with a checkout of UVDoc at commit 4c9b82b537057aff2526e6dd118a847cdd072e82:

    gh repo clone tanguymagne/UVDoc /tmp/UVDoc -- --depth 1
    uv run --no-project --index https://download.pytorch.org/whl/cpu \
        --with torch==2.15.0 --with onnx==1.23.2 --with numpy \
        python3 -I apps/android/feature/scan/eval/uvdoc_export.py /tmp/UVDoc out/

The shipped file (SHA-256 d2cff00114e406445e85fc685f8b8ef4d255361109c7a5a14367b1f2b57316ce,
16,041,896 bytes) was made with torch 2.15.0+cpu and onnx 1.23.2; other versions
may serialise the same model to different bytes. ModelAssetsTest pins the shipped file.
"""
import hashlib
import subprocess
import sys
from pathlib import Path

import numpy as np

UVDOC_COMMIT = "4c9b82b537057aff2526e6dd118a847cdd072e82"
WEIGHTS_SHA256 = "7e90861b8a516eb4bc51f84bd889cb77275743d2d1d3ca8091951ec9f2b7da23"
IN_W, IN_H = 488, 712


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main(checkout, out_dir):
    checkout, out_dir = Path(checkout).resolve(), Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    head = subprocess.run(["git", "-C", str(checkout), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    if head != UVDOC_COMMIT:
        sys.exit(f"UVDoc checkout is at {head or 'unknown'}, expected {UVDOC_COMMIT}")
    weights = checkout / "model" / "best_model.pkl"
    if sha256(weights) != WEIGHTS_SHA256:
        sys.exit(f"{weights} is not the published UVDoc weights (SHA-256 mismatch)")

    import onnx
    import torch
    from onnx import TensorProto, helper, numpy_helper

    sys.path.insert(0, str(checkout))
    from model import UVDocnet  # UVDoc's own model definition, at the pinned commit

    model = UVDocnet(num_filter=32, kernel_size=5)
    model.load_state_dict(torch.load(weights, map_location="cpu", weights_only=True)["model_state"])
    model.eval()
    fp32 = out_dir / "uvdoc-mesh-fp32.onnx"
    torch.onnx.export(model, (torch.rand(1, 3, IN_H, IN_W),), str(fp32), input_names=["image"],
                      output_names=["mesh", "surface"], opset_version=17, dynamo=False, do_constant_folding=True)

    graph_model = onnx.load(str(fp32))
    graph = graph_model.graph
    casts, initializers = [], []
    for init in list(graph.initializer):
        array = numpy_helper.to_array(init)
        if array.dtype == np.float32 and array.size >= 16:
            half = init.name + "__fp16"
            initializers.append(numpy_helper.from_array(array.astype(np.float16), half))
            casts.append(helper.make_node("Cast", [half], [init.name], to=TensorProto.FLOAT, name=init.name + "__cast"))
        else:
            initializers.append(init)
    del graph.initializer[:]
    graph.initializer.extend(initializers)
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    onnx.checker.check_model(graph_model)
    out = out_dir / "uvdoc-mesh.onnx"
    onnx.save(graph_model, str(out))
    print(f"torch {torch.__version__}, onnx {onnx.__version__}")
    for path in (fp32, out):
        print(f"{path}: {path.stat().st_size} bytes, SHA-256 {sha256(path)}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
