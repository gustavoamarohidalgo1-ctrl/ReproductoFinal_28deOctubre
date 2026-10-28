# Refuerzo final — versión 1.5

19 de septiembre de 2026. Última revisión de continuidad, bucle y fidelidad del
audio, sobre la versión 1.4. El cambio se concentra en respetar las órdenes más
recientes del usuario durante una recuperación.

## Correcciones comprobadas

- Cambiar de pista durante un reintento invalida el destino anterior. Ahora se
  cubren también las órdenes por índice, siguiente/anterior, posición inicial y
  avance/retroceso, además del desplazamiento dentro de la pista.
- Detener cancela la recuperación y guarda la intención de parada. El vigilante
  no vuelve a activar el audio después de sus 20 segundos de espera.
- Las órdenes rápidas conservan su orden: detener y preparar mantiene la pausa;
  detener, preparar y reproducir permite volver a escuchar.

Las dos primeras regresiones fallaron con la implementación anterior, antes de
aplicar el cambio (`regression-before.txt`). La prueba de detener y reproducir
juntos detectó además un problema en la primera corrección. El ajuste definitivo
procesa la detención cuando se ejecuta, mediante un `ForwardingPlayer` expuesto
por la sesión, en lugar de aplicarla al final del conjunto de órdenes. La misma
protección se instala cuando se reconstruye el motor. La detención interna que
usa la recuperación conserva su comportamiento.

Son operaciones que el controlador de la propia app admite; estas pruebas no
afirman que todos esos controles estén expuestos actualmente en la pantalla.
Referencias del motor: [ForwardingPlayer de Media3 1.6.1](https://github.com/androidx/media/blob/1.6.1/libraries/common/src/main/java/androidx/media3/common/ForwardingPlayer.java)
y [cola de órdenes de la sesión](https://github.com/androidx/media/blob/1.6.1/libraries/session/src/main/java/androidx/media3/session/ConnectedControllersManager.java).

## Bucle y calidad

Se conserva la repetición automática de la pista seleccionada, sin mezcla ni pausa
al final. Los archivos originales de 320 kbps no se recodifican. Velocidad y tono
permanecen en 1, la ganancia digital en 1 y el salto de silencios desactivado.
Se mantienen Media3 1.6.1, el procesamiento PCM flotante y el recorte gapless de
metadatos. Las pruebas vuelven a comprobar el formato del MP3, su SHA-256, bucles
completos y de 800 ms, y las muestras flotantes byte por byte.

La revisión de batería de la versión anterior sigue vigente: el aviso desaparece
cuando Android confirma la excepción real y vuelve a mostrarse si la revoca.

## Validación

```sh
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=/Users/gustavo/Library/Android/sdk ./gradlew assembleDebug lintDebug testDebugUnitTest connectedDebugAndroidTest
```

Compilación correcta; **43 pruebas unitarias y 20 de Android aprobadas**: 63 en
total, sin fallos, errores ni omitidas. Android Lint: 0 errores y 25 advertencias.
Emulador Pixel_10a, Android 17/API 37. Registro: `final-validation-log.txt`;
recuentos y hash: `validation-summary.json`.

APK `app/build/outputs/apk/debug/app-debug.apk`, versión **1.5**, código **6**.
SHA-256: `f160c3293b3f1995cd50e4fb8fd4a7ebc43a0bce96a6261f62ac917486a4bc87`.
La versión 1.5 quedó instalada y abierta en el emulador; el hash del APK instalado
coincide con el compilado. Se verificó que no quedó el archivo privado de audio
de las pruebas. La pantalla final está en `emulator-final.png`.

Evidencia de esta pasada: `app/build/reports/final-v1.5/`. La ejecución final se
limita al emulador para no incluir otros teléfonos conectados. Los resultados
intermedios fallidos se conservan por separado como evidencia de las regresiones.
El audio sintético utilizado por las pruebas se guarda en el entorno de prueba.

## Alcance

Las pruebas de esta versión comprueban los cambios de órdenes y vuelven a ejecutar
las comprobaciones de recuperación, persistencia y audio. La prueba previa con
pantalla apagada, Doze y reinicio de procesos está documentada en
[la validación de 1.4](VALIDACION_BUCLE_CALIDAD_1_4.md). No se ha realizado una nueva
escucha física de 24 horas. Android, el hardware, los archivos y la salida Bluetooth
siguen determinando límites que la aplicación no puede eliminar.
