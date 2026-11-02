<#
  IndigoVerse - envia o código do launcher para o GitHub e publica o instalador.
  Precisa de: Git (winget install Git.Git) e GitHub CLI (winget install GitHub.cli), com "gh auth login" feito.

  Uso (dentro da pasta do código):
    .\enviar-github.ps1                                  -> envia só o código
    .\enviar-github.ps1 -Instalador "C:\...\IndigoVerse-Launcher-setup-1.0.0.exe"   -> código + release v1.0.0
#>
param(
    [string]$Repo = 'Vicohenn/IndigoVerse_Launcher',
    [string]$Instalador = '',
    [string]$Versao = '1.0.0'
)
$ErrorActionPreference = 'Continue'   # git escreve avisos no stderr; checamos o código de saída
Set-Location $PSScriptRoot
if (-not (Test-Path .git)) {
    git init -b main | Out-Null
    git remote add origin "https://github.com/$Repo.git"
}
git add -A
git commit -m "IndigoVerse Launcher $Versao" 2>$null
git fetch origin 2>$null
# se o repositório no GitHub já tem um README/commit inicial, junta os históricos
git pull origin main --allow-unrelated-histories --no-edit 2>$null
git push -u origin main
if ($LASTEXITCODE -ne 0) { Write-Host 'Falha no push. Veja a mensagem acima (login do Git?).' -ForegroundColor Red; exit 1 }
if ($Instalador) {
    gh release create "v$Versao" $Instalador --repo $Repo --title "IndigoVerse Launcher $Versao" --notes 'Baixe o instalador abaixo e execute.' --latest
}
Write-Host "Pronto: https://github.com/$Repo" -ForegroundColor Green
