#!/bin/bash
SERVER_IP="3.236.188.14"
SERVER_USER="ec2-user"
KEY_FILE="ssh-key.pem"
JAR_FILE="build/libs/server-0.0.1.jar"
REMOTE_PATH="/home/$SERVER_USER/server-0.0.1.jar"

# Arquivos de credenciais locais (gitignored — ver loadLocalSecrets() em
# Application.kt) que precisam ir junto a cada deploy. Sem isso, trocar uma
# chave aqui local e rodar ./deploy.sh não tinha efeito nenhum: só o JAR
# subia, o servidor continuava com o properties antigo que já estava lá.
# Cada um é opcional — só envia os que existirem neste diretório.
CREDENTIAL_FILES=("aws-credentials.properties" "gemini-credentials.properties" "stripe-credentials.properties" "sentinela-email.properties" "firebase-service-account.json")

# A SecretárIA virou serviço próprio (./deploy-secretaria.sh) e NÃO sobe mais neste servidor. Publicar este servidor
# antes de o nginx encaminhar /secretaria para o serviço novo tira a SecretárIA do ar. Só segue se o /secretaria/health
# público responder exatamente "ok" — esse endereço só existe no serviço novo (o código HTTP não basta: este servidor
# responde 200 com a página do site para qualquer endereço desconhecido). Para pular: SKIP_SECRETARIA_CHECK=1 ./deploy.sh
PUBLIC_API="https://api.effectiveenglishcourse.com"
if [ "${SKIP_SECRETARIA_CHECK:-0}" != "1" ]; then
  SECRETARIA_HEALTH=$(curl -s -m 15 "$PUBLIC_API/secretaria/health")
  if [ "$SECRETARIA_HEALTH" != "ok" ]; then
    echo "ABORTADO: $PUBLIC_API/secretaria/health não respondeu 'ok' (o serviço da SecretárIA não está atendendo por lá)."
    echo "Primeiro publique a SecretárIA (./deploy-secretaria.sh) e aponte o nginx para ela (SECRETARIA.md, 'Serviço próprio')."
    echo "Para publicar mesmo assim: SKIP_SECRETARIA_CHECK=1 ./deploy.sh"
    exit 1
  fi
fi

# Garante permissão correta na chave
chmod 400 $KEY_FILE

echo "--- [1/4] Building JAR ---"
# ':shadowJar' = só o jar deste servidor (sem o ':', o Gradle também montaria o da SecretárIA)
./gradlew :shadowJar

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo "--- [2/4] Uploading JAR to Server ($SERVER_IP) ---"
scp -i $KEY_FILE $JAR_FILE $SERVER_USER@$SERVER_IP:$REMOTE_PATH

echo "--- [3/4] Uploading credential files ---"
for cred_file in "${CREDENTIAL_FILES[@]}"; do
  if [ -f "$cred_file" ]; then
    echo "Enviando $cred_file..."
    scp -i $KEY_FILE "$cred_file" $SERVER_USER@$SERVER_IP:/home/$SERVER_USER/"$cred_file"
  else
    echo "$cred_file não existe localmente, pulando (servidor mantém o que já tiver, ou usa variáveis de ambiente)."
  fi
done

echo "--- [4/4] Restarting Service ---"
ssh -i $KEY_FILE $SERVER_USER@$SERVER_IP << 'EOF'
  # O processo roda como serviço do systemd (/etc/systemd/system/server.service, Restart=always, sobe no boot).
  # NÃO usar kill + nohup aqui: o systemd ressuscitaria o processo morto e ficariam dois disputando a porta 8080.
  # Log: ~/server.log (o unit faz append nele).
  sudo systemctl restart server

  echo "--- Verificando saude do servico ---"
  for i in $(seq 1 30); do
    sleep 2
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ | grep -q "^200$"; then
      echo "OK: servico respondendo em http://localhost:8080/"
      exit 0
    fi
  done
  echo "FALHA: servico nao respondeu em 60 s apos o restart. Ultimas linhas do log:"
  systemctl status server --no-pager | head -n 5
  tail -n 40 server.log
  exit 1
EOF

if [ $? -ne 0 ]; then
    echo "Deploy FALHOU na verificacao de saude — confira o log acima e o server.log remoto antes de considerar concluido."
    exit 1
fi

echo "Deploy finished!"
