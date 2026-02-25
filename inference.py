import sys
import torch
import json
import logging
from doctr.models import ocr_predictor
from doctr.io import DocumentFile

logging.getLogger("libav").setLevel(logging.ERROR)

def run_ocr(file_path):
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device: {device}", file=sys.stderr)

    model = ocr_predictor(det_arch='db_resnet50', reco_arch='crnn_vgg16_bn', pretrained=True).to(device)
    doc = DocumentFile.from_images(file_path)
    result = model(doc)
    
    # SORTIE PIPE : Uniquement le JSON sur stdout
    output = result.export()
    sys.stdout.write(json.dumps(output))
    sys.stdout.flush()

if __name__ == "__main__":
    if len(sys.argv) > 1:
        run_ocr(sys.argv[1])