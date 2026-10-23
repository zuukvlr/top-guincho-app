# TOP Guincho — Recibo & Checklist (Android)

App Android (WebView + Kotlin) com emissão de recibos e checklist de vistoria, offline, com atualização automática pelo GitHub Releases.

- Telefone: (92) 99174-4383 · CNPJ: 22.678.354/0001-22
- Cores: vermelho `#E10600`, preto `#0A0A0A`, branco (logo TOP Guincho)

## Como publicar uma atualização
Faça `git push` na `main`. O GitHub Actions compila o APK assinado e cria a Release `v1.0.N`.
Quem tem o app instalado vê o aviso **"Atualização nova disponível"** → toca em **Atualizar** → o app baixa o APK e abre o instalador.

## Primeira instalação
Baixe `TopGuincho.apk` na aba **Releases** do repositório e instale no celular (permitir "fontes desconhecidas").

## Secrets necessários no repositório
`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. A mesma keystore deve ser sempre usada — sem isso o Android recusa a atualização.

## Logo
O logo no app é um desenho vetorial aproximado. Para usar o arquivo original, troque o `<symbol id="logo">` em `app/src/main/assets/www/index.html` por uma `<image>` e substitua os ícones em `app/src/main/res/drawable`.

## Dados na nuvem (Firebase)
Projeto Firebase: top-guincho-app. Recibos, checklists e clientes sincronizam via Firestore (com cache offline). Login por e-mail/senha. As regras estao em firestore.rules (colar em Firestore > Regras).
