const { DistributionAPI } = require('helios-core/common')

const ConfigManager = require('./configmanager')

// Old WesterosCraft url.
// exports.REMOTE_DISTRO_URL = 'http://mc.westeroscraft.com/WesterosCraftLauncher/distribution.json'
const fs = require('fs')
const path = require('path')
function lerUrlDistro(){
    const padrao = 'https://github.com/Vicohenn/IndigoVerse_Launcher/releases/download/pack/distribution.json'
    const candidatos = [
        path.join(ConfigManager.getLauncherDirectory(), 'indigoverse.json'),
        process.resourcesPath ? path.join(process.resourcesPath, 'indigoverse.json') : null,
        path.join(__dirname, '..', '..', '..', 'indigoverse.json')
    ].filter(Boolean)
    for(const c of candidatos){
        try {
            const j = JSON.parse(fs.readFileSync(c, 'utf-8'))
            if(j.distributionUrl) return j.distributionUrl
        } catch (_e) { /* tenta o próximo */ }
    }
    return padrao
}
exports.REMOTE_DISTRO_URL = lerUrlDistro()

const api = new DistributionAPI(
    ConfigManager.getLauncherDirectory(),
    null, // Injected forcefully by the preloader.
    null, // Injected forcefully by the preloader.
    exports.REMOTE_DISTRO_URL,
    false
)

exports.DistroAPI = api