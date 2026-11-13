// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.data

import android.media.MediaDrm
import android.os.Build
import pl.kyvaith.velvet.BuildConfig
import java.util.UUID

object DeviceDiagnostics {
    fun report(session: SessionData?, lastStage: String, lastResult: String): String = buildString {
        appendLine("Velvet ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Urządzenie: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}")
        appendLine("Widevine: ${widevine()}")
        appendLine("Tryb odtwarzania: natywny Android L1 / WIDEVINE / NFToken (eksperymentalny)")
        appendLine("Sesja konta: ${if(session == null) "brak" else "zapisana"}")
        appendLine("Logowanie: jedno, na stronie Netflixa wewnątrz Velvet; bez dodatkowego formularza hasła.")
        appendLine("Profil zgodności: native_nftoken_v3; kategoria telefonu P, własna tożsamość instalacji.")
        appendLine("Profil: ${if(session?.profileGuid.isNullOrBlank()) "niewybrany" else "wybrany"}")
        appendLine("ESN strony: ${if(session?.esn.isNullOrBlank()) "brak" else "otrzymany (wartość ukryta)"}")
        appendLine("Autoryzacja odtwarzania: NFToken → ConsumeAutoLoginToken → konfiguracja → konto i profil → natywny manifest i licencja.")
        appendLine("Sesja natywna: oddzielne ciasteczka i tokeny w pamięci; ciasteczka HTTP dobierane do adresu żądania.")
        appendLine("Token logowania: uzyskiwany bezpośrednio od Netflixa; bez serwisu pośredniczącego.")
        appendLine("Ostatni etap: $lastStage")
        appendLine("Wynik: $lastResult")
        appendLine("Pozycja oglądania: zapis lokalny na tym urządzeniu.")
        appendLine("Wersja eksperymentalna: brak certyfikacji klienta przez Netflix.")
        append("Raport nie zawiera ciasteczek, tokenów, danych konta ani adresów filmów.")
    }

    private fun widevine(): String {
        val uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        return try {
            if (!MediaDrm.isCryptoSchemeSupported(uuid)) return "niedostępny"
            MediaDrm(uuid).use { drm ->
                val level = runCatching { drm.getPropertyString("securityLevel") }.getOrDefault("nieznany")
                "$level (nie potwierdza zgody Netflixa na odtwarzanie)"
            }
        } catch (_: Exception) { "nie udało się odczytać" }
    }
}
