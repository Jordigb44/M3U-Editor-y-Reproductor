# M3U Editor — Pepe Editor / Pepe Editor TV

Gestor de listas IPTV **M3U / M3U8**: carga por URL o archivo, gestión masiva de canales y
grupos, búsqueda, exportación y reproducción integrada (ExoPlayer) o con apps externas.

El proyecto contiene **dos aplicaciones independientes que comparten toda la lógica de negocio**:

| App | Módulo | Público | applicationId |
|---|---|---|---|
| **Pepe Editor** (móvil) | `:app` | Teléfono/táctil — interfaz actual, fácil de usar | `com.aistudio.m3ueditor.abcde` |
| **Pepe Editor TV** (TV box) | `:apptv` | Android TV / Fire TV — UI de 10 pies rediseñada desde cero (D-pad, leanback) | `com.aistudio.m3ueditor.tv` |
| **Lógica compartida** | `:core` | Parser M3U, modelos, red (OkHttp) y `EditorViewModel` | — |

## Funciones (idénticas en ambas apps)

- Cargar listas por **URL** (con pegado desde portapapeles y lista demo) o desde **archivo**
  (gestor externo / explorador integrado con escaneo profundo).
- **Múltiples playlists** guardadas: renombrar, eliminar, activar.
- **Canales**: búsqueda avanzada (nombre, grupo o URL), filtro por grupo, multi-selección,
  borrado, mover a grupo.
- **Grupos**: crear, renombrar, eliminar (masivo en móvil; individual con confirmación en TV),
  grupos personalizados persistidos.
- **Exportar** la lista editada (`.m3u` / `.m3u8`) a Descargas, almacenamiento interno, Movies o Documents.
- **Reproductor integrado** (ExoPlayer + HLS) con reconexión automática ×5, pantalla completa
  y controles por D-pad en TV; **reproductor por defecto** configurable (integrado / preguntar / externo).
- **Reproducción externa** (VLC, MX Player, etc.) con detección de apps instaladas.

## Requisitos

- JDK 17/21 (el JDK 26 actual puede romper las librerías nativas de Gradle en macOS; usa el JBR
  de Android Studio: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- Android SDK 36 (`local.properties` con `sdk.dir`).

## Build

```bash
# Wrapper generado (Gradle 9.5.1):
./gradlew :app:assembleDebug        # APK móvil  → app/build/outputs/apk/debug/
./gradlew :apptv:assembleDebug      # APK TV     → apptv/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # Tests unitarios (parser M3U + Robolectric)
```

## Notas

- La versión **release** requiere un keystore y las variables `KEYSTORE_PATH`,
  `STORE_PASSWORD` y `KEY_PASSWORD` (ver `app/build.gradle.kts` y `apptv/build.gradle.kts`).
- `MANAGE_EXTERNAL_STORAGE` es necesaria para el explorador de archivos integrado en Android 11+
  (relevante en Fire TV); la app está pensada para distribución directa (sideload).
- Seguridad: el tráfico TLS usa validación estándar; las URLs `http://` explícitas siguen
  permitidas para listas IPTV antiguas (`usesCleartextTraffic`).
