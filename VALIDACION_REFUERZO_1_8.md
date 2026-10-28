# Último refuerzo — versión 1.8

## Alcance

Esta revisión conserva los bucles, los archivos originales, la velocidad y el tono,
los buffers, el proceso de reproducción separado y la política de recuperación.
Se centra en eliminar trabajo innecesario y reconocer correctamente los fallos de
persistencia. Versión 1.8, código 9; APK local optimizado mediante R8 y reducción
de recursos, firmado con la clave local de pruebas.

## Memoria después de restaurar

El servicio guardaba un `Snapshot` completo durante toda su vida. Aunque después
se seleccionara una cola distinta o se vaciara, esa referencia mantenía accesibles
los objetos de la cola original, hasta 20.000 entradas persistidas. Ahora conserva
sólo la intención de reproducción y el identificador de arranque que necesita
para decidir si puede reanudar después de un reinicio del sistema. El reproductor
sigue siendo propietario de la cola activa; no se ha limitado su tamaño.

## Confirmación del guardado

La inspección del SDK y una prueba con el `AtomicFile` real de Android confirmaron
que un fallo al reemplazar el destino podía quedar registrado únicamente en el
log, mientras `PlaybackStateStore` devolvía éxito. Esto podía dar por confirmado
un token de cola que en realidad no se había escrito.

La corrección verifica la identidad y tamaño del archivo que vuelve a abrir
`AtomicFile` después de confirmar la escritura. No lee ni vuelve a codificar su
contenido. Un fallo se devuelve al escritor para que conserve el estado confirmado
y aplique la política de reintentos. No añade una segunda sincronización del disco.

Esta comprobación detecta que la sustitución no se aplicó; no demuestra que el
hardware haya hecho durable la escritura si el propio sistema oculta un fallo de
sincronización. El acceso continúa serializado en el proceso de reproducción,
como requiere la [documentación de AtomicFile](https://developer.android.com/reference/android/util/AtomicFile).

## Bibliotecas con reordenaciones masivas

La comparación incremental de listas podía hacer cientos de millones de
comparaciones al invertir una biblioteca de 20.000 entradas. Aunque ocurría fuera
del hilo principal, ocupaba el mismo trabajador que consulta la biblioteca y el
tamaño del archivo activo.

`LibraryDiff` permite hasta 1.000.000 de comparaciones por cálculo. Los cambios
pequeños siguen siendo incrementales; al agotarse el presupuesto se actualiza
la lista completa, con IDs estables y selección conservada. La pantalla conserva
el primer archivo visible y su desplazamiento cuando ese archivo sigue existiendo.
La cancelación prevalece sobre el refresco completo y evita publicar un trabajo
obsoleto. Este límite acota las comparaciones, no todo el tiempo de consulta ni
el número de archivos de la biblioteca.

## Resultados e instalación

- Compilaciones debug y optimizada correctas; firma del APK verificada.
- **67 pruebas unitarias correctas**. Las cuatro nuevas usan `DiffUtil` real:
  inversión de 20.000 filas con trabajo acotado, inserción incremental y cancelación
  durante el cálculo y al completar el presupuesto de comparaciones.
- Lint: **cero errores**, 22 advertencias.
- **26 pruebas de persistencia Android correctas** en un emulador temporal API 37.
  Las tres nuevas bloquean el reemplazo del destino y comprueban cola, checkpoint
  y borrado. Después de retirar el bloqueo verifican que guardar vuelve a funcionar.
  La prueba de cola fallaba antes del arreglo en `assertFalse(writeQueue(...))`;
  la evidencia anterior y posterior se conserva por separado.

Comandos ejecutados:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleBenchmark :app:assembleDebugAndroidTest :validation:assembleBenchmark
ANDROID_SERIAL=emulator-5588 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.reproductor.audiolibros.PlaybackStateStoreInstrumentedTest
ANDROID_SERIAL=emulator-5588 ./gradlew :validation:connectedBenchmarkAndroidTest
ANDROID_SERIAL=emulator-5588 ./gradlew :app:lintDebug :app:assembleDebugAndroidTest \
  :validation:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.reproductor.audiolibros.ServiceRestartInstrumentedTest
```

**APK optimizado: los 21 casos de integración quedaron verificados**: 18 de
reproducción en la suite completa y tres de reinicio en una ejecución focal final
correcta de 23,222 s. El APK de producción mantuvo exactamente el mismo SHA-256.
Una prueba optativa de duración real se omitió.

Se conservan los fallos del arnés y sus correcciones: la primera ejecución dejó
tres fallos preparando el reinicio porque el diálogo de permisos impedía llegar
a `RESUMED`. Tras preparar audio/notificaciones, pasaron 20 casos y falló una
preparación al reutilizar la actividad detenida del caso anterior. Ambos fallos
ocurrieron antes de terminar el proceso bajo prueba. Cada caso de reinicio ahora
abre una actividad nueva con `NEW_TASK | CLEAR_TASK`, sin borrar datos ni modificar
el servicio. La reejecución final de los tres casos pasó: reinicio automático,
marcador pausado y cola vacía. La evidencia incluye las ejecuciones fallidas y la
final; no se afirma que hubo una única suite completa sin fallos de preparación.

No se ejecutaron suites que sustituyen la cola en el teléfono físico del usuario.

APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`, **2.933.179 bytes**.
SHA-256: `08be012752894d7ce5e4b939686e58c05743ab42f46c39ff6e24d7b2ae103975`.

La instalación en el Xiaomi 24117RN76L (Android 16) se intentó con `adb install -r`
y fue rechazada con `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`.
La app no quedó instalada. El APK final está copiado en
**Descargas/Reproductor-1.8.apk** del teléfono; se verificó que su SHA-256 coincide.
Hace falta habilitar la instalación por USB o abrir ese APK y completar la
instalación en el teléfono.

Evidencia local en `app/build/reports/validation-1.8/`: `build.log`,
`static-validation.json`, `apk.json`, `signature.txt`, `storage/` y las carpetas
`atomic-rename-before/` y `atomic-rename-after/`. Los resultados del APK optimizado
están en `optimized/results.json` (último resultado por caso),
`optimized-permission-precondition-failure/`, `optimized-activity-precondition-failure/`
y `optimized-restarts-final/`. `phone-install.json` registra el bloqueo del teléfono.

No se ha completado una escucha física de 24 horas ni una medición acústica de la
salida del Xiaomi. Los resultados anteriores se mantienen en
[VALIDACION_REFUERZO_1_7.md](VALIDACION_REFUERZO_1_7.md) y no se presentan como
mediciones nuevas de esta revisión.
