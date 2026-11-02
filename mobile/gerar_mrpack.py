"""
IndigoVerse - gera o modpack para celular (.mrpack) a partir da lista publicada do launcher.

O .mrpack é o formato de modpack do Modrinth. Launchers Android baseados no PojavLauncher
(Zalith Launcher, FoldCraftLauncher/FCL, MojoLauncher, Amethyst...) importam esse arquivo e
baixam os mods sozinhos, direto da release "pack" do GitHub.

O pack de celular é o mesmo do PC, com estas diferenças para rodar em mais aparelhos:
  - sem mods só visuais e pesados (shaders, animações, partículas, texturas conectadas, CEM/ETF);
  - só os resource packs essenciais (sem as trilhas sonoras de 400 MB e os retexturizados);
  - sem os datapacks de mundo (só servem para singleplayer);
  - options.txt já configurado para desempenho (distância 5, gráficos rápidos, 60 FPS...);
  - som 3D (Sound Physics) desligado;
  - servidor IndigoVerse já na lista de servidores.

Uso:  python gerar_mrpack.py [--endereco shrimp-cosmos.tun.ply.gg] [--versao 1.0.0]
Saída: IndigoVerse-Mobile-<versao>.mrpack
"""
import argparse
import hashlib
import io
import json
import re
import struct
import urllib.request
import zipfile

DISTRO = 'https://github.com/Vicohenn/IndigoVerse_Launcher/releases/download/pack/distribution.json'

# ids (fabric.mod.json) que ficam de fora no celular. Nenhum deles adiciona blocos/itens
# nem é dependência de outro mod, então o cliente continua compatível com o servidor.
MODS_FORA = {
    'iris', 'euphoria_patcher',                           # shaders
    'continuity',                                         # texturas conectadas
    'entity_model_features', 'entity_texture_features',   # modelos/texturas animadas
    'particlerain', 'particular',                         # partículas extras
    'notenoughanimations',                                # animações do jogador
    'resourcepackoverrides',                              # forçaria os 40 resource packs do PC
    'stackdeobfuscator',                                  # baixa mapeamentos ao abrir o jogo
    # v1.0.1: mods que só trabalham no servidor (funcionam sem estar no cliente) e pesam na RAM
    'worldedit', 'impactor', 'gts', 'impactorbattle', 'cobbledollarsbridge',
    # v1.0.1: menus personalizados (FancyMenu estourou a memória num celular de teste)
    'fancymenu', 'konkrete', 'melody',
    # v1.0.3: Litematica é opcional e pesado para celular
    'litematica', 'malilib',
}

# resource packs leves que entram no celular (o resto do PC é cosmético e pesado)
PACKS_CELULAR = [
    'COBBLEVERSE RP.zip', 'COBBLEVERSE RCTmod RP.zip', 'Cobblemon Interface Modded.zip',
    'Low Fire.zip', 'Classic Grass.zip', 'Comforts Modernized.zip',
    'ATMxMSD RP.zip',   # modelos de Paradoxos, Ultra Beasts e míticos que o Cobblemon não tem
]

# configs do PC que também vão (tela de carregamento IndigoVerse)
CONFIGS = ('config/customsplashscreen', 'config/global_packs.toml',
           'config/xaero/', 'config/xaerohud.txt')   # v1.0.3: minimapa igual ao do PC

OPTIONS = """version:3955
renderDistance:5
simulationDistance:5
graphicsMode:0
ao:false
biomeBlendRadius:0
entityShadows:false
entityDistanceScaling:0.6
particles:2
renderClouds:"false"
mipmapLevels:0
maxFps:60
enableVsync:false
guiScale:3
fov:0.0
screenEffectScale:0.5
fovEffectScale:0.5
prioritizeChunkUpdates:0
soundCategory_music:0.25
skipMultiplayerWarning:true
onboardAccessibility:false
telemetryOptInExtra:false
realmsNotifications:false
narratorHotkey:false
resourcePacks:["vanilla","fabric",%s]
"""

SOUNDPHYSICS = 'enabled=false\n'


def baixar(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def hashes(dados):
    return {'sha1': hashlib.sha1(dados).hexdigest(), 'sha512': hashlib.sha512(dados).hexdigest()}


def mod_id(dados):
    try:
        t = zipfile.ZipFile(io.BytesIO(dados)).read('fabric.mod.json').decode('utf-8', 'replace')
        t = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f]', ' ', t)
        return json.loads(t, strict=False).get('id')
    except Exception:
        return None


# --- servers.dat (NBT sem compressão) ---
def _str(s):
    b = s.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def servers_dat(nome, ip):
    servidor = b'\x08' + _str('name') + _str(nome) + b'\x08' + _str('ip') + _str(ip) + b'\x00'
    lista = b'\x09' + _str('servers') + b'\x0a' + struct.pack('>i', 1) + servidor
    return b'\x0a' + _str('') + lista + b'\x00'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--endereco', default='shrimp-cosmos.tun.ply.gg')
    ap.add_argument('--versao', default='1.0.3')
    a = ap.parse_args()

    distro = json.loads(baixar(DISTRO))
    srv = distro['servers'][0]
    loader = next(m['id'].split(':')[2] for m in srv['modules'] if m['type'] == 'Fabric')

    arquivos, overrides, fora = [], {}, []
    for m in srv['modules']:
        art = m['artifact']
        if m['type'] == 'FabricMod':
            dados = baixar(art['url'])
            mid = mod_id(dados)
            if mid in MODS_FORA:
                fora.append(m['name'])
                continue
            nome = art['url'].rsplit('/', 1)[1].removeprefix('mod_')
            arquivos.append({'path': 'mods/' + nome, 'hashes': hashes(dados),
                             'env': {'client': 'required', 'server': 'unsupported'},
                             'downloads': [art['url']], 'fileSize': len(dados)})
        elif m['type'] == 'File':
            p = art['path']
            if p.startswith('resourcepacks/') and p.split('/', 1)[1] in PACKS_CELULAR:
                dados = baixar(art['url'])
                arquivos.append({'path': p, 'hashes': hashes(dados),
                                 'env': {'client': 'required', 'server': 'unsupported'},
                                 'downloads': [art['url']], 'fileSize': len(dados)})
            elif p.startswith('mods/'):            # datapacks em .zip que ficam na pasta mods
                dados = baixar(art['url'])
                arquivos.append({'path': p, 'hashes': hashes(dados),
                                 'env': {'client': 'required', 'server': 'unsupported'},
                                 'downloads': [art['url']], 'fileSize': len(dados)})
            elif p.startswith(CONFIGS):
                overrides[p] = baixar(art['url'])

    packs = ','.join('"file/%s"' % n for n in PACKS_CELULAR)
    opcoes = (OPTIONS % packs).encode('utf-8')
    overrides['options.txt'] = opcoes
    overrides['config/defaultoptions/options.txt'] = opcoes
    sdat = servers_dat('IndigoVerse', a.endereco)
    overrides['servers.dat'] = sdat
    overrides['config/defaultoptions/servers.dat'] = sdat
    overrides['config/sound_physics_remastered/soundphysics.properties'] = SOUNDPHYSICS.encode()

    indice = {
        'formatVersion': 1, 'game': 'minecraft', 'versionId': a.versao,
        'name': 'IndigoVerse Mobile',
        'summary': 'Cobblemon 1.8.4 do servidor IndigoVerse, otimizado para celular',
        'files': arquivos,
        'dependencies': {'minecraft': srv['minecraftVersion'], 'fabric-loader': loader},
    }
    saida = 'IndigoVerse-Mobile-%s.mrpack' % a.versao
    with zipfile.ZipFile(saida, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('modrinth.index.json', json.dumps(indice, indent=2, ensure_ascii=False))
        for p, dados in sorted(overrides.items()):
            z.writestr('overrides/' + p, dados)

    total = sum(f['fileSize'] for f in arquivos)
    print('Mods/arquivos: %d (%.0f MB a baixar no celular)' % (len(arquivos), total / 1e6))
    print('Fora no celular: ' + ', '.join(sorted(fora)))
    print('Gerado: ' + saida)


if __name__ == '__main__':
    main()
