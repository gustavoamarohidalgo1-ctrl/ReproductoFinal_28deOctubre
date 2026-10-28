# Bucle automático y fidelidad del sonido — versión 1.4

Refuerzo del 19 de septiembre de 2026. Se conserva el archivo original de 320 kbps;
no hay recodificación, aumento artificial de bitrate, ecualización, normalización
ni remuestreo añadido por la aplicación.

## Bucle de la pista seleccionada

El motor mantiene `REPEAT_MODE_ONE`, mezcla aleatoria desactivada y pausa al final
desactivada. La sesión ya no publica órdenes para cambiar repetición o mezcla. Si
algún código interno modifica esos dos valores, listeners idempotentes los corrigen.
El mismo constructor se utiliza al abrir, restaurar o reconstruir el reproductor.
La pausa del usuario y el desplazamiento manual siguen disponibles.

La prueba nueva reproduce un segmento real de 800 ms de un MP3 de 320 kbps,
seleccionado como segunda pista de una cola de dos entradas. Lo observa durante
26 segundos, más allá del plazo de 20 segundos del vigilante: exige al menos
20 vueltas, siempre la misma pista e índice, sin IDLE, ENDED ni errores. También
se conservan las tres vueltas naturales del archivo completo de 12 segundos.

## Calidad y unión entre vueltas

Se actualizó Media3 de 1.5.1 a **1.6.1** por una corrección concreta: la cadena de
salida PCM flotante incorpora ahora el recorte del retraso y relleno de codificación.
Esto permite aprovechar los metadatos gapless sin forzar una salida de menor
precisión ni implementar un procesador propio. Fuentes oficiales:
[cadena flotante de 1.6.1](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L559-L561)
y [soporte de float en el recorte](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/TrimmingAudioProcessor.java#L74-L75).

Velocidad y tono quedan en 1, ganancia digital en 1 y salto de silencios desactivado.
La sesión bloquea órdenes para alterar velocidad, tono o ganancia digital y el motor
corrige cambios internos de esos parámetros. El volumen físico del teléfono sigue
funcionando normalmente. Al reconstruirse, el motor aplica estos valores originales
en lugar de heredar una modificación accidental.

Una prueba del `DefaultAudioSink` completo usa `AudioTrack` real y observa los bytes
que Android acepta. Envía PCM flotante estéreo de 44,1 kHz en buffers fragmentados,
con retraso inicial y relleno final conocidos, durante dos transiciones. Comprueba:

- Recorte únicamente de las muestras indicadas por esos metadatos.
- Identidad byte por byte de todas las muestras restantes, incluidos bits que se
  perderían al cuantizar a PCM de 16 bits.
- Reutilización del mismo AudioTrack, salida float, frecuencia y canales intactos.

Esta prueba llena el buffer sin ponerlo a sonar. Verifica el procesamiento digital,
no la calidad acústica del altavoz. La prueba de reproducción del MP3 comprueba
320 kbps, 44,1 kHz, estéreo, velocidad y tono originales y checksum SHA-256 intacto.

## Resultados

```sh
ANDROID_HOME=/Users/gustavo/Library/Android/sdk ./gradlew assembleDebug lintDebug testDebugUnitTest connectedDebugAndroidTest
```

Compilación correcta; **43 pruebas unitarias y 16 de Android aprobadas**, sin fallos,
errores ni omitidas. Android Lint: 0 errores y 25 advertencias. Entorno: Pixel_10a,
Android 17/API 37. La prueba de comandos verifica que intentar desactivar el bucle,
mezclar, cambiar velocidad/tono o atenuar la ganancia desde el controlador no altera
los valores protegidos ni la reproducción.

Evidencia: `app/build/reports/loop-quality-v1.4/validation-log.txt`,
`validation-summary.json` y los informes estándar de pruebas bajo `app/build/reports/`.
APK: `app/build/outputs/apk/debug/app-debug.apk`, versión 1.4, código 5.

Después de actualizar el motor, otra prueba siguió 90 segundos de reproducción
con pantalla apagada, Doze profundo y excepción de batería temporal. La posición
avanzó; terminar la interfaz no terminó el audio y terminar el servicio permitió
su recuperación automática. La pausa posterior se conservó al volver a abrir.
Se restauraron los ajustes temporales y se retiró el audio sintético. La app quedó
abierta en el emulador; el APK instalado coincide con el compilado por SHA-256.
La evidencia está en `runtime-summary.json` y `playback-logcat.txt` del mismo directorio.

## Alcance

Se protege la fidelidad de lo que contiene el archivo: no se puede recuperar
información que su compresión MP3 ya descartó. La unión depende también de que los
metadatos de retraso/relleno sean correctos; los silencios que formen parte del audio
se conservan. No se certifican ausencia universal de clics, salida física bit-perfect,
Bluetooth ni 24 horas de sonido ininterrumpido en un teléfono real.
