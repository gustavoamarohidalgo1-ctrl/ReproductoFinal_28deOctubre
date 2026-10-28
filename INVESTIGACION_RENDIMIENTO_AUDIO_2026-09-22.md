**Investigación de velocidad, optimización y calidad de audio — 22 de septiembre de 2026**

Nota de publicación: este informe conserva sus conclusiones históricas. Las referencias de líneas corresponden a la versión revisada; los enlaces al código abren los archivos actuales.

El mayor margen de mejora está en evitar trabajo repetido de biblioteca y cola, optimizar el paquete de distribución y reducir asignaciones temporales. Estas vías permiten mejorar eficiencia sin cambiar las muestras de audio. El búfer, la frecuencia de guardado y el offload requieren otro tratamiento: tienen compromisos entre memoria, energía, recuperación y continuidad que deben medirse.

Esta es una investigación previa a implementar cambios. El código y la configuración del proyecto principal permanecen intactos. Revisé las fuentes de Media3 **1.6.1**, documentación oficial de Android y el código local; añadí sondas de medición y probé configuraciones de compilación únicamente en una copia temporal del commit `831eece`. Los archivos de producción de esa copia se compararon con los originales y coinciden. No se instaló nada en el dispositivo físico conectado.

**Qué se investigó y cuánto puede concluirse.**

Se estudiaron cuatro dimensiones: respuesta de la interfaz y selección de pista; memoria y escrituras; tamaño y preparación de release; fidelidad y continuidad de sonido. Las mediciones de ejecución se hicieron en un emulador Pixel_10a, Android 17/API 37, ARM64, con APK debug, metadatos sintéticos y red desactivada durante la pasada final. El emulador se abrió con `-read-only -no-snapshot -no-window -no-audio`. Sus números sirven para localizar costes y estudiar escala; **no son velocidades, consumo ni calidad acústica del teléfono del usuario**.

Los ensayos de escalado tienen tres muestras por tamaño, precedidos por calentamiento pequeño. No tienen suficiente repetición ni control de JIT/GC para estimar P95/P99. Una primera pasada coincidió con compilación en el anfitrión y se conserva como piloto; las tablas usan la pasada final, realizada después de terminar las compilaciones. Incluso así, hacen falta benchmarks físicos con una variante próxima a release. Android recomienda esa configuración para [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).

La evidencia reproducible está en performance-research-2026-09-22 (`app/build/reports/performance-research-2026-09-22`, referencia local histórica): fuente de las sondas, configuración experimental, resultados JSON, registros, comparación de APK y notas especializadas. Está dentro de `app/build`, ignorada por Git. Este informe es el documento permanente de conclusiones.

**Resultado medido: el paquete release puede reducirse considerablemente.**

La configuración actual desactiva R8 en [app/build.gradle.kts:21](app/build.gradle.kts). Se compararon dos APK release sin firmar del mismo código, AGP 8.7.3, Kotlin 2.0.21 y Media3 1.6.1. La única diferencia funcional de configuración fue activar conjuntamente minificación/optimización de código y reducción de recursos en la copia experimental.

| Medida | Release actual | Release con R8 y reducción de recursos |
| --- | ---: | ---: |
| Tamaño APK | 7.423.938 bytes / 7,08 MiB | 2.448.415 bytes / 2,34 MiB |
| DEX sin comprimir | 14,44 MiB | 2,40 MiB |
| Archivos DEX | 2 | 1 |
| Compilación | Correcta | Correcta |

**Reducción del APK observada: 67,02 %.** Es una mejora de tamaño demostrada, no un 67 % de aceleración ni de ahorro de batería. No se ejecutó aceptación funcional sobre el APK optimizado, por lo que todavía no se considera una versión lista para distribuir. Los ensayos de ejecución descritos después usan el código actual en debug.

Para AGP 8.7.3 son aplicables `isMinifyEnabled = true` e `isShrinkResources = true`; no hay que copiar sin comprobar el DSL nuevo de versiones posteriores. R8 puede eliminar código y optimizar ejecución, pero deben verificarse recursos, sesión, restauración y notificación, y conservar el mapping para interpretar errores. [Documentación oficial de R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization).

**Resultado medido: reemplazar toda la cola escala con la biblioteca.**

[MainActivity.kt:447](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt) vuelve a llamar a `setMediaItems` con la biblioteca completa cuando se selecciona otra pista. Aunque la lista Kotlin esté cacheada, Media3 crea Bundles y una representación de timeline. Se comunica además con el proceso separado `:playback`. En 1.6.1, `BundleListRetriever` divide la transferencia en varias transacciones: el problema investigado es trabajo y asignaciones proporcionales al catálogo, no una excepción automática por superar una única transacción. [Implementación del controlador](https://github.com/androidx/media/blob/1.6.1/libraries/session/src/main/java/androidx/media3/session/MediaControllerImplBase.java), [transporte de listas](https://github.com/androidx/media/blob/1.6.1/libraries/common/src/main/java/androidx/media3/common/BundleListRetriever.java).

La sonda llamó al servicio real con colas sintéticas y midió dos límites distintos. Medianas de tres muestras:

| Entradas | Llamada de sustitución en el hilo del cliente | Desde la orden hasta observar el checkpoint escrito |
| --- | ---: | ---: |
| 100 | 1,05 ms | 15,61 ms |
| 1.000 | 4,27 ms | 70,76 ms |
| 10.000 | 38,25 ms | 677,41 ms |

La segunda columna puede afectar la respuesta porque la app realiza esa llamada desde la interfaz. La tercera incluye comunicación, procesamiento del servicio, cola de escritura y persistencia: **no es tiempo hasta el primer sonido, ni significa que la UI quede bloqueada todo ese período**. No se preparó ni decodificó audio en este ensayo. El resultado respalda priorizar la reutilización de cola cuando su contenido no cambió.

La solución futura debería mantener una revisión de catálogo/cola y navegar por ID o índice dentro de ella. Hay una condición indispensable: [PlaybackService.kt:112](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) restaura el marcador de la pista destino dentro de `onSetMediaItems`. Reemplazar la llamada directamente por `seekTo()` puede perder ese comportamiento. La nueva operación del servicio debe guardar la pista saliente, recuperar la posición de la siguiente e invalidar reintentos antiguos. Mantener también la posibilidad de saltar archivos fallidos; reducir la cola a una sola pista cambiaría esa recuperación.

**Resultado medido: JSON pequeño no equivale a trabajo pequeño en memoria.**

Se ejercitó `PlaybackStateStore` sin cambiar su implementación, con un directorio aislado y textos breves. Medianas de tres muestras:

| Entradas | Archivo de cola | Guardar cola y checkpoint | Leer y reconstruir snapshot |
| --- | ---: | ---: | ---: |
| 100 | 18,8 KiB | 3,59 ms | 1,85 ms |
| 1.000 | 189,3 KiB | 20,11 ms | 15,12 ms |
| 10.000 | 1,88 MiB | 179,07 ms | 144,51 ms |
| 20.000 | 3,81 MiB | 292,08 ms | 203,97 ms |

En el caso de 20.000 entradas se observaron aproximadamente 76,1 MiB de asignaciones acumuladas alrededor de la escritura y 58,4 MiB alrededor de la lectura, mediante contadores de ART. Son asignaciones del proceso, incluidas temporales; **no son heap retenido, pico de RAM ni memoria total de ambos procesos**. Los valores exactos dependen de GC, JIT y metadatos.

El código construye JSON por entrada, lo serializa para calcular tamaño y luego serializa el documento final. Al leer coexisten bytes, texto, árbol JSON, mapas y `MediaItem`. Esto explica por qué el límite de archivo de 16 MiB no limita a 16 MiB la memoria. Antes de aumentar límites, considerar streaming o una representación más compacta si las bibliotecas reales justifican el coste, conservando la atomicidad, generación, token y fallback pausado.

El checkpoint pequeño por separado mantuvo medianas aproximadas de 0,76–1,01 ms en los distintos tamaños, con 60 muestras por tamaño. Esa separación ya es una buena optimización: **la app no reescribe toda la cola cada dos segundos**. No convertir estos tiempos del emulador en coste de sincronización de una SD o teléfono real.

**Biblioteca e interfaz: mejoras de mayor utilidad.**

| Cambio propuesto | Motivo concreto | Condición para hacerlo bien |
| --- | --- | --- |
| Caché de catálogo que sobreviva a recreaciones | `onResume` consulta y reconstruye toda la biblioteca | Detectar cambios mientras el observer está desconectado, permisos y volúmenes |
| Evitar construir `MediaItem` si nada cambió | Hoy se crean antes de comparar; pueden descartarse enteros | Comparar snapshots inmutables fuera de UI y publicar una revisión |
| Obtener tamaño de archivo de forma diferida | Si `SIZE <= 0`, AudioStore abre un descriptor por fila | Resolver para la pista seleccionada; priorizar bitrate declarado por el decodificador |
| Actualizar solo fila anterior y nueva | La selección usa `notifyDataSetChanged()` | Índice por ID, payloads y selección coherente con catálogo cambiante |
| Diff asíncrono en cambios reales de catálogo | Igualdad/copia y notificación completa llegan a UI | No mutar las listas durante el diff; preservar scroll |

Evidencia: [MainActivity.kt:217](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt), [carga y publicación:381](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt), [AudioStore.kt:125](app/src/main/java/com/reproductor/audiolibros/AudioStore.kt) y [adaptador:656](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt).

Quitar simplemente la recarga de `onResume` sería incorrecto porque el observer no trabaja con la pantalla cerrada. Android 11+ permite consultar generaciones por volumen; hay que combinar esa señal con versión de MediaStore y cambios de permisos/volumen. Las versiones anteriores requieren una estrategia compatible. La sincronización incremental debe tratar también eliminaciones, no solo incorporaciones. [MediaStore](https://developer.android.com/training/data-storage/shared/media). Para diferencias de listas hay soporte en [ListAdapter](https://developer.android.com/reference/androidx/recyclerview/widget/ListAdapter).

Las microoptimizaciones vienen después: cachear mediaId/subtítulos, evitar formato repetido y consultar por mapa. La búsqueda lineal de duración de `updateProgress()` solo se ejecuta cuando el controlador no conoce la duración; no recorre necesariamente toda la biblioteca cada segundo. Los getters de `MediaController` usan estado local e interpolación, por lo que tampoco hay una llamada remota por cada lectura de progreso. Estas precisiones evitan optimizar un coste que no existe.

**Batería y persistencia: reducir trabajo sin perder intención del usuario.**

El heartbeat nominal de dos segundos supone 1.800 oportunidades de guardado por hora mientras reproduce, antes de deduplicación, retrasos y agrupación. `LatestTaskExecutor` conserva solo el último trabajo pendiente, pero no limita la frecuencia si el disco despacha cada escritura inmediatamente. AtomicFile confirma el archivo y sincroniza; el volumen pequeño de datos no elimina todo coste. [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile).

La pausa evita normalmente escribir, aunque el heartbeat continúa mientras el servicio siga ligado. Condicionarlo puede ahorrar algo de trabajo, con menor prioridad que la cola completa. `Handler.postDelayed` usa uptime y **no despierta por sí solo una CPU en suspensión profunda**; no debe presentarse como una alarma de dos segundos. [Contrato de Handler](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,long)).

El guardado de marcadores usa `SharedPreferences.apply()`, no una confirmación durable inmediata. Los bucles solicitan forzados que pueden saltar el intervalo de 30 segundos; hay deduplicación, por lo que no se puede afirmar una escritura por vuelta sin medir. El historial de marcadores tampoco tiene poda. Una política LRU debe persistir antes de expulsar; una limpieza del historial no debe borrar posiciones solo porque una SD esté desmontada temporalmente. [Contrato de apply](https://developer.android.com/reference/android/content/SharedPreferences.Editor#apply()).

Se midió además el servicio real reproduciendo el MP3 sintético de las pruebas, con dos ventanas de 30 segundos después de estabilizar el arranque. Un FileObserver contó reemplazos del archivo de checkpoint y cierres de escritura del XML de marcadores:

| Repetición | Reemplazos de checkpoint | Escrituras observadas del XML de marcadores | Reescrituras de cola |
| --- | ---: | ---: | ---: |
| Archivo completo de 12,042 s | 17 | 2 | 0 |
| Clip de 800 ms del mismo archivo | 50 | 37 | 0 |

**En este ensayo, el clip corto multiplicó aproximadamente por tres los guardados de checkpoint.** Es una evidencia concreta para priorizar agrupación de callbacks si se usan audios muy cortos. Son eventos observados de archivos durante una sola ventana por caso; no mediciones de energía, desgaste físico ni una tasa garantizada en todos los dispositivos. El audio sintético es silencio, el emulador estaba sin salida sonora, y las comprobaciones verificaron que el reproductor seguía activo y sin error, no fidelidad acústica. Las dos sondas instrumentadas de investigación terminaron correctamente; no sustituyen la suite funcional ni validan el APK release con R8.

Primero agrupar solicitudes redundantes conservando pausa, seek y cambios de pista. Solo después comparar intervalos de checkpoint de 2/5/10 segundos, si se acepta recuperar una posición potencialmente más antigua tras una muerte abrupta. Separar vigilancia y persistencia permite estudiar ese ajuste sin aumentar a la vez el tiempo de detección de bloqueos. No cambiar `apply()` por `commit()` en el hilo del reproductor ni sustituir el Handler por alarmas que despierten la CPU.

**Calidad del audio: qué conservar y qué se puede mejorar.**

Conviene distinguir fidelidad —conservar lo que contiene el archivo—, continuidad —evitar silencios y cortes— y salida real —lo que entrega Android al DAC, altavoz o Bluetooth—. La app ya evita recodificación y efectos añadidos, fija velocidad/tono/ganancia en 1 y permite PCM flotante. La velocidad musical permanece en 1; mejorar la respuesta de la aplicación no requiere acelerar ni alterar la reproducción.

La opción float **puede intervenir también con MP3 o AAC**: en 1.6.1 el renderer solicita PCM_FLOAT al codec si el sink lo admite; la salida efectiva depende de lo que entregue el decoder. Puede evitar una cuantización intermedia, pero no recupera información perdida en compresión ni garantiza una salida física bit-perfect. Mantenerla como referencia y medir el decoder/PCM efectivo antes de desactivarla por un supuesto ahorro. [MediaCodecAudioRenderer 1.6.1](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java#L1027), [DefaultAudioSink 1.6.1](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1709).

Aumentar artificialmente bitrate, frecuencia o profundidad no añade detalle que no estaba en el original. Para mejorar fidelidad, usar una fuente mejor cuando exista, evitar conversiones innecesarias y verificar la ruta efectiva. Android puede remuestrear y aplicar procesamiento de plataforma; un indicador de “320 kbps” describe el archivo, no el enlace Bluetooth. [Remuestreo en AOSP](https://source.android.com/docs/core/audio/src).

**Búfer: ajustar por formato y almacenamiento, no subir todo al máximo.**

La configuración actual tiene mínimos/máximos de carga 60/120 segundos, inicio 2,5 segundos, recuperación 5 segundos y retroceso 10 segundos: [PlaybackService.kt:382](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt). Son cantidades de contenido, no retrasos obligatorios de reloj. Leer 2,5 segundos desde almacenamiento local puede tomar mucho menos de 2,5 segundos.

Para audio, Media3 1.6.1 usa por defecto un objetivo de unos **12,5 MiB** y prioridad de bytes sobre tiempo. Ese objetivo puede detener la carga antes de alcanzar los segundos deseados, y no es un techo rígido de RAM. El allocator, decoder, AudioTrack y objetos de cola tienen costes adicionales. [DefaultLoadControl](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java), [DefaultAllocator](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/upstream/DefaultAllocator.java).

| Representación considerada | 120 segundos teóricos | 10 segundos teóricos |
| --- | ---: | ---: |
| MP3 a 320 kbit/s, comprimido | 4,80 MB | 0,40 MB |
| PCM estéreo 44,1 kHz / 16 bits | 21,17 MB | 1,76 MB |
| PCM estéreo 192 kHz / 24 bits | 138,24 MB | 11,52 MB |

Son cuentas decimales sin metadatos, contenedor, alineación ni otros buffers, no consumo medido. El MP3 se almacena comprimido antes de decodificar. FLAC tiene una tasa comprimida variable. En PCM grande el backbuffer puede consumir buena parte del objetivo; Media3 incluye una protección que lo descarta si necesita volver a cargar, así que tampoco se garantizan diez segundos retenidos. [Protección de carga](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImplInternal.java#L2991).

Candidatos para experimentar, todavía no recomendaciones universales: comparar 60/120 con 15/30 segundos, inicio de 0,5–1 segundo, recuperación de 1–2 segundos y backbuffer de 0 frente a 10 segundos. Mantener control por bytes y probar SD lenta, cambios de pista y alta resolución. Menos buffer puede ahorrar memoria y lecturas descartadas; más buffer puede agrupar lecturas y tolerar pausas de almacenamiento. Ninguno mejora por sí mismo la fidelidad. El buffer de muestras de LoadControl es distinto del de salida de AudioTrack; uno grande no elimina todos los underruns de salida.

**Offload: potencial de ahorro con condiciones reales.**

La app no lo habilita, y en 1.6.1 está desactivado por defecto. Permite delegar reproducción comprimida a hardware compatible y reducir trabajo del procesador principal. Evaluarlo con modo habilitado y fallback, no obligatorio; exigir soporte gapless si debe preservarse el recorte de delay/padding. El soporte depende de API, dispositivo, formato y salida. [Preferencias versionadas](https://github.com/androidx/media/blob/1.6.1/libraries/common/src/main/java/androidx/media3/common/TrackSelectionParameters.java), [detección de soporte](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioOffloadSupportProvider.java).

El punto delicado para este proyecto es su watchdog. Mientras duerme para offload, Media3 puede extrapolar la posición por reloj; verla avanzar no demuestra una lectura nueva del hardware ni sonido físico. Registrar estado de offload/sueño y revisar cómo se decide estabilidad/atasco al dormir y despertar. No basta activar un flag ni deshabilitar toda protección. [PlaybackInfo 1.6.1](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/PlaybackInfo.java#L583).

Conservar `WAKE_MODE_LOCAL` como referencia: Media3 libera su lock durante sueño de offload y lo recupera cuando necesita trabajar. Quitar wake locks o unir los dos procesos cambia garantías de continuidad. Los clips cortos y las transiciones frecuentes pueden limitar el tiempo de sueño. También conviene evaluar una versión posterior de Media3 con pruebas de regresión: las [notas oficiales](https://developer.android.com/jetpack/androidx/releases/media3#1.9.3) documentan correcciones de offload/gapless. No se ha acreditado todavía ahorro físico en esta app.

**Orden propuesto para una futura optimización.**

| Prioridad | Trabajo | Qué mejora | Riesgo que se debe vigilar |
| --- | --- | --- | --- |
| 1 | Validar R8 y reducción de recursos en release | Tamaño demostrado; posible mejora de carga/ejecución | Reflexión, recursos y componentes después de minificar |
| 1 | Reutilizar cola y catálogo con revisión | Selección, retorno a pantalla y asignaciones | Marcadores, archivos añadidos/eliminados y reintentos |
| 2 | Tamaño diferido, actualización de dos filas y diff asíncrono | Latencia de biblioteca y fluidez | Datos obsoletos y selección durante refrescos |
| 2 | Agrupar guardados redundantes | Trabajo periódico y temporales | Persistir pausa e intención más reciente |
| 3 | Medir y ajustar buffers por perfil | Memoria, inicio y lecturas | Cortes, SD lenta y PCM grande |
| 3 | Baseline/Startup Profiles propios | Arranque y recorridos frecuentes | No confundir compilación anticipada con eliminar trabajo innecesario |
| 4 | Offload compatible con diagnóstico y fallback | Posible ahorro de CPU/batería | Gapless, clips cortos, rutas y watchdog |

Los [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview) son complementarios a R8. Conviene generarlos sobre los recorridos finales: abrir, cargar biblioteca, desplazarse, seleccionar y restaurar. No se encontró un perfil propio del proyecto; algunas dependencias pueden aportar perfiles. Sus porcentajes genéricos de mejora no son resultados medidos para esta aplicación.

La validación física debe separar arranque de ambos procesos de apertura con `:playback` ya vivo; medir primer frame y pantalla utilizable, tiempo hasta sonido, frames lentos, CPU, memoria PSS conjunta, escrituras y energía por hora. No sumar RSS como si no hubiese páginas compartidas. Para fidelidad/continuidad, usar MP3 CBR/VBR, AAC, FLAC/WAV de 16/24 bits, pistas largas y clips cortos; probar altavoz, cable/USB y Bluetooth según existan. Comparar una variable por ensayo y capturar las fronteras de repetición: `isPlaying` y bitrate no sustituyen una medición de salida.

Una fase inicial de 30–60 minutos por candidato puede descartar regresiones; la configuración seleccionada necesita después una sesión prolongada en el dispositivo de destino. Los resultados deben acreditar que la app responde mejor o consume menos conservando calidad, marcadores y continuidad. Esa es la base para decidir los cambios cuando se retome la implementación.
