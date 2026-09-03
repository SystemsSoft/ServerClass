#!/bin/bash
SERVER_IP="98.92.129.159"
SERVER_USER="ec2-user"
KEY_FILE="ssh-key.pem"
JAR_FILE="build/libs/server-0.0.1.jar"
REMOTE_PATH="/home/$SERVER_USER/server-0.0.1.jar"

# Garante permissão correta na chave
chmod 400 $KEY_FILE

echo "--- [1/3] Building JAR ---"
./gradlew shadowJar

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo "--- [2/3] Uploading to Server ($SERVER_IP) ---"
scp -i $KEY_FILE $JAR_FILE $SERVER_USER@$SERVER_IP:$REMOTE_PATH

echo "--- [3/3] Restarting Service ---"
ssh -i $KEY_FILE $SERVER_USER@$SERVER_IP << 'EOF'
  # Mata o(s) processo(s) antigo(s), esperando de verdade eles saírem (até 15s) antes de
  # seguir. Isso evita processos "zumbis": se o kill normal (SIGTERM) não bastar porque o
  # processo travou no shutdown (ex: preso numa conexão de banco lenta/instável), força
  # com kill -9. Sem isso, o processo novo sobe na porta liberada e o antigo fica rodando
  # pra sempre em segundo plano, consumindo memória a cada deploy.
  OLD_PIDS=$(pgrep -f server-0.0.1.jar || true)
  if [ -n "$OLD_PIDS" ]; then
    echo "Encerrando processo(s) antigo(s): $OLD_PIDS"
    kill $OLD_PIDS 2>/dev/null || true
    for i in $(seq 1 15); do
      sleep 1
      pgrep -f server-0.0.1.jar > /dev/null || break
    done
    STILL_ALIVE=$(pgrep -f server-0.0.1.jar || true)
    if [ -n "$STILL_ALIVE" ]; then
      echo "Processo(s) não encerraram a tempo, forçando (kill -9): $STILL_ALIVE"
      kill -9 $STILL_ALIVE 2>/dev/null || true
      sleep 1
    fi
  fi
  # -port=8080 é obrigatório: o EngineMain não le o application.yaml de dentro do fat jar,
  # so funciona sem essa flag quando rodado via ./gradlew run (classpath solto, nao jar).
  # setsid + stdin redirecionado: nohup sozinho (com disown) nao sobrevive de forma confiavel
  # ao encerramento da sessao SSH nao interativa usada aqui.
  cd ~ && setsid nohup java -jar server-0.0.1.jar -port=8080 < /dev/null > server.log 2>&1 &
  echo "Processo iniciado em background."

  echo "--- Verificando saude do servico ---"
  sleep 20
  if pgrep -f server-0.0.1.jar > /dev/null && curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8080/ | grep -q "^HTTP 200$"; then
    echo "OK: servico respondendo em http://localhost:8080/"
  else
    echo "FALHA: servico nao respondeu apos o restart. Ultimas linhas do log:"
    tail -n 40 server.log
    exit 1
  fi
EOF

if [ $? -ne 0 ]; then
    echo "Deploy FALHOU na verificacao de saude — confira o log acima e o server.log remoto antes de considerar concluido."
    exit 1
fi

echo "Deploy finished!"
