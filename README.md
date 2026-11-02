# IndigoVerse Launcher

Launcher do servidor **IndigoVerse** (Cobblemon 1.8.4 · Minecraft 1.21.1 · Fabric 0.18.4), baseado no [Helios Launcher](https://github.com/dscalzi/HeliosLauncher) (MIT).

## Para jogar
1. Baixe o instalador em [Releases](https://github.com/Vicohenn/IndigoVerse_Launcher/releases/latest).
2. Execute. Se o Windows mostrar "editor desconhecido", clique em *Mais informações → Executar assim mesmo*.
3. Entre com a conta Microsoft ou com um nick (offline) e clique em **JOGAR**. O launcher baixa os mods sozinho.

## Como funciona
- O launcher lê `https://github.com/Vicohenn/IndigoVerse_Launcher/releases/download/pack/distribution.json`.
- A release **pack** guarda os mods, resourcepacks e configs. Ela é gerada e enviada pelos scripts
  `gerar-distribuicao.ps1` e `publicar.ps1` (pacote de ferramentas, fora deste repositório).
- Para trocar o endereço da lista sem recompilar: crie `%APPDATA%\IndigoVerse Launcher\indigoverse.json`
  com `{ "distributionUrl": "..." }`.

## Compilar
```
npm ci
npm run dist:win
```
