# Continuidad y calidad de audio — versión 1.2

> Registro de la versión anterior. El refuerzo posterior está en
> [VALIDACION_CONTINUIDAD_1_3.md](VALIDACION_CONTINUIDAD_1_3.md).

Validación local del 19 de septiembre de 2026. Esta versión refuerza la recuperación
y reduce trabajo innecesario durante la reproducción. No convierte ni modifica los
archivos originales de 320 kbps y no añade normalización, ecualización ni un límite
de bitrate. El indicador describe el archivo; no certifica la salida física del
teléfono ni el códec de Bluetooth.

## Cambios

- Reserva de hasta 120 segundos de audio original y 10 segundos anteriores para
  pequeños desplazamientos. Se conserva también el límite de memoria del motor.
- Vigilancia del avance real: alternar entre preparado y cargando no oculta un
  bloqueo. Reconoce las repeticiones cortas y exige estabilidad antes de reiniciar
  el contador de fallos.
- Reconstrucción del reproductor ante errores de salida de audio o bloqueos
  persistentes, conservando la sesión, pista, posición, volumen y velocidad.
- Una pausa, selección o desplazamiento del usuario invalida la recuperación
  pendiente. El avance real también cancela un reintento que ya no hace falta.
- Menos escrituras de marcadores por archivo: se agrupan cada 30 segundos y se
  guardan al pausar, desplazarse o cambiar de pista. La sesión conserva su punto
  de recuperación cada dos segundos mientras reproduce.
- Persistencia limitada a una ventana de hasta 20.000 pistas y 16 MiB que incluye
  la pista activa, aunque su índice supere 20.000. Un identificador une cada
  checkpoint a la ventana correcta; los fallos de escritura permiten reintentar.
- Tocar la pista activa ya no reinicia el motor. La biblioteca se consulta fuera
  del hilo principal, cancela trabajo obsoleto y observa archivos nuevos o retirados.
- La barra de posición responde al teclado y a accesibilidad. Hay recuperación
  visible de errores de permisos y un botón para revisar restricciones de batería.
- Los marcadores locales y el historial de solicitud de permisos se excluyen de
  las copias y transferencias a otro dispositivo.

## Pruebas

Comando de verificación:

```sh
ANDROID_HOME=/Users/gustavo/Library/Android/sdk ./gradlew assembleDebug lintDebug testDebugUnitTest connectedDebugAndroidTest
```

| Comprobación | Resultado |
| --- | --- |
| Compilación debug | Correcta; versión 1.2, código 3. |
| Android Lint | 0 errores y 25 advertencias; informe conservado para revisión. |
| Pruebas unitarias | 34 correctas: política de recuperación, vigilancia y marcadores. |
| Pruebas Android | 12 correctas: persistencia y servicio real en Pixel_10a, Android 17/API 37. |
| Calidad del archivo de prueba | MP3 de 320 kbps, 44,1 kHz, estéreo, velocidad 1; SHA-256 idéntico antes y después. |
| Repetición | Tres vueltas naturales sin estado IDLE/ENDED ni error del reproductor. |
| Archivo inaccesible | Recuperación hacia la siguiente pista reproducible. |
| Pausa explícita | Cancela la recuperación; un desplazamiento en pausa no inicia reproducción. |
| Pantalla apagada | Seguimiento durante 90 segundos, con puntos de recuperación y avance de posición. |
| Terminación de interfaz | El proceso de reproducción sobrevive y sigue avanzando. |
| Terminación del servicio | Android recrea el servicio y se recupera la reproducción desde el checkpoint. |
| Apertura posterior a pausa | Restaura la posición en pausa. |
| Segunda pulsación de la pista | Conserva la generación de la cola y continúa avanzando. |
| Teclado | Desplaza aproximadamente 10 segundos, con redondeo a cuadros MP3. |

Evidencia local:

- `app/build/reports/continuity/final-checks-log.txt`
- `app/build/reports/continuity/validation-summary.json` (resultados y SHA-256 del APK)
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/reports/lint-results-debug.html`
- `app/build/reports/continuity/runtime-summary.json`
- `app/build/reports/continuity/runtime-logcat.txt`
- `app/build/reports/continuity/runtime-buffer.txt`

El MP3 de integración es silencio sintético generado a 320 kbps; no se utilizaron
audios privados. La prueba de formato y checksum verifica que no se recodifica el
archivo; no mide fidelidad acústica, silencios físicos, consumo o latencia del DAC.

## Alcance

Una prueba unitaria simula 24 horas mediante un reloj virtual. No se ha realizado
una prueba física de reproducción y batería de 24 horas. La recuperación tras matar
el proceso puede incluir un corte breve: confirma recuperación, no continuidad
acústica durante la muerte del proceso. Tampoco se han inyectado fallos físicos de
AudioTrack ni verificado todos los fabricantes o dispositivos Bluetooth.

Android, el hardware, un apagado, un archivo dañado o el cierre forzado por el usuario
pueden interrumpir el sonido. Para el uso prolongado, la app ofrece el botón
**Evitar restricciones de batería**; la excepción se solicita expresamente desde
ese botón. La app sigue respetando la pausa y el cierre forzado del usuario.

El APK local está en `app/build/outputs/apk/debug/app-debug.apk`. Esta validación
complementa la auditoría histórica `ANALISIS_2026-09-19.md`, realizada antes de
estos cambios.

La versión 1.2 quedó instalada y abierta en el emulador Pixel_10a. Al terminar se
retiraron únicamente el audio sintético, sus marcadores y su cola de prueba.
