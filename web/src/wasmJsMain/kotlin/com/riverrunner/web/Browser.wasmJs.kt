package com.riverrunner.web

// Each call is wrapped in try/catch: Safari in private mode, or a full storage, throws.

private fun jsStorageGet(k: String): String? = js("(function(){ try { return localStorage.getItem(k); } catch (e) { return null; } })()")
private fun jsStorageSet(k: String, v: String): Boolean = js("(function(){ try { localStorage.setItem(k, v); return true; } catch (e) { return false; } })()")
private fun jsStorageRemove(k: String): Unit = js("(function(){ try { localStorage.removeItem(k); } catch (e) {} })()")
private fun jsLanguage(): String = js("navigator.language || ''")
private fun jsPlay(u: String, v: Double, r: Double): Unit =
    js("(function(){ try { var a = new Audio(u); a.volume = v; a.playbackRate = r; var p = a.play(); if (p && p.catch) p.catch(function(){}); } catch (e) {} })()")

internal actual fun storageGet(key: String): String? = jsStorageGet(key)
internal actual fun storageSet(key: String, value: String): Boolean = jsStorageSet(key, value)
internal actual fun storageRemove(key: String) = jsStorageRemove(key)
internal actual fun browserLanguage(): String = jsLanguage()
private fun jsDeliver(name: String, text: String): String = js(
    "(function(){ try { var blob = new Blob([text], {type:'application/json'}); var url = URL.createObjectURL(blob); var a = document.createElement('a'); a.href = url; a.download = name; a.rel = 'noopener'; document.body.appendChild(a); a.click(); a.remove(); setTimeout(function(){ URL.revokeObjectURL(url); }, 2000); return 'download'; } catch (e) {} try { if (navigator.share) { var file = new File([text], name, {type:'application/json'}); if (!navigator.canShare || navigator.canShare({files:[file]})) { navigator.share({files:[file], title:name}); return 'share'; } } } catch (e2) {} try { if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(text); return 'copy'; } } catch (e3) {} return 'fail'; })()"
)

private fun jsCopy(text: String): Boolean = js(
    "(function(){ try { if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(text); return true; } } catch (e) {} return false; })()"
)

internal actual fun deliverExportFile(filename: String, text: String): String = jsDeliver(filename, text)
internal actual fun copyExportText(text: String): Boolean = jsCopy(text)

internal actual fun playAudio(url: String, volume: Double, rate: Double) = jsPlay(url, volume, rate)
