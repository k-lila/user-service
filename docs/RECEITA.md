# Receita — como rodar o projeto

Há dois caminhos. Escolha um e siga os passos na ordem.

- **A. Local** — roda tudo na sua máquina, em HTTP. É o caminho para desenvolver e testar.
- **B. Deploy** — publica o sistema na internet por um Cloudflare Tunnel, com domínio próprio.

```
                    ┌─ A. Local    A1 segredos → A2 .env → A3 subir → A4 acessar → A5 parar
Pré-requisitos ─────┤
                    └─ B. Deploy   Preparação (uma vez):  B1 domínio → B2 túnel
                                   Subida:                B3 zerar → B4 segredos → B5 .env
                                                          → B6 subir → B7 verificar
```

Todos os comandos rodam a partir da **raiz do repositório**.

---

## Pré-requisitos

| Ferramenta                         | Versão    | Para quê                                  |
| ---------------------------------- | --------- | ----------------------------------------- |
| Docker + Docker Compose            | 24+       | Rodar o sistema (A e B)                   |
| `openssl`                          | —         | Gerar os segredos e a chave JWT (A e B)   |
| Domínio próprio + conta Cloudflare | —         | Só o deploy (B)                           |
| `cloudflared` e `dig`              | —         | Só o deploy (B)                           |
| Java + Maven                       | 21 · 3.9+ | Só para rodar os testes de back-end       |
| Node.js                            | 22        | Só para rodar os testes de front-end      |

---

## A. Ambiente local

### A1. Gerar os segredos

Cria a pasta `secrets/` com senhas de desenvolvimento e o par de chaves que assina os tokens.

```bash
infra/secrets/gen-secrets.sh
```

Basta rodar uma vez. Sem `secrets/`, o `docker compose up` falha.

### A2. Criar o `.env`

O `.env` guarda os nomes de usuário usados pelo compose. As senhas ficam em `secrets/`.

```bash
cp .env.example .env
```

Depois, abra o `.env` e ajuste **uma** linha, para casar com a senha de dev gerada em A1:

```bash
MONGO_USER=root
MONGO_PASSWORD=mongo-dev-secret     # o .env.example traz "changeme"
```

> ⚠️ Sem esse ajuste o sistema sobe, mas o monitor do Mongo (`mongodb-exporter`) fica fora do ar
> sem avisar.

### A3. Subir

```bash
docker compose up -d --build        # a primeira vez demora: compila os seis serviços
docker compose ps                   # espere todos ficarem "healthy"
docker compose logs -f              # acompanhar os logs (Ctrl+C para sair)
```

### A4. Acessar

| O quê                | Endereço                                    |
| -------------------- | ------------------------------------------- |
| **Aplicação (SPA)**  | http://localhost:5173                       |
| API (gateway)        | http://localhost:8081                       |
| Swagger UI           | http://localhost:8081/swagger-ui/index.html |
| Grafana 🔒           | http://localhost:3000                       |
| Prometheus 🔒        | http://localhost:9090                       |
| Zipkin 🔒            | http://localhost:9411                       |
| Eureka               | http://localhost:9091                       |

- O Swagger exige login: entre pela aplicação primeiro.
- Grafana: usuário `GRAFANA_ADMIN_USER` do `.env`, senha em `secrets/GRAFANA_ADMIN_PASSWORD`.
- 🔒 só responde nesta máquina: a porta fica presa ao `127.0.0.1`, de propósito.
- Os serviços internos também têm porta em dev (auth-server `8082`, user-service `8090`,
  notification `8095`, config `8888`), mas o acesso normal é sempre pelo gateway.

### A5. Parar

```bash
docker compose down         # para tudo e MANTÉM os dados
docker compose down -v      # para tudo e APAGA os dados (bancos, sessões)
```

---

## Opcional: escalar a stack

O `up` padrão sobe o **piso mínimo**: 19 containers, um nó de cada banco. Para crescer:

```bash
docker compose --profile ha up -d                     # 26 containers: 3 Mongo, 3 Redis, 2 Eureka

docker compose -f docker-compose.yml up -d \
  --scale gateway=2 --scale user-service=2            # mais réplicas de aplicação
```

- **`--scale` exige o `-f docker-compose.yml`**: sem ele entram as portas de dev, e a 2ª réplica
  falha por porta ocupada.
- **Para derrubar, repita o profile**: `docker compose --profile ha down -v --remove-orphans`.
  Sem o profile, os nós extras ficam órfãos.
- **Voltar de 3 para 1 nó Mongo é manual** (`rs.remove` antes de desligar o profile).
- **O piso mínimo não tem failover**: um nó Mongo ou Redis que cair derruba o serviço.

Detalhes em [ADR-024](adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md).

---

## B. Deploy via Cloudflare Tunnel

O túnel liga o seu domínio ao sistema rodando na sua máquina, sem abrir porta no roteador:

```
browser → Cloudflare (HTTPS) → túnel → nginx do SPA → gateway → serviços
```

Nas variáveis abaixo, troque `app.exemplo.com` pelo seu domínio.

### Parte 1 — Preparação (uma vez)

#### B1. Delegar o domínio à Cloudflare

No painel do registrador do domínio, troque os servidores DNS pelos dois nameservers que a
Cloudflare indicar. **Não** ative DNSSEC (sem registro DS). A propagação pode levar horas.

Confira antes de seguir:

```bash
dig NS app.exemplo.com @1.1.1.1 +short    # deve mostrar os nameservers da Cloudflare
dig DS app.exemplo.com @1.1.1.1 +short    # deve sair VAZIO
```

> ⚠️ Use sempre `@1.1.1.1`. O DNS do seu sistema pode guardar a resposta antiga por até uma hora
> (`sudo resolvectl flush-caches` limpa).

#### B2. Criar o túnel

Instale o `cloudflared` no seu computador. Ele só é usado aqui, para criar o túnel; depois quem
roda o túnel é um container.

```bash
# Debian/Ubuntu (macOS: brew install cloudflared)
curl -LO https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
sudo dpkg -i cloudflared-linux-amd64.deb

cloudflared tunnel login                                  # abre o browser: autorize o domínio
cloudflared tunnel create user-service                    # ANOTE o UUID impresso
cloudflared tunnel route dns user-service app.exemplo.com # aponta o domínio para o túnel
```

O resultado é o arquivo `~/.cloudflared/<UUID>.json`, a credencial do túnel, usada em B4.
Esqueceu o UUID? `cloudflared tunnel list` mostra.

<details>
<summary>Prefere não instalar nada? Criar o túnel via Docker</summary>

A imagem do cloudflared só consegue gravar em `~/.cloudflared` rodando como root. Por isso, no
fim, devolva a posse dos arquivos ao seu usuário:

```bash
mkdir -p ~/.cloudflared
CFD="docker run --rm -it --user 0:0 -e HOME=/home/nonroot \
  -v $HOME/.cloudflared:/home/nonroot/.cloudflared cloudflare/cloudflared:latest"

eval $CFD tunnel login
eval $CFD tunnel create user-service
eval $CFD tunnel route dns user-service app.exemplo.com

sudo chown -R "$(id -u):$(id -g)" ~/.cloudflared
```

</details>

### Parte 2 — Subida

Todo comando daqui em diante precisa dos **dois arquivos** do compose. Crie um atalho:

```bash
alias dcd='docker compose -f docker-compose.yml -f docker-compose.deploy.yml'
```

#### B3. Zerar o estado anterior

Apaga volumes de execuções anteriores, porque as senhas novas só valem em banco criado do zero.

```bash
dcd down -v --remove-orphans
```

> ⚠️ Só faça isso enquanto não houver dados reais: apaga todos os usuários.

#### B4. Gerar segredos fortes

Mesmo script de A1, mas com senhas aleatórias no lugar dos defaults de dev, que são públicos.

```bash
CONFIG_SERVER_PASSWORD=$(openssl rand -hex 32) \
REDIS_PASSWORD=$(openssl rand -hex 32) \
OAUTH_CLIENT_SECRET=$(openssl rand -hex 32) \
INTERNAL_API_TOKEN=$(openssl rand -hex 32) \
POSTGRES_PASSWORD=$(openssl rand -hex 32) \
MONGO_PASSWORD=$(openssl rand -hex 32) \
GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 32) \
CLOUDFLARE_TUNNEL_CREDENTIALS=~/.cloudflared/<UUID>.json \
  infra/secrets/gen-secrets.sh
```

> ⚠️ O script reescreve **todos** os segredos a cada execução. Se você já tiver SMTP real
> configurado, exporte também as sete variáveis `SMTP_*`, senão elas voltam aos valores de dev.

#### B5. Preencher o `.env`

Acrescente ao `.env` (criado em A2) as três variáveis do deploy:

```bash
PUBLIC_ORIGIN=https://app.exemplo.com    # endereço público, sem barra no final
PUBLIC_HOST=app.exemplo.com              # o mesmo, sem https://
TUNNEL_ID=<UUID do passo B2>
```

E copie para o `.env` a senha do Mongo gerada em B4:

```bash
sed -i "s/^MONGO_PASSWORD=.*/MONGO_PASSWORD=$(cat secrets/MONGO_PASSWORD)/" .env
```

#### B6. Subir

```bash
dcd up -d --build
```

Se faltar alguma variável de B5, ou se `PUBLIC_ORIGIN` e `PUBLIC_HOST` não baterem, a subida
aborta com uma mensagem dizendo o quê corrigir.

#### B7. Verificar

```bash
export $(grep '^PUBLIC_ORIGIN=' .env)

dcd ps                                                   # todos "healthy"
dcd logs cloudflared | grep -i "Registered tunnel"       # 4 conexões registradas
curl -I $PUBLIC_ORIGIN                                   # 200, com headers CSP e HSTS
curl -I $PUBLIC_ORIGIN/swagger-ui/index.html             # 302: exige login
curl -I $PUBLIC_ORIGIN/v3/api-docs                       # 401: exige login
curl $PUBLIC_ORIGIN/internal/users/email/x@y.z           # HTML do SPA, nunca JSON
```

Depois, no browser, faça o ciclo completo: **cadastro → login → dashboard → logout**.

### Depois de subir

- **Observabilidade** (Grafana, Prometheus, Zipkin) **não** fica pública: só nesta máquina, nos
  mesmos endereços de A4.
- **Não repita o B3 depois de haver dados reais.** Para trocar de domínio a partir daí, é preciso
  atualizar o cadastro do cliente OAuth no Postgres.
- **Não abra o cadastro para terceiros ainda.** Sem um SMTP real o e-mail de verificação não sai e
  a conta trava após 24h ([ADR-015](adr/ADR-015-verificacao-email-cadastro.md)). Também faltam as
  páginas de termos e privacidade ([ADR-012](adr/ADR-012-consentimento-lgpd-cadastro.md)).

---

## Problemas comuns

| Sintoma                                                   | Causa                                             | Solução                                                          |
| --------------------------------------------------------- | ------------------------------------------------- | ---------------------------------------------------------------- |
| `up` falha citando um arquivo em `secrets/`               | Faltou gerar os segredos                          | Rodar A1 (ou B4)                                                 |
| `required variable PUBLIC_HOST is missing`                | Variável de deploy ausente no `.env`              | Completar o B5                                                   |
| `[ERRO] Incoerencia de dominio`                           | `PUBLIC_HOST` ≠ `PUBLIC_ORIGIN` sem `https://`    | Igualar os dois no `.env`                                        |
| `no such service: cloudflared`                            | Comando sem os dois `-f`                          | Usar o alias `dcd`                                               |
| Mongo ou Postgres recusa a senha                          | Volume criado com senhas antigas                  | `down -v` e subir de novo (B3 → B6)                              |
| Painel do Mongo vazio no Grafana                          | `MONGO_PASSWORD` do `.env` ≠ `secrets/`           | A2 (dev) ou o `sed` do B5 (deploy)                               |
| Domínio responde `SERVFAIL`                               | DNSSEC ligado no registrador                      | Remover o registro DS                                            |
| `dig` mostra os nameservers antigos                       | Cache do DNS local                                | Usar `@1.1.1.1` ou `sudo resolvectl flush-caches`                |
| `--scale`: `port is already allocated`                    | Portas de dev entraram no merge                   | Usar `-f docker-compose.yml`                                     |
| cloudflared via Docker: `permission denied`               | Container sem ser root                            | Usar `--user 0:0`, como no bloco de B2                            |
