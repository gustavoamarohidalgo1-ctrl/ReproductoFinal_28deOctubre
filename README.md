# Reproductor de Audiolibros (Android)

Versión actual: **2.1.0**. [Descargar APK](https://github.com/gustavoamarohidalgo1-ctrl/Reproductor_28_Octubre/releases/download/v2.1.0/Reproductor-2.1.0.apk)
· [Publicación y archivos de validación](https://github.com/gustavoamarohidalgo1-ctrl/Reproductor_28_Octubre/releases/tag/v2.1.0).
La recuperación automática tras reinicio se verificó en un emulador Android 16;
la comprobación en el Redmi Note 14 4G del usuario sigue pendiente.

App nativa Android para reproducir audiolibros y música de forma prolongada,
con controles de reproducción exclusivos dentro de la aplicación:

- **Reproducción continua**: servicio en primer plano
  (`MediaSessionService`) + `WAKE_MODE_LOCAL` (mantiene el CPU despierto con la
  pantalla apagada) + notificación informativa sin botones multimedia.
- **Bucle protegido**: el audio seleccionado se repite automáticamente. La sesión
  no permite cambiar la repetición ni activar mezcla aleatoria; el motor reafirma
  estos ajustes también al recuperarse. La pausa manual continúa funcionando.
- **Motor aislado**: el servicio se ejecuta en un proceso independiente de la
  interfaz, para que un fallo de la pantalla no termine también el audio.
- **Recuperación**: conserva la cola y el punto de escucha, reintenta errores,
  detecta cuando la posición deja de avanzar y pasa al siguiente archivo si
  el actual no puede reproducirse.
- **Reanudación al encender (opcional)**: en teléfonos que arrancan sin PIN,
  patrón ni contraseña, intenta recuperar automáticamente la última reproducción
  y su volumen multimedia. Se activa una vez con **Reanudar al encender** y respeta
  una pausa previa. Necesita que el sistema entregue el aviso de inicio y permita
  el servicio; el fabricante puede imponer requisitos adicionales de autoarranque.
- **Mayor reserva de audio**: precarga hasta 120 segundos de muestras originales,
  con un umbral adicional de datos almacenados (no es un límite total de RAM). A 320 kbps, 120 segundos son aproximadamente
  4,8 MB de audio comprimido. No convierte archivos ni reduce su bitrate.
- **Reserva de salida frente a cargas breves**: el búfer PCM de `AudioTrack` solicita
  un objetivo de 750 a 1.500 ms, frente a los 250 a 750 ms predeterminados de Media3
  1.6.1. Es distinto de la precarga de archivos: conserva más audio ya decodificado
  para tolerar retrasos breves al abrir otras apps. El mínimo de la plataforma puede
  exigir una capacidad mayor. No cambia las muestras ni garantiza que el búfer esté
  siempre lleno.
- **Trabajo secundario de menor prioridad**: las consultas, comparación de biblioteca
  y metadatos opcionales usan un trabajador con prioridad de fondo. Media3 conserva
  su hilo de reproducción con prioridad de audio; la restauración y el guardado
  urgente no se trasladan a ese trabajador.
- **Motor recuperable**: si la salida de audio falla o un bloqueo persiste después
  del primer intento, reconstruye el reproductor conservando pista, posición y sesión.
- **Pausa desde la aplicación**: la pantalla de bloqueo, la notificación,
  los auriculares, Bluetooth y otras aplicaciones no pueden enviar órdenes
  para pausar, detener o cambiar la reproducción.
- **Formatos locales**: MP3, AAC, FLAC, WAV y OGG mediante Media3 ExoPlayer.

## Funciones

- Lista todos los audios del dispositivo (memoria interna y SD) vía MediaStore.
- **Indicador de calidad**: muestra el bitrate del archivo en reproducción
  (ej. "320 kbps") junto al título; en archivos sin pérdida muestra las
  especificaciones nativas (ej. "96 kHz").
- **Salida de audio**: salida PCM flotante habilitada cuando está disponible
  (`setEnableAudioFloatOutput`). La calidad final depende del dispositivo y de
  su ruta de audio; el indicador describe el archivo, no la salida física.
  Media3 1.6.1 aplica también el recorte del relleno de codificación en PCM flotante,
  conservando la precisión de las muestras al unir vueltas con metadatos gapless.
- **Memoria de posición por archivo**: cada audiolibro continúa donde lo dejaste.
- Tocar de nuevo el audio que está sonando conserva la reproducción sin volver
  a cargar la cola. La biblioteca se actualiza al añadir o retirar archivos.
- La biblioteca conserva una caché inmutable entre recreaciones; compara los cambios
  fuera del hilo principal y solo actualiza las filas necesarias. Seleccionar otra
  pista de la misma biblioteca usa un comando nativo pequeño, conserva el marcador
  y evita transferir nuevamente todos los archivos al servicio. Los empates de
  carpeta y título se resuelven por ID, para mantener un orden estable entre consultas.
- Desde Android 11 / API 30, la versión y generación de MediaStore de cada volumen
  permiten reutilizar un catálogo válido sin volver a recorrer todos sus archivos.
  Los cambios, avisos del proveedor y refrescos manuales invalidan esa comprobación;
  si el proveedor no la admite o devuelve un estado inconsistente, se realiza un
  escaneo completo. Los Android anteriores conservan el escaneo habitual.
- La revisión de la cola incluye únicamente los campos enviados al reproductor.
  Cambiar el tamaño o carpeta de un archivo no obliga a retransmitir la biblioteca
  si su orden y sus datos de reproducción siguen iguales.
- Las reordenaciones masivas tienen un límite de un millón de comparaciones. Si
  se supera, la lista se refresca completa conservando la selección y el anclaje
  visible, para no monopolizar el trabajador de biblioteca con un diff costoso.
- La barra de posición admite controles táctiles, teclado y accesibilidad, también
  para archivos cuya duración supera el rango de milisegundos de un entero de 32 bits.
- Los tiempos se formatean con operaciones enteras y cada etiqueta conserva su
  último segundo visible, evitando reconstruir el mismo texto al pausar o recibir
  eventos repetidos. Las búsquedas y los cambios de pista actualizan esa misma caché.
- El tamaño de archivos sin metadatos se consulta únicamente al necesitar el
  indicador de la pista activa; no se abre cada archivo al cargar la biblioteca.
- Al volver a abrir la app en pausa, muestra el tiempo guardado y la barra de
  progreso sin necesitar reproducir el audio. La duración también se guarda con
  la cola para mostrarla aunque la biblioteca no esté disponible. Durante la
  restauración inicial muestra un indicador de carga, en vez de un falso 0:00.
- La notificación abre la aplicación para acceder al botón de reproducción/pausa.

## Recuperación de fallos

En condiciones normales, el servicio guarda un punto de recuperación cada dos
segundos mientras se reproduce, además de guardar al pausar o cambiar de pista. La cola solo se
reescribe cuando cambia o debe actualizarse su ventana de recuperación. Las escrituras se
hacen fuera del hilo principal. La cola se codifica una sola vez por entrada y se
escribe por bloques, evitando una segunda copia completa del JSON. Vaciarla deja
un registro atómico persistente que impide recuperar una cola anterior. El escritor admite una tarea activa y únicamente
el último estado pendiente, para no acumular posiciones antiguas si el disco
se ralentiza. Cada estado lleva la cola que necesita y una pausa reciente reemplaza
las actualizaciones pendientes. Las escrituras usan `AtomicFile` para evitar archivos escritos
a medias. Antes de dar un guardado por confirmado se verifica la identidad y el
tamaño del archivo que vuelve a abrir `AtomicFile`, sin leer todo su contenido;
un reemplazo fallido activa los reintentos. Las instancias consecutivas del servicio comparten un único executor
para que cerrar y reabrir rápidamente no solape lecturas y escrituras. La cola
incluye además un punto de recuperación en su mismo archivo: si el checkpoint
reciente falta o está dañado, se recupera ese punto anterior siempre en pausa.
La restauración lee la cola progresivamente, sin construir un árbol JSON completo,
para reducir las copias y objetos necesarios al recuperar bibliotecas grandes.
Las bibliotecas que exceden los límites de persistencia conservan una ventana de
hasta 20.000 entradas y 16 MiB que siempre incluye la pista activa; la cola que
está reproduciéndose no se recorta. Un identificador por escritura evita mezclar
checkpoints de ventanas diferentes. Los marcadores por archivo se mantienen en
memoria y se escriben cada 30 segundos, además de hacerlo inmediatamente al
pausar, desplazarse o cambiar de pista. El checkpoint de la sesión conserva su
intervalo de dos segundos mientras el guardado funciona. Los marcadores con IDs locales se excluyen del backup
y de la transferencia a otro dispositivo.

La caché propia de marcadores conserva hasta 256 entradas recientes. Antes de
retirar una entrada vuelca su progreso pendiente, sin borrar el marcador guardado;
si vuelve a necesitarse, lo lee otra vez. Este límite corresponde solo a esa caché,
no al contenido de SharedPreferences ni a la RAM total. Un marcador con un tipo
incompatible se descarta individualmente para que no impida seleccionar el archivo
ni afecte las posiciones de otros audios.

Si el almacenamiento impide guardar repetidamente, los intentos del mismo estado
de sesión esperan progresivamente 2, 4, 8, 16 y hasta 30 segundos. Esa espera se
aplica mientras se mantienen la misma generación de cola, índice de pista e
intención de reproducir; no limita todas las escrituras de la aplicación. Una pausa
o búsqueda explícita tiene un intento prioritario, sin esperar ese plazo. Si también
falla, las siguientes actualizaciones periódicas vuelven a respetar la espera.
Esto evita repetir trabajo de disco innecesario sin detener el audio. Tras un
guardado correcto se restablece el ritmo habitual de dos segundos.
Si no se puede abrir el archivo temporal del guardado, la operación falla antes
de codificar la cola completa. Una validación fallida después de abrirlo revierte
la escritura y conserva el estado confirmado anterior.

Si Android reinicia el servicio mediante `START_STICKY`, puede retomar
el último punto guardado en el mismo arranque del dispositivo. Durante ese reinicio,
promueve inmediatamente el servicio y mantiene despierto el procesador mientras
lee el estado, sin esperar a que termine la lectura de la cola. Abrir normalmente
la aplicación restaura la cola en pausa; una pausa manual se conserva.
Después de restaurar, el servicio conserva únicamente la intención necesaria para
el reinicio, sin retener una copia de la cola antigua cuando se cambia la biblioteca.

Desde 2.1.0, **Reanudar al encender** permite solicitar recuperación automática
tras un reinicio del teléfono. Está desactivada inicialmente. Solo reproduce si
el último checkpoint confirmado indicaba reproducción, pertenece a un arranque
anterior conocido y no hay una orden posterior del usuario que lo impida. Recupera
el último punto guardado, no un instante que no hubiera llegado a guardarse antes
del apagado. Una pausa, una cola vacía o un estado incompatible no activan el audio.

El receptor privado utiliza `LOCKED_BOOT_COMPLETED` y el tipo de servicio real
`mediaPlayback`. Android 16 distingue ese evento temprano de `BOOT_COMPLETED`;
el inicio directo desde este último está restringido para target 35. Esta diferencia
se comprobó con reinicios reales en un emulador API 36: el evento temprano inició
audio y el control con `BOOT_COMPLETED` produjo la excepción esperada. Véanse el
[código de Android 16](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/am/ActiveServices.java)
y las [restricciones documentadas](https://developer.android.com/about/versions/15/changes/foreground-service-types#media-playback).
La entrega del evento y la política del fabricante deben verificarse en el teléfono;
el resultado del emulador no acredita el Redmi ni todas las actualizaciones futuras.

La opción se guarda atómicamente en almacenamiento disponible durante Direct Boot;
la cola, los marcadores y los archivos siguen en su almacenamiento habitual. El
receptor descarta equipos con bloqueo mediante credenciales. Si un teléfono sin PIN
todavía está desbloqueando el almacenamiento, el servicio prepara la recuperación
durante un máximo de 30 segundos y espera al aviso de desbloqueo antes de leerlo.
No se copian audiolibros a almacenamiento previo al desbloqueo. Al empezar a
reproducir conserva el reinicio sticky habitual ante muerte del proceso.

Cuando la opción está activa, el checkpoint incluye el índice de volumen multimedia.
Al recuperar una sesión tras reinicio lo aplica a la salida integrada del teléfono,
acotado al rango vigente; un volumen guardado en cero conserva el silencio. Un
campo ausente o inválido no descarta el punto de escucha y no cambia el volumen.
Desde API 33 consulta la ruta prevista para los atributos de reproducción; no
confunde salidas meramente disponibles con la ruta de medios. Si hay una ruta externa
o no puede identificarla, conserva el volumen actual. La ganancia PCM de la app sigue
en uno. Si Android rechaza el ajuste de volumen, se registra el fallo y se intenta
recuperar la reproducción igualmente.

Ante errores transitorios, reintenta tras 1, 2 y 5 segundos. Si el archivo sigue
fallando, o no existe o no es compatible, intenta otra pista de la cola. Si todas
fallan, conserva el servicio en primer plano y espera 30 segundos antes de una
nueva ronda. La notificación informa de la recuperación sin añadir controles.
En colas grandes, la recuperación solo revisa los índices que han dejado de ser
válidos cuando se reduce la cola, evitando repetir esa tarea en cada fallo.
Un vigilante también intenta recuperar el reproductor si la posición no avanza
durante 20 segundos, aunque el motor alterne entre preparado y cargando. Exige
avance real para dar por recuperada la reproducción y reconoce los bucles cortos.
Una pausa, detención o navegación explícita del usuario invalida los reintentos
pendientes, incluidos cambios de índice, siguiente/anterior y avance/retroceso.
Detener conserva la intención de parada para que el vigilante no reactive el audio;
si después se pulsa reproducir, se respeta esa nueva orden, incluso si llegan juntas. Tampoco
se ejecuta un reintento antiguo si el audio ya se recuperó. La interfaz vuelve a
conectarse si el servicio se recrea. Un final inesperado también usa reintentos
con espera; no entra en un bucle inmediato de reinicios. Los fallos de inicialización
del decodificador, decodificación o timeout pueden reconstruir el motor.

Velocidad y tono permanecen en 1, la ganancia digital de la app en 1 y el salto de
silencios desactivado. La sesión bloquea órdenes que cambien esos parámetros;
el volumen del teléfono sigue funcionando normalmente.

Los archivos originales de 320 kbps se reproducen sin recodificación, normalización,
ecualización añadida ni limitación de bitrate. La reserva de audio y la recuperación
cambian cómo se leen y reproducen las muestras, no la calidad del archivo. La salida
física continúa dependiendo del sistema, del códec Bluetooth y del dispositivo.

## Límites de la reproducción continua

El servicio en primer plano y el hilo de audio ya reciben el tratamiento previsto
por Android para reproducción. Una app normal no puede reservar todo el procesador
ni garantizar prioridad absoluta frente al sistema, otras apps, límites térmicos
o la ruta de salida. Aumentar la precarga del archivo no resuelve por sí solo un
vaciado del búfer de salida. La reserva PCM de 2.0.2 busca tolerar retrasos breves;
su efecto en los cortes requiere comparación en el teléfono afectado.

El motor registra vaciados de salida (`onAudioUnderrun`), errores del sink y la
configuración del último `AudioTrack` en memoria. La sesión privada publica los
contadores agrupando eventos durante un segundo; no hay sondeo adicional ni
escrituras de diagnóstico durante reproducción estable. El log de underruns se
limita a uno cada 30 segundos. Los contadores pertenecen a la vida del servicio,
se conservan al reconstruir el reproductor y se reinician al recrear el servicio.
No contienen nombres de archivos ni modifican la política de recuperación.

La app no pausa automáticamente por pérdida de foco o desconexión de auriculares;
al desconectarlos el sonido puede continuar por el altavoz. Esto no garantiza
ausencia absoluta de interrupciones. La recuperación puede producir una pausa
y su tiempo depende de Android y del teléfono. Si el usuario fuerza el cierre o
pulsa «Detener» desde Android, la aplicación no intenta eludir esa orden. La recuperación
al encender requiere activar la opción y que el dispositivo permita el inicio.
Mientras el teléfono está apagado no puede reproducir. Una llamada, un
apagado, un archivo inaccesible o la pérdida de una salida de audio pueden
afectar el sonido. El vigilante comprueba el avance de la posición; no mide el
sonido físico del altavoz ni puede detectar todos los casos de silencio.
El modo de repetición tampoco elimina silencios que ya existan dentro del archivo.
Una duración de 24 horas requiere una prueba real en el dispositivo de destino.

## Compilar

Requiere JDK 17 y Android SDK con la plataforma Android 15 / API 35.

```bash
git clone https://github.com/gustavoamarohidalgo1-ctrl/Reproductor_28_Octubre.git
cd Reproductor_28_Octubre
```

Abre la carpeta raíz en Android Studio para configurar el SDK, o configura
`ANDROID_HOME` con su ruta. También puedes crear `local.properties` en la raíz
con `sdk.dir=RUTA_ABSOLUTA_A_TU_ANDROID_SDK`. Ese archivo es propio de cada equipo
y no se incluye en Git. El wrapper incluido descarga la versión de Gradle necesaria.

```bash
./gradlew assembleDebug
```

El APK de desarrollo queda en `app/build/outputs/apk/debug/app-debug.apk`.
La versión `release` activa R8 y reducción de recursos; necesita tu firma de distribución.
Para una versión local optimizada, firmada con la misma clave de pruebas que debug:

```bash
./gradlew assembleBenchmark
```

El APK instalable queda en `app/build/outputs/apk/benchmark/app-benchmark.apk`.
`benchmark` hereda las optimizaciones de `release`, sin activar depuración.

Para validar compilación, análisis estático y las pruebas de recuperación,
vigilancia de progreso y guardado de marcadores:

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Con un emulador o dispositivo de pruebas conectado, las pruebas de integración
comprueban la persistencia real de Android y el servicio con un MP3 sintético de
320 kbps (formato, checksum, bucles, pausa, detención y archivos inaccesibles).
Selecciona explícitamente su identificador de `adb devices` para evitar ejecutar
las pruebas en otros teléfonos conectados; por ejemplo, para `emulator-5554`:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

Las pruebas de integración abren la aplicación y sustituyen temporalmente su cola;
deben ejecutarse en un entorno de pruebas. La simulación unitaria de 24 horas usa un
reloj virtual: no equivale a una prueba de sonido o batería de 24 horas en un teléfono.

La revisión de fidelidad del 27 de octubre de 2026 añadió una prueba con WAV y FLAC
de 24 bits, estéreo y 48 kHz generados a partir de muestras conocidas. Utiliza la
fábrica de audio de producción y compara exactamente las 24.000 muestras de cada
archivo que el decodificador entrega al sink, incluidos los bits inferiores que
se perderían al reducirlas a 16 bits. Comprueba también frecuencia, canales,
longitud e integridad de los archivos. La fábrica admite una subclase de prueba
para observar ese punto sin duplicar su configuración ni cambiar su comportamiento.

En un emulador Android 15/API 35 pasaron esa prueba y las dos comprobaciones de
recorte gapless de PCM flotante y capacidad del búfer. No se detectó una pérdida
de precisión en esos archivos; no se cambió la configuración sonora ni se aumentó
la versión. La captura del decodificador no mide el mezclador, el DAC o el altavoz.
La prueba gapless existente observa por separado los bytes aceptados por
AudioTrack. El Redmi no estaba conectado y no se ha medido una mejora audible en
él. Evidencia local: `app/build/reports/audio-fidelity-2026-10-27/summary.json`.

Para repetir únicamente estas comprobaciones en un entorno de pruebas:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.reproductor.audiolibros.HighResolutionAudioInstrumentedTest,com.reproductor.audiolibros.FloatGaplessAudioSinkInstrumentedTest,com.reproductor.audiolibros.ContinuousAudioBufferInstrumentedTest
```

Para comprobar la variante optimizada en el mismo entorno de pruebas:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :validation:connectedBenchmarkAndroidTest
```

El módulo `validation` prueba el APK optimizado por IPC con dependencias propias
del arnés; no desactiva R8 ni modifica permisos de producción. Detalles en
[validation/README.md](validation/README.md).

Las pruebas también verifican marcadores entre pistas, orden de pausa/selección,
cola vacía persistente y búsqueda después de 25 horas en un WAV sparse de 27 horas.
El archivo sparse comprueba duraciones grandes sin escribir gigabytes físicos; no
representa 27 horas de escucha real.

El ensayo continuo es optativo y mide tiempo real. Selecciona un dispositivo dedicado;
reemplaza su cola. El script instala la variante elegida y guarda evidencia JSON:

```bash
# Ensayo corto del arnés, seguido de un ensayo real de 24 horas cuando corresponda.
./scripts/validate-continuity.sh emulator-5554 180 benchmark
./scripts/validate-continuity.sh DEVICE_SERIAL 86400 benchmark
# Opcional: archivo local ya disponible y legible por la app en el dispositivo.
./scripts/validate-continuity.sh DEVICE_SERIAL 86400 benchmark /sdcard/Music/audio.mp3
```

Sin archivo adicional usa el MP3 sintético de pruebas. Registra avance, repeticiones,
estado del motor, errores y actualidad del checkpoint del proceso de audio. No mide
sonido físico, autonomía ni calidad Bluetooth; para eso se necesita una prueba con
el audio y la salida reales. Mantén el equipo que ejecuta adb encendido durante el ensayo.

El informe del ensayo incluye los últimos contadores de salida publicados al
principio, durante y al final. Pueden llegar con retraso si el hilo de aplicación
está ocupado; no constituyen una captura física del sonido ni una instantánea IPC
sincronizada. El ensayo exige al menos dos checkpoints distintos vinculados a la
cola concreta de la prueba, también cuando su duración es corta.

## Instalar en el teléfono

1. Descarga el [APK 2.1.0](https://github.com/gustavoamarohidalgo1-ctrl/Reproductor_28_Octubre/releases/download/v2.1.0/Reproductor-2.1.0.apk)
   o compílalo con el comando anterior y cópialo al teléfono.
   La [versión publicada 1.1](https://github.com/gustavoamarohidalgo1-ctrl/ReproductorUltimate/releases/tag/v1.1)
   es anterior a estas mejoras.
2. Ábrelo en el teléfono y permite "instalar apps de origen desconocido".
3. Al abrir la app, concede el permiso de acceso a audio y notificaciones.
4. Para uso prolongado, el botón **Evitar restricciones de batería** permite abrir
   la solicitud de excepción de Android. La decisión es del usuario; la app no
   repite automáticamente el diálogo al abrirla o girar la pantalla.
   El botón desaparece cuando Android confirma la excepción, al volver del diálogo
   o de Ajustes y también si el permiso cambia mientras la pantalla está abierta.
   No se guarda una autorización supuesta; si la excepción se revoca, vuelve a aparecer.
   También puedes revisar su configuración de batería en Ajustes del teléfono.
5. Para recuperar reproducción y volumen después de reiniciar, activa **Reanudar
   al encender**. El teléfono debe iniciar sin PIN, patrón ni contraseña. En HyperOS,
   revisa también el permiso de inicio automático de la aplicación si el sistema lo
   ofrece. La primera comprobación debe hacerse fuera de una sesión importante:
   reproduce un archivo, espera a que se guarde el progreso, reinicia normalmente
   y comprueba el audio sin abrir la app. El interruptor no modifica el bloqueo del
   teléfono ni los permisos del fabricante por su cuenta.

Como orientación para HyperOS, Xiaomi documenta **Ajustes → Aplicaciones → Permisos
→ Inicio automático en segundo plano** en su [guía para REDMI 13C](https://www.mi.com/global/support/faq/details/KA-497677/).
La ubicación puede variar en el Redmi Note 14 y no se ha comprobado en el teléfono
del usuario. Autorizar el inicio no demuestra por sí solo que la recuperación funcione.
Para el [REDMI Note 14, Xiaomi confirma](https://www.mi.com/uk/support/faq/details/KA-529972/)
que el ahorro de batería reduce la actividad en segundo plano y sitúa sus modos en
**Ajustes → Batería → Modo actual**. Anota el modo usado al probar para poder
distinguir un problema de la aplicación de una restricción del sistema.

## Estructura

```
app/src/main/java/com/reproductor/audiolibros/
├── MainActivity.kt      # UI: lista de audios + barra de reproducción
├── PlaybackService.kt   # Motor aislado, vigilancia y autorización de controles
├── PlaybackStateStore.kt # Cola y punto de recuperación con escritura atómica
├── PlaybackRecoveryPolicy.kt # Reintentos, cambio de pista y espera entre rondas
├── PlaybackProgressWatchdog.kt # Avance real y estabilidad, independiente de Android
├── PlaybackNotificationProvider.kt # Notificación informativa sin acciones
├── AudioStore.kt        # Consulta MediaStore (audios del dispositivo)
└── PositionStore.kt     # Posición por archivo + ajustes (SharedPreferences)
```

`PositionCheckpointCache.kt` mantiene los marcadores actuales y agrupa escrituras
periódicas sin retrasar la lectura de la posición en el proceso del servicio.

Evidencia local de recuperación al encender 2.1.0: `app/build/reports/boot-recovery-2.1.0/`.
El [archivo de evidencias de la publicación](https://github.com/gustavoamarohidalgo1-ctrl/Reproductor_28_Octubre/releases/download/v2.1.0/Evidencias-2.1.0.zip)
conserva los informes disponibles de análisis, prioridad, fidelidad y recuperación,
con sus rutas `app/build/reports/`. Incluye resultados de pruebas, registros, firma
y hashes del APK. Los informes históricos de septiembre se conservan como
documentación; sus archivos temporales de validación no están disponibles en esta copia.
Los directorios generados por Gradle, la configuración local del SDK y las claves
de firma no forman parte del código versionado.
Validación de prioridad 2.0.2: [VALIDACION_PRIORIDAD_AUDIO_2_0_2.md](VALIDACION_PRIORIDAD_AUDIO_2_0_2.md).
Validación 2.0.1: [VALIDACION_CIERRE_2_0_1.md](VALIDACION_CIERRE_2_0_1.md).
Validaciones anteriores: [VALIDACION_VERSION_2_0.md](VALIDACION_VERSION_2_0.md),
[VALIDACION_REFUERZO_1_8.md](VALIDACION_REFUERZO_1_8.md),
[VALIDACION_REFUERZO_1_7.md](VALIDACION_REFUERZO_1_7.md),
[VALIDACION_RENDIMIENTO_1_6.md](VALIDACION_RENDIMIENTO_1_6.md)
y [VALIDACION_FINAL_1_5.md](VALIDACION_FINAL_1_5.md).
Las mejoras previas del bucle y sonido están en [VALIDACION_BUCLE_CALIDAD_1_4.md](VALIDACION_BUCLE_CALIDAD_1_4.md).
El refuerzo previo de continuidad está en [VALIDACION_CONTINUIDAD_1_3.md](VALIDACION_CONTINUIDAD_1_3.md).
