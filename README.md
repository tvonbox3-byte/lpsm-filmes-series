# LPSM Filmes & Séries

Aplicativo Android/Android TV separado do LPSM Player de TV ao vivo.

## Arquitetura inicial
- Filmes e séries consultados separadamente pela API Xtream do provedor autorizado.
- Não varre uma M3U gigante para descobrir o catálogo.
- Categorias são carregadas primeiro; os títulos só são buscados quando a categoria é aberta.
- Séries: Série -> Temporadas -> Episódios.
- Player Media3/ExoPlayer.
- Navegação por controle remoto e touchscreen.
- PIN adulto inicial: `0202`.

## Primeiro teste
Na primeira abertura, cole a URL M3U/Xtream no formato `get.php?...username=...&password=...` da sua fonte autorizada.

## GitHub Actions
Ao enviar o projeto para a branch `main`, a aba Actions gera o APK de teste automaticamente.
