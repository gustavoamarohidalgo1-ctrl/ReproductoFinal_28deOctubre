# Reproductor de Audiolibros (Android)

Versión actual: **2.1.5**, con precarga de audio por reproducir, una reserva PCM
mayor frente a retrasos de alimentación y diagnósticos acotados de buffering y salida.
Conserva el audiolibro seleccionado,
menos escrituras durante repeticiones rápidas y reanudación opcional al encender.
[Descargar el APK 2.1.5](https://github.com/gustavoamarohidalgo1-ctrl/ReproductoFinal_28deOctubre/releases/download/v2.1.5/Reproductor-2.1.5.apk) · [Notas de la versión](https://github.com/gustavoamarohidalgo1-ctrl/ReproductoFinal_28deOctubre/releases/tag/v2.1.5).
La recuperación automática tras reinicio se verificó en un emulador Android 16;
la comprobación en el Redmi Note 14 4G del usuario sigue pendiente.

La versión 2.1.5 se compiló con R8 y se instaló como actualización conservando los
datos; se confirmó la versión y el hash del APK instalado. No se ejecutaron nuevas
pruebas funcionales para esta versión. Las comprobaciones de la versión 2.1.4 y los
informes históricos no garantizan la ausencia de chasquidos en el Redmi. No hay un
ensayo de 24 horas activo ni una validación acústica de 24 horas completada.

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
- **Recuperación**: conserva el audiolibro seleccionado y el punto de escucha, reintenta errores
  y detecta cuando la posición deja de avanzar. Nunca selecciona otro archivo
  automáticamente ante un fallo.
- **Reanudación al encender (opcional)**: en teléfonos que arrancan sin PIN,
  patrón ni contraseña, intenta recuperar automáticamente la última reproducción
  y su volumen multimedia. Se activa una vez con **Reanudar al encender** y respeta
  una pausa previa. Necesita que el sistema entregue el aviso de inicio y permita
  el servicio; el fabricante puede imponer requisitos adicionales de autoarranque.
  El interruptor y su explicación desaparecen cuando el servicio confirma la
  activación. Se configura una vez por instalación y no hay menú de desactivación. El ajuste independiente de autoarranque de HyperOS no se modifica.
- **Mayor reserva de audio**: precarga hasta 120 segundos de muestras originales,
  con un umbral adicional de datos almacenados (no es un límite total de RAM). A 320 kbps, 120 segundos son aproximadamente
  4,8 MB de audio comprimido. No convierte archivos ni reduce su bitrate.
- **Reserva de salida frente a cargas breves**: el búfer PCM de `AudioTrack` solicita
  un objetivo de 2.000 a 3.000 ms (antes, 750 a 1.500 ms), frente a los 250 a 750 ms predeterminados de Media3
  1.6.1. Es distinto de la precarga de archivos: conserva más audio ya decodificado
  para tolerar retrasos breves al abrir otras apps. El mínimo de la plataforma puede
  exigir una capacidad mayor. No cambia las muestras ni garantiza que el búfer esté
  siempre lleno. Consume algo más de RAM y puede aumentar la latencia según la salida.
  La comparación con una pausa deliberada de alimentación no reproduce por sí sola
  los cortes del Redmi cada 20–30 minutos; su causa sigue sin estar identificada.
- **Precarga hacia delante**: no retiene diez segundos de audio ya escuchado en el
  mismo asignador. En PCM de alta tasa, esa reserva anterior podía consumir el objetivo
  de bytes antes de la protección de Media3 que la descarta cuando falta audio futuro.
  Los saltos hacia atrás siguen disponibles y releen el archivo local; pueden tardar
  más si el almacenamiento es lento. No cambia la posición guardada ni las muestras.
- **Diagnóstico de discontinuidades**: contadores y tiempos de underruns, buffering,
  errores y creación/liberación de la salida, sin sondeo periódico ni escrituras
  adicionales a archivos. Los registros están limitados y no contienen títulos ni rutas.
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
  a cargar el archivo. La biblioteca se actualiza al añadir o retirar archivos.
- La biblioteca conserva una caché inmutable entre recreaciones; compara los cambios
  fuera del hilo principal y solo actualiza las filas necesarias. Seleccionar otra
  pista conserva su marcador y transfiere únicamente ese archivo al servicio. Los empates de
  carpeta y título se resuelven por ID, para mantener un orden estable entre consultas.
- Desde Android 11 / API 30, la versión y generación de MediaStore de cada volumen
  permiten reutilizar un catálogo válido sin volver a recorrer todos sus archivos.
  Los cambios, avisos del proveedor y refrescos manuales invalidan esa comprobación;
  si el proveedor no la admite o devuelve un estado inconsistente, se realiza un
  escaneo completo. Los Android anteriores conservan el escaneo habitual.
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
  el estado del archivo para mostrarla aunque la biblioteca no esté disponible. Durante la
  restauración inicial muestra un indicador de carga, en vez de un falso 0:00.
- La notificación abre la aplicación para acceder al botón de reproducción/pausa.

## Recuperación de fallos

En condiciones normales, el servicio guarda un punto de recuperación cada dos
segundos mientras reproduce, y también al pausar o cambiar de archivo. El servicio
recibe y conserva únicamente el elemento seleccionado. Al leer una cola de una
versión anterior, intenta conservar el elemento activo y su posición. Si el
checkpoint más reciente no puede usarse, la lectura heredada puede recurrir a
un punto inicial anterior, siempre en pausa.

Las escrituras se hacen fuera del hilo principal mediante `AtomicFile`. El escritor
admite una tarea activa y el último estado pendiente, para no acumular posiciones
antiguas si el disco se ralentiza. Una pausa reciente sustituye actualizaciones
pendientes. El estado vacío deja un registro atómico que impide recuperar una
selección anterior. La lectura mantiene compatibilidad con el formato de cola
legado y sus límites de seguridad de 20.000 entradas y 16 MiB; esos límites no
implican que la reproducción actual mantenga una biblioteca completa en la sesión.
Los marcadores por archivo se mantienen en memoria y se escriben cada 30 segundos,
además de hacerlo al pausar, desplazarse o cambiar de archivo. Los IDs locales se
excluyen del backup y de la transferencia a otro dispositivo.

Desde 2.1.2, las repeticiones automáticas muy rápidas del mismo elemento comparten
la ventana de dos segundos del checkpoint. La primera vuelta conserva el guardado
inmediato; las siguientes evitan repetirlo si ya hay una solicitud reciente del
mismo estado. También se evita escribir un marcador intermedio en cero justo antes
de guardar la posición actual del mismo audio. Pausar, buscar o cambiar de pista
conserva su guardado inmediato. No se cambian las muestras, el formato ni la
velocidad del audio. Si el proceso termina entre dos guardados, puede recuperarse
la posición de la vuelta anterior dentro de esa ventana, más la latencia del disco.

En una comparación de 12 segundos con un WAV de 200 ms en bucle, el sondeo de
archivos publicados observó 66 cambios de checkpoint antes y 7 después; los cambios
del archivo de marcadores fueron 58 y 4. Con un MP3 de 12 segundos se mantuvieron
6 y 1, respectivamente. Son observaciones de un emulador, no contadores exactos
de escrituras ni mediciones de batería o sonido físico. La prueba también comprueba
que el marcador y el checkpoint coincidan al pausar. No acredita por sí sola una
sesión de 12–24 horas en un Redmi.

La caché propia de marcadores conserva hasta 256 entradas recientes. Antes de
retirar una entrada vuelca su progreso pendiente, sin borrar el marcador guardado;
si vuelve a necesitarse, lo lee otra vez. Este límite corresponde solo a esa caché,
no al contenido de SharedPreferences ni a la RAM total. Un marcador con un tipo
incompatible se descarta individualmente para que no impida seleccionar el archivo
ni afecte las posiciones de otros audios.

Si el almacenamiento impide guardar repetidamente, los intentos del mismo estado
de sesión esperan progresivamente 2, 4, 8, 16 y hasta 30 segundos. Esa espera se
aplica mientras se mantienen la misma generación de sesión, índice de pista e
intención de reproducir; no limita todas las escrituras de la aplicación. Una pausa
o búsqueda explícita tiene un intento prioritario, sin esperar ese plazo. Si también
falla, las siguientes actualizaciones periódicas vuelven a respetar la espera.
Esto evita repetir trabajo de disco innecesario sin detener el audio. Tras un
guardado correcto se restablece el ritmo habitual de dos segundos.
Si no se puede abrir el archivo temporal del guardado, la operación falla antes
de codificar el estado seleccionado. Una validación fallida después de abrirlo revierte
la escritura y conserva el estado confirmado anterior.

Si Android reinicia el servicio mediante `START_STICKY`, puede retomar
el último punto guardado en el mismo arranque del dispositivo. Durante ese reinicio,
promueve inmediatamente el servicio y mantiene despierto el procesador mientras
lee el estado, sin esperar a que termine su lectura. Abrir normalmente
la aplicación restaura el archivo seleccionado en pausa; una pausa manual se conserva.

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
el estado de reproducción, los marcadores y los archivos siguen en su almacenamiento habitual. El
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

Ante errores transitorios, reintenta el mismo archivo tras 1, 2 y 5 segundos.
Si se agotan esos intentos, espera 30 segundos antes de una nueva ronda. Si el
archivo no existe, no es accesible o no es compatible, espera 30 segundos antes
de volver a comprobarlo. Nunca cambia automáticamente a otro audiolibro; el usuario
puede seleccionar otro explícitamente. La notificación informa de la recuperación
sin añadir controles.
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
git clone https://github.com/gustavoamarohidalgo1-ctrl/ReproductoFinal_28deOctubre.git
cd ReproductoFinal_28deOctubre
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

Las pruebas de integración abren la aplicación y sustituyen temporalmente la selección;
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

Las pruebas incluyen marcadores entre archivos, orden de pausa y selección, estado
vacío persistente y un WAV sparse de 27 horas: buscan después de 25 horas y prueban
el paso del final al inicio del mismo archivo. Es una prueba de frontera, no un
ensayo de 24 horas ni 27 horas de escucha real. Las pruebas nuevas requieren ejecución
en un dispositivo o emulador; su presencia aquí no acredita que hayan pasado.

El ensayo continuo es optativo y mide tiempo real. Selecciona un dispositivo dedicado;
reemplaza su selección. El script instala la variante elegida y guarda evidencia JSON:

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
sesión concreta de la prueba, también cuando su duración es corta.

El ensayo asistido envía la actividad a segundo plano, solicita apagar la pantalla
y comprueba su estado durante la ejecución. La instrumentación y el controlador
permanecen conectados: esa condición se declara en el informe. Conserva una ventana
acotada de muestras y eventos junto con los contadores totales; los tiempos sin
reproducción y en buffering son estimaciones por sondeo. Cada ejecución usa un
identificador propio para rechazar informes anteriores. El script requiere Python 3,
impone un timeout externo e intenta recuperar la evidencia incluso si falla la prueba.
Un timeout del equipo anfitrión puede dejar la instrumentación o el audio activos
en el dispositivo; el informe no se considera una ejecución aprobada.

Para observar una sesión normal ya iniciada, sin instalar ni controlar la app,
existe un monitor complementario. Requiere un dispositivo de pruebas accesible por
ADB, reproducción activa, la interfaz en segundo plano y la pantalla apagada:

```bash
python3 scripts/monitor-background-playback.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE_SERIAL \
  --package com.reproductor.audiolibros --duration 86400 --interval 30 \
  --output app/build/reports/background-24h
```

El monitor comprueba el servicio en primer plano y el avance del contador de
AudioTrack asociado a su proceso, además de la sesión y la pantalla. Rechaza nuevos
underruns, cambios de proceso o salida y brechas excesivas entre observaciones;
un formato de diagnóstico que no pueda interpretar no produce una aprobación.
Guarda las muestras incrementalmente y recoge memoria y CPU periódicamente.
Su consulta por ADB añade carga y solo acredita las condiciones muestreadas:
no mide sonido físico, autonomía ni comportamiento específico de HyperOS. La carpeta
de salida debe ser nueva. No reinicia la reproducción si detecta un fallo.

Las comprobaciones del monitor y del timeout se ejecutan con:

```bash
python3 -m unittest discover -s scripts/tests -v
```

## Instalar en el teléfono

1. Descarga [Reproductor-2.1.5.apk](https://github.com/gustavoamarohidalgo1-ctrl/ReproductoFinal_28deOctubre/releases/download/v2.1.5/Reproductor-2.1.5.apk)
   de la [publicación 2.1.5](https://github.com/gustavoamarohidalgo1-ctrl/ReproductoFinal_28deOctubre/releases/tag/v2.1.5),
   o compílalo con el comando anterior y copia el APK al teléfono.
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
├── PlaybackStateStore.kt # Estado seleccionado y lectura compatible con colas anteriores
├── PlaybackRecoveryPolicy.kt # Reintentos del archivo seleccionado y espera entre rondas
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
