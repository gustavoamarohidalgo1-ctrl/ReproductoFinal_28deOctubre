# Refuerzo de rendimiento y persistencia — versión 1.7

## Cambios

- **Fallos de almacenamiento:** los intentos fallidos de guardar la misma
  generación de cola, pista e intención de reproducción esperan progresivamente
  2, 4, 8, 16 y hasta 30 segundos. La comprobación ocurre antes de serializar y
  escribir, evitando repetir ese trabajo mientras el almacenamiento sigue fallando.
  Las pausas y búsquedas manuales solicitan un intento inmediato; esa prioridad
  sobrevive al reemplazo de una tarea pendiente y se consume una sola vez.
  Un guardado correcto restablece la frecuencia habitual de dos segundos. Se
  mantienen el escritor serial, la escritura atómica y el último estado pendiente.
- **Colas con archivos inaccesibles:** se eliminó el recorrido completo de los
  registros de errores en cada fallo cuando el tamaño de la cola permanece igual.
  La limpieza de índices fuera de rango ocurre al reducirse la cola. Se conservan
  las esperas, el cambio de pista y las rondas de recuperación existentes.
- **Biblioteca:** las consultas usan el ID como último criterio de ordenación para
  resolver empates entre títulos/carpetas. Así, esos empates no provocan cambios
  arbitrarios de orden que invaliden la caché o la revisión de la biblioteca.
- **Versión:** 1.7, código 8. El APK local `benchmark` usa R8 y reducción de recursos,
  no permite depuración y lleva la firma local de pruebas.

La espera de guardado no es un límite universal: una nueva pista, cola o intención,
y las órdenes explícitas del usuario, pueden solicitar un intento antes. Tampoco
permite persistir en un almacenamiento que sigue rechazando escrituras. La app
mantiene el último estado confirmado y continúa intentando guardar sin bloquear
el hilo de reproducción.

Este refuerzo conserva los bucles, Media3 1.6.1, las muestras originales, el PCM
flotante cuando está disponible, la velocidad, el tono, los buffers, el proceso de
audio separado y los mecanismos de reproducción en segundo plano. No se alteró
la fidelidad para reducir CPU o tamaño del APK.

## Validación ejecutada

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleBenchmark :app:assembleDebugAndroidTest :validation:assembleBenchmark
ANDROID_SERIAL=emulator-5586 ./gradlew :validation:connectedBenchmarkAndroidTest
```

- Compilación correcta. **63 pruebas unitarias correctas**, cero fallos.
  Incluyen el consumo único de prioridad tras reemplazar tareas pendientes,
  recuperación después de fallos y 24 horas de reintentos con reloj virtual.
  También comprueban dos rondas de recuperación de una cola de 20.000 archivos
  y cambios de tamaño de la cola.
- Lint: **cero errores**, 22 advertencias.
- APK optimizado real: **21 pruebas de integración correctas**, cero fallos y
  una prueba optativa de duración real omitida. Tiempo de las pruebas: 205,969 s.
  Se ejecutaron en un emulador temporal API 37; el arnés se comunica por IPC con
  el proceso del servicio. No se ejecutó esta suite sobre los archivos del teléfono.
- La prueba nueva bloquea de verdad la creación del archivo temporal del checkpoint
  durante diez segundos de reproducción. Verifica avance, ausencia de errores del
  reproductor y conservación del checkpoint anterior. Después retira el bloqueo
  y comprueba que una búsqueda manual guarda la posición sin esperar todo el
  intervalo de recuperación. Las pruebas unitarias verifican las esperas; esta
  prueba Android verifica su integración con el servicio y el almacenamiento real.
- Se volvieron a verificar pausa y selección ordenadas, bucles, archivos inaccesibles,
  búsqueda después de 25 horas en un WAV sparse de 27 horas, cola vacía persistente
  y recuperación después de terminar el proceso. En el reinicio sticky observado,
  PID 8338 → 9076, checkpoint 3.115 ms → posición restaurada 3.139 ms, sin enviar
  una nueva orden de reproducción desde el controlador.

El reloj virtual de 24 horas y el WAV sparse no son una escucha de esa duración.
No se ha completado una prueba física de sonido/batería de 24 horas en el Xiaomi.
Las mediciones de rendimiento y el ensayo de 180 segundos de la versión anterior
están en [VALIDACION_RENDIMIENTO_1_6.md](VALIDACION_RENDIMIENTO_1_6.md); no se presentan
como mediciones nuevas de esta versión.

## APK y teléfono

APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`, **2.931.607 bytes**.
Firma verificada con `apksigner`.

SHA-256:
`456cbb29e3957f2ab1b0a2b6160632a63b365edd8b648c79704826a52d511f6b`

Teléfono conectado: Xiaomi 24117RN76L, Android 16/API 36.
El primer intento con `adb install -r` fue rechazado por el teléfono con
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. La instalación está
pendiente de habilitarla en el teléfono. También se copió el APK a
`Descargas/Reproductor-1.7.apk` para permitir la instalación manual.

Evidencia local en `app/build/reports/validation-1.7/`: `build.log`,
`static-validation.json`, `optimized-blackbox.log`, XML de resultados,
`optimized-results.json`, `apk.json` y `phone-install.json`.
