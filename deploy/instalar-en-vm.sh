#!/usr/bin/env bash
# Instalación en la VM (Ubuntu 24.04 LTS). Ejecutar una vez, con un usuario con sudo,
# desde la carpeta donde están rutapaq.jar, config.properties, datos/ y deploy/.
set -euo pipefail
sudo apt-get update
sudo apt-get install -y openjdk-17-jre-headless nginx
sudo mkdir -p /opt/rutapaq
sudo cp rutapaq.jar config.properties /opt/rutapaq/
sudo cp -r datos /opt/rutapaq/
sudo sed -i 's/^servidor.host=.*/servidor.host=127.0.0.1/' /opt/rutapaq/config.properties
sudo chown -R ubuntu:ubuntu /opt/rutapaq
sudo cp deploy/rutapaq.service /etc/systemd/system/
sudo cp deploy/nginx-rutapaq.conf /etc/nginx/sites-available/rutapaq
sudo ln -sf /etc/nginx/sites-available/rutapaq /etc/nginx/sites-enabled/rutapaq
sudo rm -f /etc/nginx/sites-enabled/default
sudo systemctl daemon-reload
sudo systemctl enable --now rutapaq
sudo nginx -t && sudo systemctl reload nginx
echo "Listo: abrir http://<IP-pública-de-la-VM>/"
