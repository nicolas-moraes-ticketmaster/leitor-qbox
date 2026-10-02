# Leitor QBOX (Zebra TC22)

App Android para o TC22. Recebe as leituras do scanner pelo DataWedge e valida cada ingresso direto no Q-Box pela rede local (`POST /api/access/validate`).

## Arquitetura no evento

```
[TC22 + app]  --Wi-Fi-->  [Roteador]  <--Wi-Fi/cabo-->  [Laptop: VirtualBox + VM Q-Box :8080]  --Internet-->  Quentro Cloud
```

O servidor Windows não participa da validação. O app fala com o Q-Box direto, e a documentação permite isso: qualquer sistema que faça um POST com os headers consegue integrar.

## 1. Preparar o Q-Box no laptop

1. Importe o `.ova` no VirtualBox.
2. Em **Settings > Network**, coloque o adaptador em **Bridged Adapter**, apontando para a placa **Wi-Fi** do laptop (ou para a cabeada, se o laptop estiver no cabo do roteador).
3. Inicie a VM e anote o IP que aparece no rodapé do console. **Esse IP é da VM, não do laptop.**
4. No roteador, reserve esse IP para o MAC da VM (reserva de DHCP).
5. No navegador do laptop, abra `http://IP_DA_VM:8080`, crie o usuário, adicione o show e **ligue a Sync e as Validações**.

> O roteador precisa estar com o **AP/Client Isolation desligado**. Para sincronizar com a nuvem, o Q-Box precisa de internet. A validação funciona sem internet.

## 2. Gerar o APK

**Opção A: Android Studio.** Abra a pasta `leitor-qbox` e use **Build > Build APK(s)**. O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

**Opção B: GitHub Actions.** Suba esta pasta para um repositório no GitHub. O workflow `.github/workflows/build.yml` compila o APK a cada push. Baixe em **Actions > (última execução) > Artifacts > LeitorQBOX-apk**.

## 3. Instalar no TC22

- Copie o APK por USB (modo transferência de arquivos) e abra no gerenciador de arquivos, permitindo "fontes desconhecidas"; **ou**
- Com a depuração USB ligada, rode: `adb install -r app-debug.apk`

## 4. Configurar o app

Toque na engrenagem e preencha:

| Campo | Exemplo |
|---|---|
| Endereço do Q-Box | `192.168.0.50` (a porta 8080 é adicionada sozinha) ou `qbox.local` |
| Show ID | `746` (show de teste) ou `V-...` (show virtual) |
| Token | token do show (painel do Q-Box, botão **ID e token**) |
| Nome do leitor | `Portão Norte - TC22 01`. Vai no campo `gate` e aparece no log |

Toque em **Testar conexão e carregar setores**. Se aparecer "Conectado!", marque os setores daquele portão na barra de setores da tela de leitura. Não existe modo "todos": sem setor marcado, a leitura fica bloqueada.

Opcional: **Modo consulta** usa `/check` e não marca o ingresso como usado. **PIN** impede que o operador mude a configuração.

## Como funciona

- Na primeira abertura, o app cria sozinho o profile **LeitorQBOX** no DataWedge, com saída por intent e teclado desligado. Não precisa configurar nada no DataWedge.
- Leitura com o gatilho físico, com o botão **LER** ou digitando o código.
- Resultado em tela cheia, com bipe e vibração:
  - 🟢 **LIBERADO**: nome, setor e documento do titular
  - 🔵 **CÓDIGO MESTRE**
  - 🔴 **JÁ UTILIZADO** (hora e portão), **SETOR INVÁLIDO**, **ANULADO**, **BLOQUEADO**, **CÓDIGO INVÁLIDO**, **NÃO ENCONTRADO**
  - 🟠 **SEM CONEXÃO**, **SHOW NÃO ABERTO**, **ERRO NO Q-BOX**, **ERRO DE CONFIGURAÇÃO**
- Ignora leitura repetida do mesmo código em menos de 1,5 s.

## Teste rápido (show de teste 746)

1. Compre um ingresso de teste em https://ticketing.getcrowder.com/event/evento-quentro-accesos
2. Leia o QR com o TC22: deve aparecer **LIBERADO**.
3. Leia de novo: deve aparecer **JÁ UTILIZADO**.
4. Confira no painel do Q-Box, aba Log, se a leitura aparece com o nome do portão.
