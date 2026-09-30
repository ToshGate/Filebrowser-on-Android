# File Browser para Android

Cliente Android nativo para servidores [File Browser](https://github.com/filebrowser/filebrowser): navegar, enviar e descarregar ficheiros no teu servidor a partir do telemóvel.

> Esta app não é oficial nem está associada ao projecto File Browser.

## Funcionalidades

- **Navegação** por pastas com caminho em chips, puxar para actualizar e ordenação por nome, tamanho ou data.
- **Miniaturas** de imagens e ícones por tipo de ficheiro.
- **Uploads** de ficheiros e de pastas inteiras pelo protocolo TUS, em blocos de 10 MiB, com pausa, retoma e continuação depois de falhas de rede ou de fechar a app.
- **Downloads** de ficheiros (com retoma), de pastas (mantendo a estrutura) ou de pastas como ZIP.
- **Gestão**: criar pastas, mudar o nome, apagar.
- **Sessão persistente** opcional: palavra-passe cifrada com o Android Keystore e renovação automática do token.
- **Material 3**, com cores dinâmicas e ícone temático.

## Requisitos

- Android 8.0 (API 26) ou mais recente.
- Servidor File Browser acessível por HTTPS.

## Compilar

Requer o Android Studio (com o JDK 17 incluído) e a plataforma Android 36.

```
git clone <url-deste-repositório>
cd filebrowser-android
./gradlew assembleDebug        # Windows: gradlew assembleDebug
```

O APK fica em `app/build/outputs/apk/debug/`. O `local.properties` (caminho do Android SDK) é criado automaticamente no primeiro build.

O build debug instala-se como "File Browser (debug)" (`com.toshgate.filebrowser.debug`), ao lado da versão release.

## Estrutura

```
app/src/main/java/com/toshgate/filebrowser/
├── AppGraph.kt          dependências partilhadas entre a UI e os workers
├── data/                API, sessão, credenciais cifradas, re-login automático
├── upload/              cliente TUS, fila de uploads, worker, leitura de pastas
├── download/            cliente de downloads (/api/raw), fila e worker
├── transfer/            estado e persistência comuns a uploads e downloads
└── ui/                  ecrãs Compose (ui/components: peças reutilizáveis)
```

## Releases

As releases são publicadas automaticamente pelo GitHub Actions ao enviar uma tag `v*`. O processo completo (chave de assinatura, secrets, versões) está em [RELEASE.md](RELEASE.md). Histórico de alterações em [CHANGELOG.md](CHANGELOG.md).

## Privacidade

A app não recolhe dados. As credenciais ficam guardadas só no dispositivo e são enviadas apenas para o servidor indicado. Ver [PRIVACY.md](PRIVACY.md).

## Licença

Distribuído sob a licença MIT. Ver [LICENSE](LICENSE).