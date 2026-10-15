#!/bin/bash
# Deploy do serviço da Madames Formosas (catálogo): processo próprio na mesma EC2 (porta 8082, só localhost; o nginx encaminha
# /madames para ele). NÃO reinicia o servidor principal nem a SecretárIA — os outros produtos seguem no ar.
# Primeira vez? Veja "Primeiro deploy" no MADAMES.md (credenciais do banco e configuração do nginx).
SERVER_IP="98.92.129.159"
SERVER_USER="ec2-user"
KEY_FILE="ssh-key.pem"
JAR_FILE="madames/build/libs/madames-0.0.1.jar"
REMOTE_PATH="/home/$SERVER_USER/madames-0.0.1.jar"

# Memória da JVM: a máquina também roda o servidor principal e a SecretárIA. Ajuste com MADAMES_JAVA_OPTS se precisar.
JAVA_OPTS="${MADAMES_JAVA_OPTS:--Xms32m -Xmx192m}"

# Só o que a Madames usa (gitignored, opcional: só envia se existir aqui).
# madames-credentials.properties precisa ter db.host, db.user e db.password além de madames.adminToken
CREDENTIAL_FILES=("madames-credentials.properties")

chmod 400 $KEY_FILE

echo "--- [1/5] Testes da Madames ---"
if ! ./gradlew :madames:test; then
    echo "Testes falharam! Nada foi publicado."
    exit 1
fi

echo "--- [2/5] Building JAR ---"
if ! ./gradlew :madames:shadowJar; then
    echo "Build failed!"
    exit 1
fi

echo "--- [3/5] Uploading JAR to Server ($SERVER_IP) ---"
if ! scp -i $KEY_FILE $JAR_FILE $SERVER_USER@$SERVER_IP:$REMOTE_PATH; then
    echo "Falha ao enviar o JAR."
    exit 1
fi

echo "--- [4/5] Uploading credential files ---"
for cred_file in "${CREDENTIAL_FILES[@]}"; do
  if [ -f "$cred_file" ]; then
    echo "Enviando $cred_file..."
    scp -i $KEY_FILE "$cred_file" $SERVER_USER@$SERVER_IP:/home/$SERVER_USER/"$cred_file"
  else
    echo "$cred_file não existe localmente, pulando (servidor mantém o que já tiver, ou usa variáveis de ambiente)."
  fi
done

echo "--- [5/5] Restarting Madames ---"
ssh -i $KEY_FILE $SERVER_USER@$SERVER_IP "JAVA_OPTS='$JAVA_OPTS' bash -s" << 'EOF'
  # Só o processo da Madames (o nome do jar não casa com o do servidor principal, server-0.0.1.jar).
  OLD_PIDS=$(pgrep -f madames-0.0.1.jar || true)
  if [ -n "$OLD_PIDS" ]; then
    echo "Encerrando processo(s) antigo(s): $OLD_PIDS"
    kill $OLD_PIDS 2>/dev/null || true
    for i in $(seq 1 15); do
      sleep 1
      pgrep -f madames-0.0.1.jar > /dev/null || break
    done
    STILL_ALIVE=$(pgrep -f madames-0.0.1.jar || true)
    if [ -n "$STILL_ALIVE" ]; then
      echo "Processo(s) não encerraram a tempo, forçando (kill -9): $STILL_ALIVE"
      kill -9 $STILL_ALIVE 2>/dev/null || true
      sleep 1
    fi
  fi
  # Roda em ~ para achar os .properties enviados acima. setsid + stdin redirecionado: sobrevive ao fim da sessão SSH.
  cd ~ && setsid nohup java $JAVA_OPTS -jar madames-0.0.1.jar < /dev/null > madames.log 2>&1 &
  echo "Processo iniciado em background ($JAVA_OPTS)."

  echo "--- Verificando saude do servico ---"
  for i in $(seq 1 30); do
    sleep 2
    if curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8082/madames/health | grep -q "^200$"; then
      echo "OK: Madames respondendo em http://127.0.0.1:8082 (banco acessível)."
      exit 0
    fi
  done
  echo "FALHA: Madames não respondeu em 60 s. Ultimas linhas de ~/madames.log:"
  tail -n 40 madames.log
  exit 1
EOF

if [ $? -ne 0 ]; then
    echo "Deploy da Madames FALHOU na verificacao de saude — confira o log acima e ~/madames.log no servidor."
    exit 1
fi

echo "Deploy da Madames finished!"
