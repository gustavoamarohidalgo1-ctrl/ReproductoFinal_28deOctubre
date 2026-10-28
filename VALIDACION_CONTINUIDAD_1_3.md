# Refuerzo de continuidad — versión 1.3

> Registro anterior. El refuerzo posterior del bucle y el sonido está en
> [VALIDACION_BUCLE_CALIDAD_1_4.md](VALIDACION_BUCLE_CALIDAD_1_4.md).

Cambios del 19 de septiembre de 2026 sobre la versión 1.2. Los archivos de 320 kbps
siguen sin recodificación, limitación de bitrate, normalización ni efectos añadidos.
Se mantienen los parámetros de salida y la reserva de audio anteriores.

## Batería

El botón **Evitar restricciones de batería** se oculta con `GONE` cuando
`PowerManager.isIgnoringBatteryOptimizations(packageName)` confirma la excepción.
La app consulta el estado al volver del diálogo o de Ajustes, al recuperar el foco,
al reanudar la pantalla y cada cinco segundos mientras permanece visible. No deja
ese refresco trabajando en segundo plano ni almacena un permiso supuesto.

Comprobado en el emulador Pixel_10a, Android 17/API 37:

- Al conceder la excepción mientras la app está visible, desaparece el botón.
- Al revocarla, vuelve a aparecer.
- Al aceptar el diálogo real de Android, desaparece al regresar a la app.
- La prueba restaura el estado de batería que había antes de ejecutarse.

Esta consulta confirma la excepción estándar de Android. No certifica todas las
restricciones adicionales que pueda incorporar cada fabricante.

## Reproducción y persistencia

1. **Guardado sin cola ilimitada.** Se mantiene una escritura activa y solo el
   último estado pendiente. Si el almacenamiento se ralentiza, las actualizaciones
   intermedias se reemplazan por la más reciente, incluida una pausa. Cada solicitud
   conserva la cola que necesita; el cambio de pista no pierde su dependencia de
   persistencia. El último guardado termina antes de que otra instancia del servicio
   lea el estado en el mismo executor.
2. **Final inesperado con espera.** Un `STATE_ENDED` inesperado pasa por la política
   de reintentos desde posición cero. Ya no ejecuta reinicios inmediatos sin límite.
   Los bucles normales de `REPEAT_ONE` mantienen su comportamiento.
3. **Restauración protegida.** Tras un reinicio del servicio solicitado por Android,
   se publica la notificación y se toma un bloqueo temporal del procesador antes de
   esperar la lectura de una cola grande. Si se restaura una sesión pausada o vacía,
   se retiran esa protección y el primer plano.
4. **Reconstrucción ampliada.** Además de errores de AudioTrack, los errores de
   inicialización del decodificador, decodificación y timeout pueden reconstruir el
   reproductor, conservando archivo, posición, volumen y velocidad.

## Validación automatizada

```sh
ANDROID_HOME=/Users/gustavo/Library/Android/sdk ./gradlew assembleDebug lintDebug testDebugUnitTest connectedDebugAndroidTest
```

Resultado: compilación correcta, **43 pruebas unitarias y 14 de Android correctas**,
sin fallos ni pruebas omitidas. Lint: 0 errores y 25 advertencias.

Las pruebas nuevas incluyen productores concurrentes, 10.000 actualizaciones con
el escritor bloqueado, conservación de la pausa final, recuperación tras excepciones
y una lectura posterior que debe recibir la cola y posición más recientes. Una
prueba con `AtomicFile` real comprueba que solo se ejecutan la escritura en curso y
la última pendiente y que se restaura la nueva pista pausada.

La prueba del final inesperado utiliza un final real del reproductor con repetición
temporalmente desactivada desde el controlador de pruebas. Comprueba la espera y
la recuperación desde el principio, sin añadir entradas de inyección de fallos a
la aplicación. Se conservan las pruebas de tres repeticiones normales, archivo
inaccesible, pausa y calidad del MP3 sintético de 320 kbps: formato original y SHA-256
idéntico antes y después de reproducir.

Evidencia en `app/build/reports/continuity-v1.3/`: `validation-log.txt`,
`validation-summary.json` (incluye checksum del APK), `battery-summary.json` y
capturas XML de cada estado de batería. Los informes de las pruebas están en
`app/build/reports/tests/testDebugUnitTest/` y `app/build/reports/androidTests/connected/debug/`.

## Prueba de ejecución prolongada y recuperación

En el mismo emulador se reprodujo el MP3 sintético de 320 kbps con excepción de
batería temporalmente concedida, pantalla apagada y modo Doze profundo forzado.
Durante 180 segundos se tomaron 36 muestras del checkpoint:

- La posición siguió avanzando y la intención de reproducción permaneció activa.
- Terminar el proceso de interfaz no terminó el proceso de reproducción.
- Se terminó el proceso de audio dos veces; Android lo recreó y la reproducción
  se recuperó en ambos casos, también estando en reposo profundo.
- Al pausar después de la prueba, el estado se guardó pausado; cerrar forzadamente
  y abrir la app conservó la pausa. El informe de energía mostró cero wakelocks
  retenidos después de pausar.
- Se verificó el indicador de 320 kbps y la ausencia del aviso de batería mientras
  estaba concedida la excepción.

El reposo forzado, la simulación de batería y la excepción de la app se restauraron
al terminar. `runtime-summary.json`, `playback-logcat.txt`, `device-idle-after.txt`
y los XML de interfaz conservan la evidencia. Tres minutos en un emulador con
lecturas periódicas por ADB no equivalen a una prueba de autonomía física de 24 horas.

## Alcance

Estas mejoras no garantizan ausencia absoluta de cortes. Recuperarse de la muerte
del proceso puede incluir una interrupción breve. No se ha realizado una prueba
física de sonido y batería de 24 horas ni se han inyectado fallos físicos del
decodificador, AudioTrack o DAC. La simulación unitaria de 24 horas usa un reloj
virtual. El cierre forzado y la pausa del usuario siguen respetándose.

Referencias técnicas verificadas: [excepciones de ahorro de batería en Android](https://developer.android.com/training/monitoring-device-state/doze-standby),
[gestión de wakelocks en Media3 1.5.1](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImpl.java#L2709-L2725)
y [servicio de sesión de Media3 1.5.1](https://github.com/androidx/media/blob/1.5.1/libraries/session/src/main/java/androidx/media3/session/MediaSessionService.java).

APK: `app/build/outputs/apk/debug/app-debug.apk`, versión 1.3, código 4.
Se dejó instalada y abierta en el emulador, con checksum del APK instalado idéntico
al archivo compilado. Se retiraron solamente el audio sintético y su estado de prueba.
