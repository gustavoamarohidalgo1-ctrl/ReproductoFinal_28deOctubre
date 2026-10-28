# Rendimiento y continuidad — versión 1.6

## Cambios aplicados

La prioridad es conservar el audio original y reducir trabajo ajeno a su reproducción.
Se mantienen Media3 1.6.1, repetición de la pista seleccionada, PCM flotante cuando la
ruta lo permite, velocidad/tono/ganancia originales, silencios originales, buffers,
servicio en primer plano, proceso de audio separado, wake lock y recuperación de fallos.
No se activó offload sin validarlo en los dispositivos de destino.

- **Biblioteca**: caché inmutable reutilizada entre recreaciones de pantalla, índices
  por ID, construcción de MediaItems y comparación/diff fuera del hilo principal.
  Los escaneos cancelados no publican sobre uno más reciente; se comprueba la
  biblioteca al volver a la app y tras avisos de MediaStore. Se conservan audios de
  duración todavía desconocida.
- **Interfaz**: cambios de selección actualizan las filas afectadas; restauración
  del scroll diferida hasta tener datos; los diffs no calculan movimientos costosos.
  El tamaño de archivos con metadatos incompletos se consulta solo para la pista activa.
- **Cambio de pista**: revisión determinista de biblioteca y comprobación del índice/ID
  permiten usar `seekTo(index, TIME_UNSET)` sobre la cola existente. El servicio traduce
  la posición indefinida al marcador del archivo. Se conserva el orden de los comandos
  nativos y el masking de Media3 ante toques rápidos y pausas. No hay protocolo custom
  paralelo ni reproducción tardía desde callbacks de confirmación.
- **Persistencia**: cada entrada se codifica una sola vez; se guardan sus bytes UTF-8
  y se escriben por bloques. No se crea un segundo árbol JSON de toda la cola ni una
  cadena gigante final. Sigue el formato v1, límite exacto de 16 MiB/20.000 entradas,
  prioridad de la pista activa, orden original y token de escritura.
- **Cola vacía**: un registro atómico `{"version":1,"cleared":true}` impide que un
  checkpoint antiguo restaure un audio eliminado. Se usa el mismo escritor serial,
  con reintento ante fallo, sin carreras entre borrado y guardado pendiente.
- **Archivos largos**: escala de progreso basada en Long, con precisión de milisegundos
  en duraciones normales y acceso al final para duraciones superiores a Int.MAX_VALUE ms.
  El indicador reconoce PCM de 24 bits si el formato lo declara; float no se presenta
  como prueba de profundidad original.
- **Compilación**: versión 1.6/código7; R8 y reducción de recursos en release. Variante
  benchmark con las mismas optimizaciones y firma local de pruebas para validar el APK.

Se conserva el checkpoint de sesión cada dos segundos durante reproducción y el
registro inmediato de pausa/cambio de pista. No se sacrificó esa frecuencia para
obtener cifras de escritura menores. Una recuperación puede producir una interrupción;
la app no puede garantizar continuidad física ante cualquier fallo del sistema/salida.

## Reproducción de las comprobaciones

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug assembleBenchmark assembleRelease
ANDROID_SERIAL=emulator-5584 ./gradlew connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5584 ./gradlew :validation:connectedBenchmarkAndroidTest
./scripts/validate-continuity.sh emulator-5584 180 benchmark
```

La suite optimizada usa el módulo independiente `validation`. Su controlador
se comunica por IPC con el APK minificado real. El runner incluye dependencias
propias y un adaptador de manifiesto documentado para AGP8.7.3, comprobado con aapt2.
No se agregaron reglas amplias de keep ni se relajaron permisos del servicio para
hacer pasar las pruebas.

El emulador de esta sesión es Pixel_10a, Android API37, ejecución temporal `-read-only`,
`-no-snapshot`, `-no-audio`. No se instaló la app ni se cambió la configuración de un
aparato físico del usuario. Las mediciones de posición y formato no equivalen a medir
el sonido físico; el emulador deliberadamente no emite audio.

La prueba WAV crea un archivo sparse PCM16/8kHz/mono con duración de 27 horas y busca
más allá de 25 horas. Comprueba duración, reproducción y checkpoint pausado sin
reservar gigabytes físicos; después elimina la muestra. No simula 27 horas de escucha.

La prueba optativa de continuidad admite hasta siete días de tiempo real. Mantiene
un informe JSON con duración real, avance, repeticiones, errores, estados y frescura
del checkpoint guardado por el proceso remoto. Se exige progreso observado antes
de 30 segundos y actualización del checkpoint antes de 60 segundos. El script
selecciona explícitamente dispositivo y comprueba tanto el informe como el resultado
JUnit. Para 24 horas reales, usa `86400` y el equipo/salida/audio de destino.

La medición optativa de almacenamiento se ejecuta con:

```bash
adb -s emulator-5584 shell am instrument -w -r \
  -e class com.reproductor.audiolibros.PersistencePerformanceInstrumentedTest \
  -e measurePersistence true \
  com.reproductor.audiolibros.test/androidx.test.runner.AndroidJUnitRunner
```

Su informe queda en `externalFiles/validation/persistence-performance.json`. Usa
100/1.000/10.000/20.000 entradas sintéticas, una preparación de 100 entradas y tres
muestras por tamaño. Las asignaciones ART son acumuladas en el proceso, no memoria
retenida ni pico de RAM. Los tiempos del emulador no permiten prometer el mismo
resultado en teléfonos, medir consumo o estimar latencias extremas de disco.

## Resultados de esta sesión

- 54 pruebas unitarias correctas; lint sin errores (22 advertencias).
- Suite Android debug: 41 pruebas correctas y 2 optativas omitidas (178,2 s de ejecución).
- Suite contra APK optimizado: 20 pruebas correctas y 1 optativa omitida (187,3 s).
  Incluye las tres pruebas nuevas de muerte real del proceso. En la recuperación
  sticky observada, PID 10380 → 11024, checkpoint 2.864 ms → posición restaurada
  2.876 ms; el controlador nuevo no envió play/prepare. También se conservaron
  pausa y cola vacía tras otras muertes de proceso.
- Ensayo sostenido contra el APK optimizado: **180.002 ms**, 14 repeticiones observadas,
  cero errores y ningún cambio a IDLE/ENDED. Checkpoints continuaron avanzando. Se envió
  HOME y se apagó la pantalla aproximadamente a los 33 s; el resto del ensayo ocurrió
  con la interfaz en segundo plano. Dumpsys confirmó el wake lock de ExoPlayer durante
  la reproducción y cero wake locks al finalizar la limpieza. El máximo intervalo entre
  observaciones de progreso fue 1.034 ms con muestreo de 1 s: no mide huecos audibles.
- El script de continuidad se ejecutó de principio a fin y comprobó informe + JUnit.
  Evidencia: `app/build/reports/continuity-20260922-183323/` y copia de JSON/log en
  `app/build/reports/validation-1.6/`. **No se ejecutó una escucha física de 24 horas**.
- Apertura visual del APK optimizado y reproducción desde la biblioteca comprobadas.
  Captura y jerarquías UI guardadas; indicador de 320 kbps y selección correctos.
  El hash del APK antes y después de preparar el arnés independiente coincide.
- APK release sin firma: 7.423.938 → 2.455.219 bytes (7,08 → 2,34 MiB), **66,93 % menor**.
  Comparación con el release sin optimizar de la investigación previa. La variante
  benchmark firmada ocupa 2.930.343 bytes; su empaquetado difiere del release sin firma.
- El ensayo de almacenamiento debug terminó correctamente. Medianas de tres muestras:

| Entradas | Cola + checkpoint antes → ahora | Asignaciones ART antes → ahora |
|---:|---:|---:|
| 100 | 3,59 → 2,91 ms | 0,44 → 0,25 MiB |
| 1.000 | 20,11 → 14,00 ms | 3,73 → 2,41 MiB |
| 10.000 | 179,07 → 131,74 ms | 36,39 → 24,09 MiB |
| 20.000 | 292,08 → 193,83 ms | 76,06 → 48,84 MiB |

En 20.000 entradas, la mediana de guardado disminuyó 33,6 % y las asignaciones
acumuladas 35,8 %. Son builds debug, mismo trabajo sintético y AVD API37, con arranques
diferentes. No es una reducción demostrada de RAM máxima ni de consumo de batería.
La lectura no mostró aceleración consistente (variación aproximada de −4,1 % a +4,3 %).

Evidencia local bajo `app/build/reports/validation-1.6/`: `build.log`,
`static-validation.json`, `debug-instrumented.log`, `apk-comparison.json`,
`persistence-performance-debug.json` y `persistence-performance-comparison.json`.
Además: `optimized-blackbox.log`, `process-restart.log`, `validation-module-build.log`,
`ui-playing.png` y las jerarquías UI. Resultados optimizados en
`validation/build/outputs/androidTest-results/connected/benchmark/`; debug en
`app/build/outputs/androidTest-results/connected/debug/`.
