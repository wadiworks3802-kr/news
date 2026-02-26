#!/usr/bin/env bash
# Rocky Linux 서버에서 GND systemd 서비스 파일을 설치/갱신한다.
#
# 작성자 : 안태욱
# 현재날짜 : 2026년 02월 20일

set -euo pipefail

PROJECT_DIR="${1:-/home/was/gnd-news}"
SERVER_HOST="${2:-192.168.30.39}"
SERVICE_DIR="/etc/systemd/system"
H2_TCP_PORT="${3:-9092}"
SPRING_PROFILE="${4:-ops}"

cat <<EOF | sudo tee "${SERVICE_DIR}/gnd-h2.service" >/dev/null
[Unit]
Description=GND H2 TCP Server
After=network.target

[Service]
Type=simple
User=was
WorkingDirectory=${PROJECT_DIR}
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ExecStart=/usr/bin/bash ${PROJECT_DIR}/scripts/linux/run-h2-tcp-server.sh ${PROJECT_DIR} ${H2_TCP_PORT} ${PROJECT_DIR}
SuccessExitStatus=143
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

cat <<EOF | sudo tee "${SERVICE_DIR}/gnd-api.service" >/dev/null
[Unit]
Description=GND API Service
After=network.target gnd-h2.service
Requires=gnd-h2.service

[Service]
Type=simple
User=was
WorkingDirectory=${PROJECT_DIR}
Environment=SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk
Environment=APP_SECURITY_CORS_ALLOWLIST=http://localhost:8080,http://localhost:8081,http://127.0.0.1:8081,http://${SERVER_HOST}:8081,http://${SERVER_HOST}:8080
Environment=SPRING_DATASOURCE_URL=jdbc:h2:tcp://127.0.0.1:${H2_TCP_PORT}//${PROJECT_DIR}/.data/gnd;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH
Environment=SPRING_DATASOURCE_USERNAME=sa
Environment=SPRING_DATASOURCE_PASSWORD=
Environment=APP_MARKET_PROVIDER_ACTIVE=kiwoom
Environment=APP_MARKET_PROVIDER_ALLOW_MOCK=false
Environment=APP_MARKET_PROVIDER_FALLBACK_TO_MOCK_ON_FAILURE=false
Environment=APP_SEED_ENABLED=false
Environment=APP_NEWS_SOURCE_BOOTSTRAP_ENABLED=true
Environment=KIWOOM_APPKEY_FILE=${PROJECT_DIR}/appkey.txt
Environment=KIWOOM_SECRETKEY_FILE=${PROJECT_DIR}/secretkey.txt
ExecStart=/usr/bin/java -jar ${PROJECT_DIR}/api/build/libs/api-0.1.0-SNAPSHOT.jar --server.port=8080
SuccessExitStatus=143
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

cat <<'EOF' | sudo tee "${SERVICE_DIR}/gnd-web.service" >/dev/null
[Unit]
Description=GND WEB Service
After=network.target

[Service]
Type=simple
User=was
WorkingDirectory=/home/was/gnd-news
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ExecStart=/usr/bin/java -jar /home/was/gnd-news/web/build/libs/web-0.1.0-SNAPSHOT.jar --server.port=8081
SuccessExitStatus=143
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

cat <<EOF | sudo tee "${SERVICE_DIR}/gnd-batch.service" >/dev/null
[Unit]
Description=GND BATCH Service
After=network.target gnd-h2.service
Requires=gnd-h2.service

[Service]
Type=simple
User=was
WorkingDirectory=${PROJECT_DIR}
Environment=SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk
Environment=SPRING_DATASOURCE_URL=jdbc:h2:tcp://127.0.0.1:${H2_TCP_PORT}//${PROJECT_DIR}/.data/gnd;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH
Environment=SPRING_DATASOURCE_USERNAME=sa
Environment=SPRING_DATASOURCE_PASSWORD=
Environment=APP_MARKET_PROVIDER_ACTIVE=kiwoom
Environment=APP_MARKET_PROVIDER_ALLOW_MOCK=false
Environment=APP_MARKET_PROVIDER_FALLBACK_TO_MOCK_ON_FAILURE=false
Environment=APP_NEWS_SOURCE_BOOTSTRAP_ENABLED=true
Environment=KIWOOM_APPKEY_FILE=${PROJECT_DIR}/appkey.txt
Environment=KIWOOM_SECRETKEY_FILE=${PROJECT_DIR}/secretkey.txt
ExecStart=/usr/bin/java -jar ${PROJECT_DIR}/batch/build/libs/batch-0.1.0-SNAPSHOT.jar
SuccessExitStatus=143
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

sudo chmod +x "${PROJECT_DIR}/scripts/linux/run-h2-tcp-server.sh"
sudo systemctl daemon-reload
sudo systemctl enable gnd-h2 gnd-api gnd-web gnd-batch
sudo systemctl stop gnd-batch gnd-api || true
sudo systemctl restart gnd-h2
sudo systemctl restart gnd-api gnd-web gnd-batch
sudo systemctl --no-pager --full status gnd-h2 gnd-api gnd-web gnd-batch
