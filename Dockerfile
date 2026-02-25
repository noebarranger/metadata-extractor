# Utilisation d'une image optimisée pour le GPU
FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime

# Éviter les questions interactives lors de l'install
ENV DEBIAN_FRONTEND=noninteractive

# Installation des dépendances système, de la JVM et des outils nécessaires (curl/git)
RUN apt-get update && apt-get install -y \
    libgl1-mesa-glx \
    libglib2.0-0 \
    openjdk-17-jdk-headless \
    curl \
    git \
    && rm -rf /var/lib/apt/lists/*

# Installation de Clojure (via le script officiel)
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh && \
    rm linux-install.sh

# Installation des dépendances Python
RUN pip install --no-cache-dir "python-doctr[torch]" "numpy<2" datasets matplotlib mplcursors
WORKDIR /app

CMD ["sh", "-c", "python3 inference.py image.jpg | clojure -M -m ./process-metadata/src/parsejson.clj"]