# bplay (APK)

App Android para ver las transmisiones en vivo de BeisbolPlay, con
temática similar a la app oficial: lista los partidos al aire y los
reproduce a pantalla completa. Funciona en teléfonos y en Android TV /
Fire TV Stick. **No depende de ningún servidor ni de la PC**: todo se
resuelve en el propio dispositivo.

## Cómo funciona (v2.0)

- La lista de transmisiones sale directo de la API de Dailymotion
  (videos en vivo del canal `beisbolplay`); cada candidato se confirma
  contra el metadata del player antes de mostrarse.
- Al elegir una, la app extrae la URL maestra HLS (`.m3u8`) del video
  en el propio dispositivo y la reproduce con ExoPlayer (Media3)
  enviando los headers que exige Dailymotion (`Referer`). La IP que
  pide el stream es la misma que lo reproduce, como exige el token.
- Cada reintento automático extrae una URL fresca (los tokens vencen).

## TV / Fire TV Stick

- En el reproductor, **arriba/abajo del D-pad cambia de canal**
  (zapping con vuelta al inicio/fin). Solo zapea cuando los overlays
  están ocultos; si están visibles, el D-pad navega la UI.
- Al cambiar de canal (y al arrancar) aparece un **banner profesional**
  con el número del canal, su nombre y la pastilla EN VIVO; se
  desvanece solo a los 4 segundos.

## Compilación automática (GitHub Actions)

Cada `push` a GitHub ejecuta `.github/workflows/android.yml`:

1. Descarga el SDK de Android y JDK 17 (Temurin).
2. Corre `./gradlew assembleDebug`.
3. Publica el APK como artefacto **`app-debug`**.

Para descargar el APK: pestaña **Actions** del repo → última ejecución →
artefacto `app-debug` → `app-debug.apk`. Es un APK *debug*: se instala
directo, sin firma de release.

## Instalación en el teléfono

1. Descarga `app-debug.apk` en el teléfono.
2. Ábrelo y permite «instalar apps desconocidas» si lo pide.
3. Listo: aparece como **BéisbolPlay**.

O por ADB: `adb install app-debug.apk`.

## Instalación en el Fire TV Stick

1. En el Stick: Ajustes → Mi Fire TV → Opciones de desarrollador →
   activa **Depuración ADB** y **Apps de origen desconocido**.
2. Instala **Tailscale** en el Stick e inicia sesión en la misma red.
3. Opción A (sin PC): instala la app **Downloader**, escribe la URL del
   APK (o súbelo a un enlace corto) y ábrelo para instalar.
4. Opción B (con PC): `adb connect <ip-del-stick>:5555` y luego
   `adb install app-debug.apk`.
5. La app aparece en la fila de apps con su banner «BéisbolPlay».

## Estructura

- `app/src/main/java/com/jhonnysga/bpenvivo/` — código Kotlin
  (`MainActivity`, `PlayerActivity`, `ApiClient`, `ImageLoader`,
  `StreamAdapter`, paquete `tv` con la interfaz Leanback).
- `app/src/main/res/` — layouts, textos, colores, iconos y banner de TV.
