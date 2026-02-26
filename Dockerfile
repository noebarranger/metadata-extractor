FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y \
    libgl1-mesa-glx libglib2.0-0 openjdk-17-jdk-headless curl git && \
    rm -rf /var/lib/apt/lists/*

# Clojure
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && \
    chmod +x linux-install.sh && ./linux-install.sh && rm linux-install.sh

RUN pip install --no-cache-dir "python-doctr[torch]" "numpy<2"

# PRE-DOWNLOAD (Pour gagner du temps au run)
RUN python3 -c "from doctr.models import ocr_predictor; ocr_predictor(det_arch='db_resnet50', reco_arch='crnn_vgg16_bn', pretrained=True)"

WORKDIR /app
COPY deps.edn /app/
RUN clojure -P

COPY run_pipeline.sh /app/
COPY . /app
RUN chmod +x run_pipeline.sh

COPY images /app/images


# Commande par défaut
ENTRYPOINT ["./run_pipeline.sh"]