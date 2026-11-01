# ─────────────────────────────────────────────────────────────────────────────
# Reglas R8/ProGuard para el build de RELEASE de ExtMusic.
# ─────────────────────────────────────────────────────────────────────────────

# Seguridad: elimina los Log.d / Log.v en release (entre ellos el que imprimía la
# URL de stream). Los Log.e/Log.w se conservan para diagnóstico de crashes pero no
# imprimen tokens ni datos de cuenta.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Conserva atributos necesarios para reflexión/serialización.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ── Código propio: keeps ESPECÍFICOS (el resto lo ofusca R8) ──────────────────
# Entidades y DAOs de Room: el mapeo cursor↔campo depende de los nombres.
-keep class com.auramusic.app.data.db.** { *; }
# Modelos de dominio: incluyen enums que se persisten por NOMBRE en DataStore y se
# releen con valueOf(); si R8 renombra sus constantes, valueOf() fallaría.
-keep class com.auramusic.app.domain.model.** { *; }
# Cualquier otro enum de la app usado con valueOf() (defensa en profundidad).
-keepclassmembers enum com.auramusic.app.** { *; }
# WorkManager instancia los workers por nombre de clase (Class.forName), así que el
# worker no puede renombrarse o no se encontraría al recrear el trabajo.
-keep class com.auramusic.app.work.DescargaWorker { *; }
# El resto (ui, service, repository, api, MainActivity, Application) SÍ se ofusca.
# Hilt y Room aportan sus propios keeps para su código generado.

# ── Kotlin / Coroutines ──────────────────────────────────────────────────────
-dontwarn kotlin.**
# Sin -keep de kotlinx.coroutines, io.ktor ni androidx.media3: las tres traen sus
# propias reglas (consumer rules). Conservarlas enteras ocupaba 1,9 MB del APK
# (v1.3.0: 6,66 → 4,72 MB; probado reproducir, buscar, segundo plano y local).
-dontwarn kotlinx.coroutines.**

# ── kotlinx.serialization ────────────────────────────────────────────────────
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <methods>; }
-dontnote kotlinx.serialization.**

# ── Ktor + OkHttp + Okio ─────────────────────────────────────────────────────
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ── NewPipeExtractor + Rhino (CRÍTICO) ───────────────────────────────────────
# NewPipe usa Mozilla Rhino para ejecutar el JS que descifra las firmas de
# YouTube. Si R8 lo elimina, la extracción de audio deja de funcionar en release.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# ── Media3 ───────────────────────────────────────────────────────────────────
-dontwarn androidx.media3.**

# ── slf4j (logger opcional que arrastran Ktor/NewPipe; no se usa en runtime) ──
-dontwarn org.slf4j.**
