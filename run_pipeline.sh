#!/bin/bash
# run_pipeline.sh

# On récupère le nom du fichier passé en argument (ex: email.tif)
FILENAME=$1

# On lance Python en lui donnant le chemin complet vers le dossier images
echo "[1/2] Lancement OCR sur images/$FILENAME"
python3 inference.py "images/$FILENAME"

# Si le JSON est généré, on lance Clojure
if [ -f "resultat_ocr3.json" ]; then
    echo "[2/2] Analyse Metadata avec Clojure..."
    clojure -M -m parsejson
else
    echo "ERREUR : Python n'a pas généré resultat_ocr3.json"
    exit 1
fi