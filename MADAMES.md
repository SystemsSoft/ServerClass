# Madames Formosas — serviço do catálogo

Subprojeto Gradle `madames/` deste repositório, com jar (`madames-0.0.1.jar`), processo, log e deploy (`./deploy-madames.sh`)
separados do servidor principal e da SecretárIA: um deploy de um não derruba os outros.

```
Site Flutter (madames_formosas) ⇄ https://api.effectiveenglishcourse.com/madames/ ⇄ nginx ⇄ serviço Madames :8082 ⇄ MySQL madames_formosas
```

Código em `madames/src/main/kotlin/`: `schemas/madamesFormosas` (tabelas `produtos` e `produto_fotos`, catálogo inicial),
`routes/madamesFormosas` (HTTP) e `madames/MadamesServer.kt` (partida: banco, CORS e `/madames/health`).
O banco `madames_formosas` e as tabelas são criados sozinhos na partida; com a tabela de produtos vazia, ele grava o catálogo inicial.

## API

| Método | Caminho | Acesso |
|---|---|---|
| GET | `/madames/health` | público — `ok` se o banco responde |
| GET | `/madames/produtos[?categoria=Brincos]` | público — só ativos, ordenados por código |
| GET | `/madames/produtos/{codigo}` | público |
| GET | `/madames/produtos/todos` | admin — inclui inativos |
| POST | `/madames/produtos` | admin |
| PUT | `/madames/produtos/{codigo}` | admin — substitui dados e fotos |
| DELETE | `/madames/produtos/{codigo}` | admin |

Admin = header `Authorization: Bearer <madames.adminToken>`. Produto:
`{"codigo":"AR185","nome":"...","preco":63.98,"categoria":"Brincos","fotos":["AR185_1.webp"],"ativo":true}`.
Em `fotos`, um nome simples é um asset do site (`assets/catalogo/`); uma URL `https://...` é usada como está.

## Configuração

Copie `madames-credentials.properties.example` para `madames-credentials.properties`: `db.host`, `db.user`, `db.password`
(obrigatórios, sem valor padrão) e `madames.adminToken`.

**Rodar local sem MySQL:**
```bash
./gradlew :madames:shadowJar
java '-Dmadames.db.url=jdbc:h2:mem:madames;MODE=MySQL;DB_CLOSE_DELAY=-1' -jar madames/build/libs/madames-0.0.1.jar
```

## Deploy

`./deploy-madames.sh` roda os testes, gera o jar, envia o jar + `madames-credentials.properties`, reinicia **só** o processo
da Madames (porta 8082, log em `~/madames.log`) e espera o `/madames/health` responder.

### Primeiro deploy

1. Crie `madames-credentials.properties` (acima).
2. `./deploy-madames.sh` — sobe o serviço na 8082.
3. No nginx da EC2, dentro do `server` que atende a API, encaminhe a Madames para a 8082, teste e recarregue:
   ```nginx
   location /madames/ {
       proxy_pass http://127.0.0.1:8082;
       proxy_set_header Host $host;
       proxy_set_header X-Real-IP $remote_addr;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       proxy_set_header X-Forwarded-Proto $scheme;
   }
   ```
   `sudo nginx -t && sudo systemctl reload nginx`, depois `curl https://api.effectiveenglishcourse.com/madames/health` → `ok`.
