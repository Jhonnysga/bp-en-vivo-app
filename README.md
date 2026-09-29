# BéisbolPlay (APK)

App Android para ver las transmisiones en vivo del backend «BéisbolPlay»,
parecida a la app de BeisbolPlay: lista los partidos al aire y los reproduce
a pantalla completa. Funciona en teléfonos y en Android TV / Fire TV Stick.

## Cómo funciona

- La lista de transmisiones sale de `GET {BACKEND}/index.php?api=videos`
  (JSON con `live`: id, título, miniatura, canal y logo).
- Al elegir una, el reproductor (ExoPlayer / Media3) carga
  `{BACKEND}/proxy.php?m3u8=<id>`, que devuelve la playlist maestra HLS.
  El token del stream va atado a la IP del servidor, por eso todo el
  tráfico de video pasa por el backend y la app nunca pide el `.m3u8`
  directo al CDN.

## Dónde se configura la URL del backend

En `app/build.gradle`, campo `buildConfigField`:

```groovy
buildConfigField("String", "BACKEND_URL", "\"https://jhon-pc.tailc7b9f2.ts.net\"")
```

Se expone como `BuildConfig.BACKEND_URL`. Para apuntar a otro servidor,
cambia ese valor y vuelve a compilar. La app necesita alcanzar esa URL:
en el teléfono y en el Fire TV Stick debe estar instalado y conectado
**Tailscale** (misma red que el servidor).

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
