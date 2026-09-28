# LPSM Filmes & Séries 1.1.0

Aplicativo Android/Android TV separado do LPSM de TV ao vivo.

## O que mudou

- O cliente não digita mais a URL Xtream no aparelho.
- O app mostra um código/MAC e aguarda ativação no painel.
- Painel web separado para cadastrar aparelho, ativar/pausar, definir validade e fonte Xtream autorizada.
- Filmes e séries continuam consultados separadamente pela API Xtream.
- Presença ONLINE/OFFLINE no painel.
- Atualização dentro do próprio aplicativo por GitHub Releases quando o APK estiver com assinatura estável.
- PIN adulto 0202.

## Painel

O backend está em `backend/` e pode ser publicado no Render com o `render.yaml` da raiz.

URL esperada por padrão:

`https://lpsm-filmes-series-backend.onrender.com`

Se o Render gerar outra URL, altere apenas o arquivo `backend-url.txt` na raiz do GitHub. O app consulta esse arquivo remotamente e não precisa de novo APK só para trocar o endereço do painel.

## Persistência

O painel funciona localmente sem Supabase, mas serviços gratuitos podem perder arquivo local em reinicializações/deploys.
Para persistência, use as mesmas variáveis `SUPABASE_URL` e `SUPABASE_SECRET_KEY` do backend principal. O painel usa a linha `id=vod` na tabela `lpsm_state`, separada do app de TV.

## Atualização automática

A Action sempre gera um APK para teste.
Quando os 4 Secrets `LPSM_VOD_*` estiverem configurados, também gera APK Release assinado, publica em Releases e cria `update.json`.
O aplicativo verifica `update.json` e oferece o botão ATUALIZAR sem precisar baixar manualmente pelo navegador.
