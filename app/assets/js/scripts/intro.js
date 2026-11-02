// Intro IndigoVerse: o launcher só sai da tela de carregamento depois que o vídeo
// termina (ou o jogador clica para pular). Limite de 15 s por segurança.
window.introDone = new Promise((resolve) => {
    const v = document.getElementById('introVideo')
    let done = false
    const fim = () => { if(!done){ done = true; resolve() } }
    v.volume = 0.6
    v.addEventListener('ended', fim)
    v.addEventListener('error', fim)
    const ult = v.querySelector('source:last-of-type')
    if(ult) ult.addEventListener('error', fim)
    document.getElementById('loadingContainer').addEventListener('click', () => { v.pause(); fim() })
    v.play().catch(() => { v.muted = true; v.play().catch(fim) })
    // se o vídeo não começar em 4 s (codec/áudio indisponível), segue sem ele
    let tocou = false
    v.addEventListener('playing', () => { tocou = true })
    setTimeout(() => { if(!tocou) fim() }, 4000)
    setTimeout(fim, 14000)
})
