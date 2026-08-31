@echo off
set SERVER_IP=54.207.64.102
set SERVER_USER=ec2-user
set KEY_FILE=ssh-key.pem
set JAR_FILE=build\libs\server-0.0.1.jar
set REMOTE_PATH=/home/%SERVER_USER%/server-0.0.1.jar

echo [1/3] Gerando Fat JAR (shadowJar)...
call gradlew.bat shadowJar

if %ERRORLEVEL% NEQ 0 (
    echo Erro ao gerar o JAR. Abortando.
    pause
    exit /b %ERRORLEVEL%
)

echo [2/3] Enviando arquivo para o servidor %SERVER_IP%...
scp -i %KEY_FILE% %JAR_FILE% %SERVER_USER%@%SERVER_IP%:%REMOTE_PATH%

if %ERRORLEVEL% NEQ 0 (
    echo Erro no upload (SCP). Verifique a chave e o IP.
    pause
    exit /b %ERRORLEVEL%
)

echo [3/3] Reiniciando o servidor via SSH...
REM -port=8080 e obrigatorio: o EngineMain nao le o application.yaml de dentro do fat jar.
ssh -i %KEY_FILE% %SERVER_USER%@%SERVER_IP% "pgrep -f server-0.0.1.jar | xargs -r kill; sleep 2; nohup java -jar %REMOTE_PATH% -port=8080 > server.log 2>&1 & disown; sleep 20; pgrep -f server-0.0.1.jar > /dev/null && curl -s -o /dev/null -w 'HTTP %%{http_code}\n' http://localhost:8080/ | grep -q '^HTTP 200$' && echo OK: servico respondendo || (echo FALHA: confira server.log remoto; tail -n 40 server.log)"

echo.
echo Deploy concluido — confira a linha "OK" ou "FALHA" acima antes de considerar terminado.
echo O log pode ser acompanhado no servidor com: tail -f server.log
pause
