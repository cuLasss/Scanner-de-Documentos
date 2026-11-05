<div align="center">
  <img src="./docs/icone.svg" width="120" alt="Ícone do Scan Limpo: uma folha dentro da moldura de escaneamento">

  <h1>Scan Limpo</h1>

  <p>
    <strong>Escaneador de documentos para Android: sem anúncios, sem conta e 100% offline.</strong>
  </p>

  <p>
    <img alt="Plataforma" src="https://img.shields.io/badge/plataforma-Android%208%2B-3DDC84">
    <img alt="Feito com" src="https://img.shields.io/badge/feito%20com-Kotlin%20%2B%20Compose-7F52FF">
    <img alt="Internet" src="https://img.shields.io/badge/internet-n%C3%A3o%20usa-2ea44f">
    <img alt="Licença" src="https://img.shields.io/badge/licen%C3%A7a-MIT-blue">
    <img alt="Autor" src="https://img.shields.io/badge/autor-Lucas%20Martin-green">
  </p>

  <h3>⬇️ <a href="https://github.com/cuLasss/Scanner-de-Documentos/releases/latest/download/ScanLimpo.apk">Baixar o APK</a></h3>
</div>

## Sobre

O **Scan Limpo** nasceu de um problema simples: escanear um documento no celular não deveria vir com uma rodada de anúncios a cada página.

O app transforma a câmera em scanner, lê o texto, dá nome ao documento e exporta para vários formatos. Os arquivos ficam só no aparelho, e tudo o que o app usa já vem dentro do APK: ele nem tem permissão para acessar a internet.

A interface é em português do Brasil, e o nome automático foi pensado para documentos brasileiros, como RG, CNH e boleto.

## O que faz

- **Escanear:** câmera própria com detecção de bordas ao vivo, foto automática ou manual, lanterna e ajuste das bordas depois de cada foto. Também importa fotos, PDFs e TIFFs, e recebe arquivos pelo "Compartilhar" de outros apps.
- **Nome automático:** o app lê a página e dá o nome sozinho ("Declaração de residência", "CNH", "Nota fiscal"). As regras rodam no aparelho, sem internet.
- **Editar:** filtros (Melhorar, Cinza, P&B) com nível de aplicação, brilho e contraste, recorte em perspectiva com lupa, giro animado, reordenar páginas arrastando, juntar frente e verso de RG/CNH em uma folha.
- **Marcações:** assinatura desenhada e reaproveitável, tarja para esconder dados, corretivo e marca-texto. Primeiro em rascunho (mover, redimensionar, excluir), depois "Aplicar", com "Desfazer" passo a passo mesmo depois de concluir.
- **Texto (OCR):** leitura offline em português, com correção manual, cópia e busca dentro de todos os documentos.
- **Organizar:** grade ajustável, seleção de vários documentos de uma vez e tema claro, escuro ou do sistema.
- **Exportar:** PDF, PDF pesquisável, Word, JPG, PNG, WebP, TIFF, TXT e ZIP. No PDF: senha AES-256, limite de tamanho do arquivo e tamanho do papel. Marca d'água nos formatos com imagem.
- **Entregar:** compartilhar, salvar em Downloads ou em outro lugar, abrir e imprimir.
- **Ler:** aparece no "Abrir com" de outros apps para PDF, imagens, TIFF, Word e TXT.

## Como instalar

Precisa de Android 8 ou mais novo.

1. No celular, baixe o [ScanLimpo.apk](https://github.com/cuLasss/Scanner-de-Documentos/releases/latest/download/ScanLimpo.apk) e abra o arquivo.
2. Se o celular pedir, permita instalar apps daquele navegador ou gerenciador de arquivos.
3. Se o Play Protect avisar que não conhece o app, escolha instalar mesmo assim. Se ele oferecer enviar o app para análise, pode recusar. Os nomes dos botões mudam de um celular para outro.

O app não se atualiza sozinho. Para atualizar, baixe a versão nova em [Releases](https://github.com/cuLasss/Scanner-de-Documentos/releases) e instale por cima: os documentos continuam lá.

## Como compilar

Precisa de JDK 17 e do Android SDK. Informe o caminho do SDK em `local.properties` (`sdk.dir=...`) ou na variável `ANDROID_HOME`. Os comandos abaixo são para Linux, macOS e Git Bash; no Prompt de Comando do Windows, troque `./gradlew` por `gradlew`.

**Para experimentar,** gere a versão de depuração. Ela instala separada da versão final, como `br.com.scanlimpo.debug`:

```sh
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Para gerar a versão final,** crie uma chave de assinatura e um arquivo `keystore.properties` na raiz do projeto. Os dois ficam fora do controle de versão.

```sh
mkdir keystore
keytool -genkeypair -keystore keystore/scanlimpo-release.jks -alias scanlimpo -keyalg RSA -keysize 4096 -validity 10000
```

```properties
storeFile=keystore/scanlimpo-release.jks
storePassword=sua-senha
keyAlias=scanlimpo
keyPassword=sua-senha
```

Depois rode `./gradlew :core:test :app:assembleRelease`. O APK sai em `app/build/outputs/apk/release/app-release.apk`. No Windows, `compilar.bat` faz o mesmo e copia o resultado para `dist\ScanLimpo.apk`. Sem a chave, o Gradle gera um APK sem assinatura, que não instala.

**Guarde a chave.** Sem ela, uma versão nova não instala por cima da antiga: seria preciso desinstalar, e isso apaga os documentos de quem usa o app. Ao lançar uma atualização, aumente `versionCode` em `app/build.gradle.kts`.

## Testes

`./gradlew :core:test` roda os testes do núcleo e não precisa de Android. As imagens que eles usam ficam em `core/src/test/fixtures` e são todas sintéticas: nomes e CPF também são fictícios.

Os testes que passam pelas telas ficam em `app/src/androidTest` e rodam no aparelho, em uma cópia separada (`br.com.scanlimpo.debug`), sem tocar nos documentos de verdade. Duas coisas para saber antes de rodar:

- O relógio desses testes é simulado. As animações pulam na tela enquanto eles rodam, e isso não é defeito do app. Pelo mesmo motivo, eles não enxergam tela piscando: o que é visual precisa ser conferido no APK final.
- Em celulares Xiaomi, libere antes a abertura de telas em segundo plano, ou o teste fica parado esperando: `adb shell appops set br.com.scanlimpo.debug 10021 allow`.

## Como está organizado

O código do app fica em `app/src/main/java/br/com/scanlimpo/`.

| Pasta | O que tem |
|---|---|
| `core/` (na raiz) | Kotlin puro, sem Android: leitura e gravação de PDF, TIFF e DOCX, filtros, detector de bordas, lógica da foto automática. Os testes ficam aqui. |
| `data` | Modelos e armazenamento (JSON + imagens na área privada do app). |
| `imaging` | Aplica as edições sobre a imagem original, que nunca é alterada. |
| `ocr`, `export`, `viewer` | OCR, exportação e o leitor do "Abrir com". |
| `ui` | Telas em Jetpack Compose. A câmera está em `ui/camera`. |

## Decisões que vale conhecer

- **100% offline de propósito.** Nada é baixado em tempo de execução, nem pelo Play Services. Por isso o scanner do Google (ML Kit Document Scanner) não é usado: ele baixa um módulo na primeira vez. O OCR é a versão embutida do ML Kit, com os modelos dentro do APK.
- **Sem internet de propósito.** O ML Kit traz `INTERNET` e `ACCESS_NETWORK_STATE` só para telemetria, e o `AndroidManifest.xml` remove as duas. A única permissão pedida é a de câmera. Para conferir, rode `aapt2 dump permissions` no APK. A outra que aparece, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, é interna e vem do AndroidX.
- **Arquivos de fora não são confiáveis.** Os leitores limitam tamanhos e tratam dados inválidos, e o app só aceita arquivos entregues por outro app (`content://`), nunca `file://` nem o próprio provedor.
- **Tarja apaga de verdade.** Ela é gravada nos pixels da imagem exportada, e o OCR roda de novo sobre a página já com a tarja, então o texto escondido também some da busca, do PDF pesquisável, do Word e do TXT. O arquivo original continua guardado na área privada do app, mas nunca é exportado.
- **Documentos não vão para backup.** Ficam só na área privada do app; desinstalar apaga tudo.

## Limites conhecidos

- **Aparelhos:** testado de ponta a ponta em um único celular (Xiaomi, Android 14). Em outros modelos, a câmera e a foto automática podem se comportar de outro jeito.
- **Detecção de bordas:** nas cenas sintéticas dos testes, acerta 11 de 12 na resolução ideal do detector (320 px). A que falha é o cartão de cantos arredondados, como RG e CNH, em que a borda sai um pouco fora do lugar. Dá para corrigir no ajuste das bordas depois da foto.
- **PDF com senha:** o leitor do Android só abre a partir do Android 15, e o app ainda não usa esse recurso.
- **Word:** leitura simplificada (texto, títulos, negrito, imagens e tabelas linha a linha), sem a formatação exata. Formato `.doc` antigo não é lido.
- **TIFF:** sem compressão, LZW, Deflate e PackBits. CCITT Grupo 4, comum em scanners de escritório, ainda não.

## Licença

O código deste repositório está sob a [licença MIT](LICENSE).

O reconhecimento de texto é a exceção entre as dependências: o ML Kit, do Google, e as bibliotecas do Play Services que ele traz são fechados e seguem os [termos do ML Kit](https://developers.google.com/ml-kit/terms). Eles não ficam neste repositório: o Gradle os baixa na hora de compilar, e eles vão dentro do APK. As outras bibliotecas são de código aberto, quase todas sob Apache 2.0.
