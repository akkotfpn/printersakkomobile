# 🖨️ Akko's Print

App Android simples, leve e **sem anúncios** para imprimir em mini impressoras térmicas Bluetooth de 58 mm — feito para a **"Mobile Printer" JingPu** (linha PT-210 / PT-260) e compatível com a maioria das impressoras chinesas que falam **ESC/POS**.

Os apps oficiais dessas impressoras costumam ser lentos, cheios de propaganda e pedem permissões demais. O Akko's Print faz só o que importa: cupom, recibo, foto e PDF.

> Um projeto **Akko's Corporation**.

---

## ✨ Funcionalidades

### 🧾 Modelos prontos
| Modelo | O que tem |
|---|---|
| **Nota de venda** | Cliente, itens (qtd × valor), desconto, total e forma de pagamento |
| **Recibo** | Valor em destaque, **valor por extenso** automático, cidade/data e linha de assinatura |
| **Orçamento** | Itens, total, validade em dias e prazo de entrega |
| **Ordem de serviço** | Equipamento, defeito relatado, serviço realizado, peças, garantia e assinatura do cliente |
| **Cobrança Pix** | QR Code do Pix com valor, chave e descrição |
| **QR Code** | Link, texto ou Wi-Fi em QR, com legenda |
| **Texto livre** | Qualquer texto, com título e tamanho de letra ajustável |

- Cabeçalho da empresa (nome, CNPJ/CPF, endereço, telefone) e mensagem de rodapé configuráveis uma única vez.
- **Numeração automática** dos documentos (Nº 000001, 000002…), editável.
- Impressão de **1, 2 ou 3 vias** (via do cliente + via da loja).

### 💠 Pix e QR Code
- Cadastre sua **chave Pix** uma vez e a nota de venda, o recibo e o orçamento saem com **QR Code do Pix já com o valor** (BR Code padrão do Banco Central — o cliente aponta a câmera do app do banco e paga).
- Modelo **Cobrança Pix**: QR grande com o valor, pra cobrar na hora.
- Modelo **QR Code**: link, texto ou **Wi-Fi** (nome da rede + senha → o celular conecta lendo o QR).
- **Logo** da empresa no topo dos cupons (convertido automaticamente pra preto e branco).

### 🖼️ Fotos e PDFs
- Escolha uma **foto** da galeria ou um **PDF** do celular e imprima.
- Modo **Foto (pontilhado)**: converte tons de cinza em pontinhos (Floyd–Steinberg), deixando fotos reconhecíveis no papel térmico.
- Modo **Texto / código**: preto e branco puro, ideal para comprovantes, códigos de barras e QR codes.
- **Girar 90°**: deixa páginas e imagens maiores no papel.
- Ajuste de **clarear / escurecer**.
- PDF com várias páginas: imprima todas ou só algumas (ex.: `1-3, 5`).

### 📤 Integração com o Android
- O app aparece no menu **Compartilhar** (galeria, WhatsApp, Arquivos, Drive…) e no **Abrir com** para fotos e PDFs — manda direto para a impressora sem abrir o app antes.

### 👀 O que você vê é o que sai
Tudo é desenhado como imagem na largura exata do papel (384 pontos = 58 mm a 203 dpi). A pré-visualização é idêntica à impressão, e **acentos e "ç" sempre saem certos** — sem depender da tabela de caracteres da impressora.

---

## 📱 Como usar

1. Ligue a impressora e **pareie** no Android: *Configurações › Bluetooth* (senha geralmente `0000` ou `1234`).
2. Abra o Akko's Print, permita o acesso ao Bluetooth e selecione a impressora.
3. Toque em **Página de teste** para confirmar que está tudo certo.
4. Em **Dados da empresa / cabeçalho**, preencha as informações que vão no topo dos cupons.
5. Escolha um modelo, preencha, confira a pré-visualização e toque em **Imprimir**.

### Problemas comuns
- **A impressora não aparece na lista** → ela precisa estar pareada nas configurações do Android primeiro. Depois toque em *Atualizar*.
- **"Não conectou"** → confira se a impressora está ligada e perto, e se nenhum outro app (como o app oficial) está conectado a ela ao mesmo tempo.
- **Foto muito escura ou clara** → use o controle *Clarear / escurecer*.
- **Letra do PDF muito pequena** → ative *Girar 90°* ou prefira PDFs de comprovante/cupom.

---

## ⚠️ Aviso fiscal
Os documentos impressos saem marcados como **"DOCUMENTO SEM VALOR FISCAL"**. Emissão de NF-e / NFC-e exige autorização da SEFAZ e certificado digital, o que está fora do escopo deste app.

---

## 🛠️ Detalhes técnicos

- **Linguagem:** Kotlin + Jetpack Compose (Material 3)
- **Android:** 7.0 (API 24) ou superior
- **Conexão:** Bluetooth clássico SPP (`00001101-0000-1000-8000-00805F9B34FB`), com fallback para o canal RFCOMM 1
- **Protocolo:** ESC/POS, imagem raster `GS v 0`, enviada em faixas de 64 linhas e blocos de 256 bytes (para o buffer pequeno dessas impressoras)
- **PDF:** renderizado com o `PdfRenderer` nativo do Android (até 30 páginas por arquivo)
- **Permissões:** só Bluetooth. Sem internet, sem anúncios, sem rastreamento.

### Estrutura
```
app/src/main/java/com/akkos/print/
├── MainActivity.kt   # Telas (início, modelos, foto/PDF, dados da empresa) e "Compartilhar"
├── Templates.kt      # Modelos de cupom e valor por extenso
├── Receipt.kt        # Desenho do cupom em bitmap + conversão para ESC/POS
├── ImageTools.kt     # Carregar foto/PDF, girar, pontilhar
├── Codes.kt          # QR Code (ZXing) e payload Pix (BR Code + CRC16)
└── Printer.kt        # Conexão Bluetooth, permissões e preferências
```

### Download
**[⬇️ Baixar a última versão (akkos-print.apk)](https://github.com/akkotfpn/printersakkomobile/releases/latest/download/akkos-print.apk)**

### Build
O APK é gerado automaticamente pelo **GitHub Actions** a cada push na `main` e publicado em **Releases**. Todas as versões são assinadas com a mesma chave (`app/debug.keystore`), então cada atualização instala por cima da anterior.

Para compilar localmente: abra no Android Studio ou rode `gradle assembleDebug` (Gradle 8.9, JDK 17).

---

## 🗺️ Ideias futuras
- Suporte a impressoras BLE-only
- Código de barras nos modelos
- Cadastro de produtos e clientes frequentes
- Impressoras de 80 mm
