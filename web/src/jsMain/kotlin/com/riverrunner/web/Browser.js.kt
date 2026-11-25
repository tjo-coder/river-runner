package com.riverrunner.web

// Each call is wrapped in try/catch: Safari in private mode, or a full storage, throws.

internal actual fun storageGet(key: String): String? =
    js("(function(k){ try { return localStorage.getItem(k); } catch (e) { return null; } })")(key) as String?

internal actual fun storageSet(key: String, value: String): Boolean =
    js("(function(k, v){ try { localStorage.setItem(k, v); return true; } catch (e) { return false; } })")(key, value) as Boolean

internal actual fun storageRemove(key: String) {
    js("(function(k){ try { localStorage.removeItem(k); } catch (e) {} })")(key)
}

internal actual fun browserLanguage(): String = js("navigator.language || ''") as String

internal actual fun deliverExportFile(filename: String, text: String): String =
    js("(function(name, text){\n" +
        "  try {\n" +
        "    var blob = new Blob([text], {type:'application/json'});\n" +
        "    var url = URL.createObjectURL(blob);\n" +
        "    var a = document.createElement('a');\n" +
        "    a.href = url; a.download = name; a.rel = 'noopener';\n" +
        "    document.body.appendChild(a); a.click(); a.remove();\n" +
        "    setTimeout(function(){ URL.revokeObjectURL(url); }, 2000);\n" +
        "    return 'download';\n" +
        "  } catch (e) {}\n" +
        "  try {\n" +
        "    if (navigator.share) {\n" +
        "      var file = new File([text], name, {type:'application/json'});\n" +
        "      if (!navigator.canShare || navigator.canShare({files:[file]})) {\n" +
        "        navigator.share({files:[file], title:name});\n" +
        "        return 'share';\n" +
        "      }\n" +
        "    }\n" +
        "  } catch (e) {}\n" +
        "  try {\n" +
        "    if (navigator.clipboard && navigator.clipboard.writeText) {\n" +
        "      navigator.clipboard.writeText(text);\n" +
        "      return 'copy';\n" +
        "    }\n" +
        "  } catch (e) {}\n" +
        "  return 'fail';\n" +
        "})")(filename, text) as String

internal actual fun copyExportText(text: String): Boolean =
    js("(function(text){ try { if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(text); return true; } } catch (e) {} return false; })")(text) as Boolean

internal actual fun playAudio(url: String, volume: Double, rate: Double) {
    js("(function(u, v, r){ try { var a = new Audio(u); a.volume = v; a.playbackRate = r; var p = a.play(); if (p && p.catch) p.catch(function(){}); } catch (e) {} })")(url, volume, rate)
}
