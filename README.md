# LPSM Filmes & Séries 1.2.0

Aplicativo Android/Android TV separado do LPSM de TV ao vivo.

## Nesta versão

- Fonte em formato **M3U** configurada pelo painel.
- O app não pede URL ao cliente.
- Ao abrir o app em um aparelho novo, o código/MAC aparece automaticamente em **Aguardando ativação** no painel.
- No painel, clique em **Ativar**, informe o nome e salve.
- Uma lista M3U principal pode ser usada por todos os aparelhos.
- Também é possível definir uma M3U específica para um aparelho.
- O backend organiza filmes, séries, temporadas e episódios a partir da M3U e mantém cache temporário para acelerar as consultas.
- O painel pode testar a M3U e mostrar quantidade de filmes, séries, episódios e categorias reconhecidas.
- PIN adulto: `0202`.
- Atualização automática do APK continua pelo GitHub Releases quando a assinatura fixa está configurada.

## Backend

URL padrão do app:

`https://lpsm-filmes-series-backend.onrender.com`

No Render, configure:

- Root Directory: `backend`
- Build Command: `npm install`
- Start Command: `npm start`
- `ADMIN_USER`
- `ADMIN_PASSWORD`
- `TOKEN_SECRET`

O plano gratuito pode ser usado.
