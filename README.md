# File Browser Android

Cliente Android nativo para [File Browser](https://github.com/filebrowser/filebrowser), usando Kotlin + Jetpack Compose + Material 3.

## Estado

- Login via `/api/login` (token `X-Auth`, renovado automaticamente via `/api/renew`)
- Navegação por pastas via `/api/resources` (botão "voltar" do sistema incluído)
- Criar pasta (`POST /api/resources/<pasta>/`) e renomear (`PATCH ...?action=rename`)
- Apagar com confirmação
- Upload pelo Storage Access Framework: vários ficheiros ou uma pasta inteira
  (subpastas e pastas vazias incluídas)
- Uploads TUS (`/api/tus`) em chunks de 10 MiB, com progresso, pausa, retoma
  (mesmo depois de fechar a app ou perder a rede) e resolução de conflitos
- Download de ficheiros (com retoma por HTTP Range) e de pastas, recriadas com a
  mesma estrutura ou como ZIP gerado pelo servidor (`/api/raw`)
- Painel de transferências com uploads e downloads; ficheiros descarregados abrem-se
  com um toque
- Fila única no WorkManager (2 uploads em paralelo) com notificação de progresso;
  uploads de uma pasta aparecem agrupados e podem ser pausados/cancelados em bloco
- Interface Material 3: barra de topo que recolhe, breadcrumbs, pull-to-refresh,
  miniaturas de imagens, ícones por tipo de ficheiro, ordenação e cores dinâmicas (Android 12+)

## Abrir

1. Abrir a pasta no Android Studio (Ladybug ou mais recente, JDK 17+).
2. Fazer Sync Gradle. Se quiseres `./gradlew` na linha de comandos, corre `gradle wrapper` uma vez.
3. Executar num Android 8.0+.

## Estrutura

```
AppGraph.kt              dependências partilhadas (UI + worker)
data/Api.kt              endpoints Retrofit + modelos JSON
data/Repository.kt       sessão, autenticação, erros
data/RemotePath.kt       normalização e codificação de caminhos
upload/TusClient.kt      protocolo TUS (POST / HEAD / PATCH / DELETE)
upload/FolderScanner.kt  percorre uma árvore SAF
upload/UploadStore.kt    lista de uploads persistida
upload/UploadManager.kt  fila, pausa, retoma, cancelamento
upload/UploadWorker.kt   worker que consome a fila + notificação
download/                cliente /api/raw, fila e worker de downloads
transfer/                estado e persistência comuns a uploads e downloads
ui/                      ecrãs Compose (ui/components: peças reutilizáveis)
```

## Próxima fase

- Preview de imagem/vídeo/PDF
- Pesquisa
- Multi-select, mover/copiar
- Share sheet ("Partilhar para File Browser")
- Gestão de múltiplos servidores
- Biometria / token em armazenamento cifrado
- Testes instrumentados e unitários

## Compatibilidade

Alvo inicial: File Browser v2.63.23.
