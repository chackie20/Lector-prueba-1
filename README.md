# LectorContador – ZIV 5CTB-3AB-M

Proyecto Android preparado para GitHub Actions.

## Comunicación ZIV
- Saludo óptico: 300 baudios, 7E1.
- Cambio posterior: 9600 baudios, 8N1.
- Diagnóstico ZIV: comprueba la comunicación y solicita el consumo activo total.
- Lectura DLMS/COSEM para ZIV 5CTB.
- También conserva las opciones IEC 62056-21 e IEC 870-5-102 del proyecto.

## Generar el APK
Al subir el proyecto a GitHub y hacer un `push` a `main`/`master`, GitHub Actions ejecutará la compilación.
También se puede lanzar manualmente desde **Actions → Compilar APK Android → Run workflow**.

El APK aparecerá como artefacto:
`LectorContador-debug-apk`

## Importante
El proyecto no depende de `gradle-wrapper.jar` para GitHub Actions: la acción instala Gradle 8.9 directamente.
