import os
import sys
import json
import torch
from doctr.models import ocr_predictor
from doctr.io import DocumentFile

def run_ocr(file_path):
    # Vérification de sécurité
    if not os.path.exists(file_path):
        print(f"ERREUR: Le fichier {file_path} est introuvable dans /app", file=sys.stderr)
        sys.exit(1)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Utilisation de : {device}", file=sys.stderr)

    # Chargement (db_resnet50 et crnn_vgg16_bn comme dans tes logs)
    model = ocr_predictor(det_arch='db_resnet50', reco_arch='crnn_vgg16_bn', pretrained=True).to(device)
    
    # Lecture
    doc = DocumentFile.from_images(file_path)
    result = model(doc)
    
    # SAUVEGARDE PHYSIQUE DU JSON
    output_json = "resultat_ocr3.json"
    with open(output_json, "w", encoding="utf-8") as f:
        json.dump(result.export(), f, indent=4)
    
    print(f"Succès: {output_json} généré.", file=sys.stderr)

if __name__ == "__main__":
    if len(sys.argv) > 1:
        run_ocr(sys.argv[1])