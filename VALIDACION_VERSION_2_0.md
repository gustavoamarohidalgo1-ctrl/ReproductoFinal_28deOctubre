# Versión 2.0: restauración, biblioteca y continuidad

## Alcance

Versión 2.0, código 10. Conserva los refuerzos de las versiones anteriores y añade
mejoras de memoria, lectura del estado y actualización de bibliotecas grandes.
El APK local `benchmark` aplica R8 y reducción de recursos, está firmado con la
clave local de pruebas y no habilita depuración.

Esta revisión mantiene los bucles, Media3 1.6.1, el tamaño de los buffers, la salida
PCM flotante disponible, la velocidad y el tono en 1 y los archivos originales.
No recodifica ni aumenta artificialmente el bitrate. La calidad física sigue
dependiendo del archivo, Android, el dispositivo y la ruta de salida.

## Cambios de esta versión

### Restauración progresiva de la cola

`PlaybackStateStore` construye los `MediaItem` directamente desde `JsonReader`.
Ya no necesita retener primero el archivo completo, convertirlo en una cadena
completa y crear un árbol JSON de toda la cola. Conserva pequeños objetos para
el encabezado y los checkpoints, junto con los modelos finales que necesita
el reproductor.

Se mantienen los límites de 20.000 entradas y 16 MiB, los índices originales,
los tokens que vinculan cola y checkpoint y el punto de recuperación alternativo
en pausa. La lectura limita los bytes reales, incluso si el archivo cambia
durante la operación, y acota a 64 niveles los valores desconocidos anidados.
Las operaciones siguen serializadas fuera del hilo principal.

Las pruebas cubren claves reordenadas, claves repetidas, campos desconocidos,
filtrado de direcciones remotas, duraciones opcionales malformadas y límites
exactos con UTF-8 y espacios al final. Los números de control no se convierten
desde cadenas ni decimales. Los archivos estándar escritos por las versiones
anteriores siguen siendo compatibles; se rechazan extensiones JSON no estándar,
comentarios y documentos adicionales al final, en lugar de recurrir a una lectura
completa que volvería a incrementar la memoria.

### Biblioteca reutilizable y menos transferencias

Desde Android 11/API 30 se consultan la versión y generación de cada volumen
externo concreto. Si coinciden con las de la caché válida, se reutiliza el catálogo
sin consultar y reconstruir todas sus filas. Se comprueban reinicios de la base
de datos, cambios de volúmenes y cambios durante un escaneo. Cuando la validación
no está disponible o resulta inconsistente se realiza el escaneo habitual.
Las notificaciones de MediaStore, la actualización manual y los cambios de
permisos invalidan la reutilización. Los Android anteriores mantienen el escaneo.

La revisión de la cola ahora describe exactamente el orden y los campos enviados
al reproductor: ID, URI, título, artista, álbum y duración. Cambiar solo tamaño o
carpeta, manteniendo ese orden y esos campos, actualiza la interfaz sin obligar
a retransmitir toda la cola. Se conservan la selección y los marcadores.

### Marcadores acotados y tolerantes a datos incompatibles

La caché propia conserva las 256 entradas usadas más recientemente. Antes de
retirar una entrada escribe el progreso que tenía pendiente, y puede volver a
leerla cuando se necesite. No borra posiciones persistidas. Si el callback de
escritura falla, la entrada permanece disponible para reintentar.

Este límite no acota el mapa interno de SharedPreferences ni toda la memoria
del proceso. Los marcadores siguen usando `apply()`, cuya escritura a disco es
asíncrona; el checkpoint atómico de sesión mantiene su protección separada.
Un marcador cuyo tipo sea incompatible se descarta de forma individual, sin
derribar la selección del audio ni borrar las posiciones de otros archivos.
Se comprueban también posiciones posteriores a 25 horas y su conservación al
recorrer más de 256 archivos.

## Compilación y pruebas

- Compilaciones debug y optimizada correctas.
- **81 pruebas unitarias aprobadas**, sin fallos ni omisiones.
- Lint: **0 errores y 22 advertencias**.
- **59 casos Android verificados**, con dos pruebas optativas omitidas en la
  suite ordinaria. Incluyen 33 pruebas del almacén de reproducción, dos de
  MediaStore, dos de marcadores, reproducción y reinicios reales del proceso.
- La prueba PCM flotante conserva el recorte gapless y las muestras restantes;
  las pruebas del servicio comprueban el archivo de 320 kbps y sus parámetros.
  Esto no constituye una medición acústica del teléfono.
- **21 casos aprobados en el APK optimizado**, en una suite completa sin fallos
  (215,621 s); el caso optativo de duración se ejecuta por separado.

La primera suite debug tuvo un fallo de limpieza: intentaba borrar por segunda
vez la fila de MediaStore ya eliminada por la propia prueba. Se corrigió solo
ese cierre, conservando todas las comprobaciones funcionales y evitando ocultar
el error original con un error secundario. Los dos casos de MediaStore pasaron
en la reejecución focal. Se conserva tanto la ejecución inicial como la final;
el total anterior es el último resultado por caso, no una única ejecución
completa sin fallos de preparación o limpieza.

Comandos de validación, con `ANDROID_HOME` configurado y un emulador dedicado:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleBenchmark :app:assembleDebugAndroidTest :validation:assembleBenchmark
ANDROID_SERIAL=emulator-5590 ./gradlew :app:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5590 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.reproductor.audiolibros.AudioStoreGenerationInstrumentedTest
ANDROID_SERIAL=emulator-5590 ./gradlew :validation:connectedBenchmarkAndroidTest
```

Los resultados locales se conservan en `app/build/reports/validation-2.0/`.
Las suites que sustituyen la cola se ejecutan únicamente en el emulador temporal,
sin utilizar los archivos ni la cola del teléfono físico.

### Ensayo continuo del APK optimizado

`scripts/validate-continuity.sh emulator-5590 300 benchmark` completó
**300,001 segundos de reproducción observada**, con 24 repeticiones y ningún
error del motor registrado. Todas las muestras del informe indicaron reproducción
activa, y el checkpoint del servicio siguió actualizándose. El intervalo máximo
entre avances observados fue 1.076 ms con muestreo aproximado de un segundo;
esa cifra no mide pausas acústicas.

Unos 55 segundos después de comenzar la preparación se enviaron HOME y apagado
de pantalla. Android confirmó `mScreenState=OFF`, `mWakefulness=Asleep`, el servicio
en primer plano y el wake lock de ExoPlayer. El ensayo continuó otros 246,7 s
desde esa acción hasta el informe final. El arnés terminó con `OK (1 test)`.
Los JSON, comandos y capturas de estado están en `continuity/`, dentro de la
carpeta de evidencia. Se cerró únicamente el emulador temporal al finalizar.

## Medición comparada con 1.8

Se ejecutó la misma sonda de persistencia con metadatos sintéticos sobre los APK
debug 1.8 y 2.0, en el mismo emulador API 37. Cada proceso calentó 100 entradas
y tomó tres muestras de cada tamaño. Se repitió invirtiendo el orden de versiones:
1.8, 2.0, 2.0, 1.8. La tabla resume las seis muestras por tamaño y versión.

| Entradas | Lectura 1.8, mediana [mín.–máx.] ms | Lectura 2.0, mediana [mín.–máx.] ms | Asignaciones totales 1.8 → 2.0, mediana MB |
| ---: | ---: | ---: | ---: |
| 100 | 3,34 [2,00–11,40] | 2,25 [1,98–10,08] | 0,70 → 0,56 |
| 1.000 | 25,04 [15,56–187,66] | 14,26 [12,78–16,20] | 6,36 → 5,11 |
| 10.000 | 263,14 [125,60–727,33] | 113,55 [111,17–127,01] | 63,59 → 50,89 |
| 20.000 | 242,63 [196,88–573,46] | 161,26 [154,78–169,60] | 128,47 → 104,28 |

MB usa 1.000.000 bytes. La última columna suma las fases de creación de modelos,
escritura y lectura del contador `art.gc.bytes-allocated`: con 20.000 entradas,
la mediana disminuyó aproximadamente **18,8%**. Es una medida aproximada de
asignaciones acumuladas del proceso, no RAM máxima ni memoria retenida. ART
puede reflejar algunas asignaciones con retraso; sumar las fases reduce la
distorsión de atribuirlas exactamente a una lectura o escritura.

Esta sonda lee inmediatamente después de escribir, con la caché de archivos
caliente. No mide el tiempo de arranque hasta escuchar sonido ni rendimiento
del Xiaomi. La variación de los tiempos es visible en los rangos; incluso la
escritura, cuyo código no cambió en 2.0, varió entre ejecuciones. Los resultados
son exploratorios y no justifican prometer un factor fijo de aceleración.
Se verificaron cantidad de entradas, pista activa y posición, y los tamaños de
los JSON resultaron iguales entre versiones.

Los APK de comparación y todos los datos originales están en `baseline/`,
`persistence-performance-*.json` y `performance-*.log`, dentro de la carpeta de
evidencia. `performance-comparison-final.json` contiene medianas y rangos de
las dos rondas. `performance-comparison.json` conserva la primera comparación
parcial; no es la cifra final de esta tabla.

## APK e instalación en el teléfono

APK local optimizado: `app/build/outputs/apk/benchmark/Reproductor-2.0.apk`.
Tamaño: **2.936.267 bytes**. Firma verificada y depuración desactivada.
SHA-256: `28925e018749ba0bf9eb4a04d78361199a57532594eabca900d476df15fe9870`.

La instalación mediante `adb install -r` **se completó correctamente** en el
Xiaomi 24117RN76L con Android 16. Se verificaron `versionName=2.0` y
`versionCode=10`. Esta vez Android permitió la operación que había rechazado
en los intentos de 1.7 y 1.8. No se desinstaló la app ni se borraron sus datos.

También se dejó una copia en **Descargas/Reproductor-2.0.apk** del teléfono.
Su SHA-256 coincide con el APK local y el validado en el emulador. La evidencia
está en `apk.json`, `signature.txt` y `phone-install.json`, dentro de
`app/build/reports/validation-2.0/`.

La apertura devolvió `Status: ok` y mostró la solicitud de permisos de Android.
El Xiaomi rechazó concederlos desde el usuario shell de USB con
`GRANT_RUNTIME_PERMISSIONS`. En la comprobación final, **el acceso a audio ya
estaba concedido** desde el teléfono y las notificaciones permanecían desactivadas.
Se respetó esa configuración. La revisión final confirmó también **la excepción
de ahorro de batería de Android activa** para la app; no se modificó esa
configuración mediante comandos. `phone-launch.json` y `phone-final-check.json`
conservan los resultados.

Al concluir, los procesos estaban cerrados. El historial de Android identificó
las últimas salidas como `SwipeUpClean` al limpiar aplicaciones recientes, no
como excepciones de la aplicación. Se respetó ese cierre y no se volvió a abrir
la app. El registro se conserva en `phone-exit-info.txt`.

## Límite de la validación

No se ha completado una escucha física de 24 horas en el Xiaomi. La búsqueda
después de 25 horas usa un archivo sparse y las simulaciones unitarias emplean
un reloj virtual: ninguna equivale a escuchar durante un día. El ensayo continuo
registra avance y checkpoints; no mide sonido físico, autonomía o Bluetooth.
Se mantiene el script optativo `scripts/validate-continuity.sh` para un ensayo
real de 86.400 segundos en un dispositivo dedicado. Android, un cierre forzado,
el hardware o la pérdida de la salida de audio pueden interrumpir el sonido.
