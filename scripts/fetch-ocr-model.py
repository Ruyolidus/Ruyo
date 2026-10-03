"""Fetch the pinned Apache-2.0 PaddleOCR detector at build time; inference is offline."""
import hashlib
from pathlib import Path
import urllib.request

DESTINATION = Path(__file__).resolve().parents[1] / 'app/src/main/assets/ocr/ppocr-det.onnx'
SHA256 = 'a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d'
URL = 'https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx'
if not DESTINATION.exists() or hashlib.sha256(DESTINATION.read_bytes()).hexdigest() != SHA256:
    with urllib.request.urlopen(URL, timeout=120) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit('Text detector download did not match the pinned SHA-256. No model was installed.')
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    temporary = DESTINATION.with_suffix('.tmp')
    temporary.write_bytes(data)
    temporary.replace(DESTINATION)
print('Offline text detector verified:', SHA256)
