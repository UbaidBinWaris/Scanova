"""Builds model/gcdrnet.onnx, the page clean-up model of the image AI runtime.

GCDRNet (Zhang, Liang, Ding, Jin; "Appearance Enhancement for Camera-captured
Document Images in the Wild", IEEE TAI 2023), MIT licence,
https://github.com/ZZZHANG-jx/GCDRNet. Two networks run one after the other
(the authors' infer.py): GCNet estimates the page's shading at full
resolution, the photo is divided by it, and DRNet restores the detail from the
photo and that division. This script exports both, chained exactly that way,
as one ONNX graph:

  image     1 x 3 x H x W   BGR scaled to 0..1, H and W multiples of 32
  enhanced  1 x 3 x H x W   the cleaned page, same layout

Weights: the authors' checkpoints (`gcnet/checkpoint.pkl`, `drnet/checkpoint.pkl`
in their OneDrive folder linked from the README). The Hugging Face mirror
FahNos/GCDRnet holds byte-identical copies (checked on 2026-11-16 against the
OneDrive download); either source passes the SHA-256 checks below.

Run, with a checkout of GCDRNet at commit 415c97a9a64d0a796dbbad43485ce2ab102f2a7d:

    gh repo clone ZZZHANG-jx/GCDRNet /tmp/GCDRNet -- --depth 1
    uv run --no-project --index https://download.pytorch.org/whl/cpu \
        --with torch==2.15.0 --with onnx==1.23.2 --with timm==1.0.31 --with numpy \
        python3 -I apps/ai-image/tools/export_gcdrnet.py /tmp/GCDRNet gcnet.pkl drnet.pkl out/

The shipped file (SHA-256 36573a09a9e6d905e12ce7e0c71e859cd11b92aa81d9eff637c8d6f8bdeb8aeb,
22,223,885 bytes) was made with torch 2.15.0+cpu, onnx 1.23.2 and timm 1.0.31,
twice, byte-identical. ONNX Runtime matches PyTorch to 2.1e-5 (0.01 grey levels)
on RealDAE photos. test_server.py pins the shipped file.
"""

import hashlib
import subprocess
import sys
from pathlib import Path

GCDRNET_COMMIT = "415c97a9a64d0a796dbbad43485ce2ab102f2a7d"
GCNET_SHA256 = "dbc1257f63b93ff1e55b148d9a6897929f93c253feac00454d81c67e7dbf910a"
DRNET_SHA256 = "fc31351af8deb40e490e8dfd450e29f84c11ffd64ff7e35ce57d21124bd5d53d"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(checkout: str, gcnet: str, drnet: str, out_dir: str) -> None:
    repo = Path(checkout).resolve()
    head = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    if head != GCDRNET_COMMIT:
        sys.exit(f"GCDRNet checkout is at {head or 'unknown'}, expected {GCDRNET_COMMIT}")
    weights = {Path(gcnet): GCNET_SHA256, Path(drnet): DRNET_SHA256}
    for path, expected in weights.items():
        if sha256(path) != expected:
            sys.exit(f"{path} is not the authors' checkpoint (SHA-256 mismatch)")

    import onnx
    import torch
    import torch.nn.functional as F

    sys.path.insert(0, str(repo / "models" / "UNeXt"))
    from unext import UNext_full_resolution_padding, UNext_full_resolution_padding_L_py_L  # the authors' code

    def load(model, path):
        state = torch.load(path, map_location="cpu", weights_only=True)["model_state"]
        model.load_state_dict({k[7:] if k.startswith("module.") else k: v for k, v in state.items()})
        return model.eval()

    gc = load(UNext_full_resolution_padding(num_classes=3, input_channels=3, img_size=512), gcnet)
    dr = load(UNext_full_resolution_padding_L_py_L(num_classes=3, input_channels=6, img_size=512), drnet)

    class GCDRNet(torch.nn.Module):
        # The attribute names (m1, m2) become the names of the weights in the file:
        # changing them changes the file's bytes (and SHA-256), not what it computes.
        def __init__(self):
            super().__init__()
            self.m1, self.m2 = gc, dr

        def forward(self, x):
            # infer.py: shading at full resolution, nearest upsampling, divide, clamp, restore.
            shadow = F.interpolate(self.m1(x), size=(x.shape[2], x.shape[3]))
            return self.m2(torch.cat((x, torch.clamp(x / shadow, 0, 1)), 1))[0]

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    target = out / "gcdrnet.onnx"
    torch.onnx.export(
        GCDRNet().eval(),
        (torch.rand(1, 3, 512, 384),),
        str(target),
        input_names=["image"],
        output_names=["enhanced"],
        opset_version=17,
        dynamo=False,
        do_constant_folding=True,
        dynamic_axes={"image": {2: "height", 3: "width"}, "enhanced": {2: "height", 3: "width"}},
    )
    onnx.checker.check_model(onnx.load(str(target)))
    print(f"torch {torch.__version__}, onnx {onnx.__version__}")
    print(f"{target}: {target.stat().st_size} bytes, SHA-256 {sha256(target)}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
