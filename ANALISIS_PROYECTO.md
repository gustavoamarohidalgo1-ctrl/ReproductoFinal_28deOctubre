**Análisis técnico de Reproductor — 3 de septiembre de 2026**

Este documento describe el estado anterior a la implementación posterior de
controles exclusivos dentro de la aplicación. Algunos hallazgos y números de
línea cambiaron con esa implementación; se conserva como registro de la auditoría.
Los enlaces de fuentes apuntan a los archivos actuales; los números de línea
corresponden a la revisión original. La evidencia de `app/build/` se generó
localmente y no forma parte del repositorio.

La aplicación tiene una base pequeña y comprensible para reproducir archivos locales en Android. La separación entre interfaz, servicio, consulta de archivos y preferencias es adecuada para su tamaño. Sin embargo, presenta problemas de consumo de batería, control de acceso a la sesión, recuperación de reproducción y coherencia entre las funciones anunciadas y las implementadas. Corregirlos aporta más valor inmediato que ampliar la interfaz o incorporar nuevas funciones.

Este análisis no modifica el código de la aplicación. Combina inspección completa de fuentes y recursos, compilación, Android Lint, documentación oficial y comprobaciones en un emulador Android 15. Las conclusiones distinguen observaciones de ejecución, defectos demostrables por código y riesgos que necesitan más validación.

**1. Alcance y arquitectura**

El proyecto contiene un módulo Android, cuatro archivos Kotlin y 765 líneas Kotlin. Utiliza vistas XML con ViewBinding, AppCompat, Material Components, RecyclerView y Media3 ExoPlayer/Session 1.5.1. El mínimo es Android 8.0/API 26; compileSdk y targetSdk son 35. Gradle es 8.11.1, AGP 8.7.3 y Kotlin 2.0.21. Compila a bytecode JVM 17; la ejecución de Gradle en esta auditoría utilizó el JBR 21 disponible en el equipo.

| Componente | Responsabilidad actual | Observación |
| --- | --- | --- |
| [MainActivity.kt](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt) | Permisos, biblioteca, controlador, reproducción seleccionada y actualización visual | Concentra también persistencia y parte de la política de reproducción. |
| [PlaybackService.kt](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) | ExoPlayer, MediaSession, reproducción en segundo plano y guardado periódico | Debería ser el único responsable de las reglas de reproducción y del progreso. |
| [AudioStore.kt](app/src/main/java/com/reproductor/audiolibros/AudioStore.kt) | Consulta de MediaStore y creación de modelos | Usa URIs y cierra correctamente cursores/descriptores. |
| [PositionStore.kt](app/src/main/java/com/reproductor/audiolibros/PositionStore.kt) | Marcadores de escucha y modo de repetición | Los marcadores sí se almacenan; el modo de repetición es una constante. |

Flujo principal: permiso → MediaStore → lista de AudioItem → selección → MediaController → MediaSessionService → ExoPlayer. El servicio guarda progreso cada diez segundos y la Activity fuerza algunos guardados al pausar su ciclo de vida o seleccionar un archivo.

La carpeta recibida no contiene metadatos Git. Por ello no se pudo revisar historial, ramas ni evolución de decisiones. No se encontraron instrucciones AGENTS.md aplicables ni configuración de integración continua en los archivos entregados.

**2. Resultados de validación**

Se ejecutó `./gradlew assembleDebug lintDebug testDebugUnitTest --console=plain`.

| Comprobación | Resultado | Interpretación |
| --- | --- | --- |
| assembleDebug | Correcta; tareas de compilación mayormente UP-TO-DATE | Existe un APK debug instalable. No fue una reconstrucción limpia. |
| lintDebug | Falló: 9 errores y 26 advertencias | Los nueve errores son UnsafeOptInUsageError de APIs Media3. |
| testDebugUnitTest | NO-SOURCE | No existen pruebas unitarias que esta tarea pudiera ejecutar. |
| Instalación y apertura en Android 15/API 35 | Correctas | La aplicación muestra la biblioteca y reproduce los WAV de prueba. |
| Wake lock sin reproducción y en pausa | Defecto observado | El bloqueo manual permanece adquirido. |
| Recuperación de posición mediante siguiente/anterior | Defecto observado | El archivo volvió a 0 ms pese a tener un marcador de 31.262 ms. |
| Archivos retirados durante la sesión | Inconclusa | Una observación de tres segundos no produjo errores en el log filtrado; no confirma ni descarta el bucle identificado en el código. |
| Duración de 24 horas y salida Hi-Res real | No realizadas | No se certifican autonomía, continuidad prolongada ni fidelidad de una salida física. |

El emulador se inició en modo temporal con `-read-only -no-snapshot`, se utilizaron dos WAV silenciosos de 90 segundos y se cerró al terminar. No se utilizó un teléfono físico.

Evidencia local: Android Lint (evidencia local: `app/build/reports/lint-results-debug.html`), resultados de reproducción (evidencia local: `app/build/reports/project-audit/runtime-checks.json`), wake locks al inicio (evidencia local: `app/build/reports/project-audit/idle-power.txt`) y sesión inicialmente vacía (evidencia local: `app/build/reports/project-audit/idle-session.txt`). Estos archivos están bajo build y pueden desaparecer al limpiar el proyecto.

**3. Hallazgos prioritarios**

P1 indica un problema que conviene resolver antes de distribuir una versión considerada estable. P2 indica un defecto funcional o de robustez relevante. P3 identifica mejoras de experiencia o mantenimiento. La prioridad no implica que todos los escenarios se hayan reproducido.

**A. [P1] Bloqueo de CPU adquirido durante toda la vida del servicio. Confirmado en ejecución.**

[PlaybackService.kt:49](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) crea un PARTIAL_WAKE_LOCK y llama a acquire sin timeout. Solo se libera en onDestroy, en la línea 137. La conexión de MainActivity inicia el servicio aunque todavía no haya ninguna pista.

En el emulador apareció `Reproductor:PlaybackWakeLock` con la sesión en NONE y cola vacía. Después de reproducir y pausar, el bloqueo manual seguía activo y el bloqueo administrado por ExoPlayer ya no figuraba como adquirido. Esto mantiene innecesariamente la CPU despierta mientras el servicio permanece vivo. No se midió el porcentaje de consumo de batería.

Corrección: utilizar la gestión existente mediante WAKE_MODE_LOCAL. Cualquier bloqueo adicional debe tener una necesidad demostrada y liberarse en los estados que no lo requieren.

**B. [P1] La sesión acepta controladores externos sin una política de acceso. Confirmado por código y fuente de la dependencia.**

El [servicio exportado](app/src/main/AndroidManifest.xml) devuelve la sesión a cualquier controlador en [PlaybackService.kt:132](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt). La construcción de la sesión, en la línea 104, no establece un callback de autorización.

Se verificó específicamente Media3 **1.5.1**: su callback predeterminado acepta conexiones y ofrece todos los comandos disponibles del reproductor. Una aplicación que se conecte puede observar metadatos y enviar órdenes como pausa o desplazamiento. El proyecto introduce toda la biblioteca en la cola, ampliando los metadatos visibles. No se ejecutó una prueba con un APK atacante. [Fuente oficial de MediaSession 1.5.1](https://raw.githubusercontent.com/androidx/media/1.5.1/libraries/session/src/main/java/androidx/media3/session/MediaSession.java).

Corrección: reconocer el UID propio y los controladores confiables necesarios, limitar comandos y validar los medios aceptados. El atributo exported por sí solo no es un defecto: tiene usos legítimos en reproducción multimedia. El problema es la ausencia de una política explícita con esta versión concreta.

**C. [P1] Recuperación de errores sin límite ni espera. Confirmado por código; ejecución no reproducida.**

[PlaybackService.kt:93](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) ignora la causa del error, selecciona otra pista o la primera y vuelve a ejecutar prepare/play. Un único archivo permanentemente ilegible, o una cola donde todos fallan, no tiene condición de salida.

Consecuencia posible: repetición de intentos, actividad de CPU y ausencia de audio sin un mensaje útil. La prueba breve de retirada de archivos no produjo una reproducción concluyente de este escenario; el diagnóstico se apoya en la ausencia de límites en el manejador.

Corrección: clasificar fallos recuperables, limitar intentos por pista y por recorrido de cola, introducir espera cuando corresponda y mostrar un estado terminal si no queda ningún archivo reproducible.

**D. [P2] El modo de repetición anunciado no se puede elegir ni persistir. Confirmado por código.**

[PositionStore.kt:25](app/src/main/java/com/reproductor/audiolibros/PositionStore.kt) siempre devuelve REPEAT_MODE_ONE. El setter descarta el argumento y también escribe ONE. La interfaz únicamente expone un botón de reproducción/pausa; existen recursos de repetición y salto que no se utilizan.

Además, [PlaybackService.kt:82](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) y [MainActivity.kt:51](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt) fuerzan el reinicio al recibir STATE_ENDED. Corregir únicamente la preferencia no basta para respetar el modo apagado.

Corrección: definir si el producto pretende repetir siempre una sola pista. Si mantiene las funciones del README, implementar ONE/ALL/OFF reales y delegar los finales normales de reproducción al modo de ExoPlayer. Si la repetición fija es deliberada, actualizar las promesas y simplificar el código.

**E. [P2] La memoria por archivo depende del camino utilizado para navegar. Confirmado en ejecución.**

La restauración mediante getPosition solo aparece en [MainActivity.kt:228](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt), al pulsar una fila. Siguiente/anterior desde los controles multimedia no utiliza ese código.

Prueba: reproducir Audit-A, avanzar treinta segundos, abrir/cerrar la pantalla para forzar el guardado, comprobar el marcador de 31.262 ms, enviar siguiente y anterior desde los controles multimedia y pausar. Audit-A quedó en 0 ms. La preferencia existía; ese camino de navegación no la restauró.

También hay un problema de guardado: [PlaybackService.kt:122](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) solo escribe cuando isPlaying y STATE_READY son verdaderos. Una pausa externa puede dejar sin guardar el tramo desde la última escritura; un desplazamiento estando pausado puede perderse por completo. El último valor usado para limitar escrituras tampoco distingue entre archivos.

Corrección: centralizar progreso y restauración en el servicio, guardar en eventos relevantes y definir el comportamiento de siguiente/anterior para audiolibros. Mantener guardado periódico para tolerar cierres inesperados.

**F. [P2] Recrear el servicio no restaura la reproducción. Confirmado por código.**

[PlaybackService.kt:45](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) crea un reproductor vacío. No se guardan la última cola ni el índice o identificador del último elemento activo. START_STICKY no reconstruye esos datos. Tras recrear el proceso, el marcador individual puede recuperarse al seleccionar manualmente el archivo, pero no existe restauración automática de la sesión.

Corrección: persistir el contexto mínimo de reproducción y definir cuándo se restaura y cuándo se inicia audio. Para reanudación desde controles del sistema, implementar el mecanismo de playback resumption apropiado. [Guía oficial de reproducción en segundo plano](https://developer.android.com/media/media3/session/background-playback).

**G. [P2] Gestión de foco y desconexión de auriculares desactivada deliberadamente. Decisión de producto con consecuencias.**

[PlaybackService.kt:65](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) desactiva audio focus y audio becoming noisy. La app no gestiona esas interrupciones mediante ExoPlayer. Al desconectar auriculares puede continuar por el altavoz; también puede solaparse con otras aplicaciones. No se probaron llamadas ni Bluetooth físico.

Los comentarios indican que la decisión busca evitar interrupciones, aunque el comentario inicial del archivo describe lo contrario. Conviene definir el comportamiento esperado y probarlo. La gestión automática de foco y la pausa al desconectar son opciones apropiadas para una experiencia convencional de audiolibros. [Documentación de audio focus](https://developer.android.com/media/optimize/audio-focus).

**H. [P2] La biblioteca no se actualiza y su consulta no maneja errores. Confirmado por código.**

[MainActivity.kt:204](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt) inicia un Thread sin cancelación, manejo de errores ni comprobación del ciclo de vida antes de publicar resultados. [AudioStore.kt:83](app/src/main/java/com/reproductor/audiolibros/AudioStore.kt) tampoco captura fallos de consulta o cursor. Una excepción en ese hilo queda sin manejar.

La carga solo se inicia al crear la pantalla o tras conceder permisos. No existe observación de MediaStore ni recarga voluntaria: los archivos agregados pueden no aparecer y los eliminados pueden seguir visibles. Rotar durante una consulta lenta mantiene temporalmente la Activity anterior y actualiza sus vistas al finalizar.

Corrección: consulta cancelable asociada al ciclo de vida, contexto de aplicación para acceso a datos, estados de carga/error y una estrategia de actualización de la biblioteca. No hace falta introducir una arquitectura extensa para resolverlo.

**I. [P2] Controles que no representan correctamente ciertos estados. Confirmado por código.**

En [MainActivity.kt:242](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt), el botón decide entre play y pause usando isPlaying. Durante BUFFERING puede ser falso aunque exista intención de reproducir; pulsar el botón vuelve a pedir play y no cancela esa intención. Conviene considerar playWhenReady y presentar el estado de preparación.

En [MainActivity.kt:253](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt), seekTo solo se ejecuta al finalizar un gesto táctil. Los cambios de SeekBar con teclado o accesibilidad no pasan por ese final de gesto, por lo que pueden cambiar la etiqueta sin desplazar el audio. Deben procesarse también esos cambios de usuario. [Implementación oficial de AbsSeekBar](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/widget/AbsSeekBar.java).

**J. [P2] Las afirmaciones de calidad son más amplias que la evidencia disponible.**

[README.md:18](README.md) promete conservar resolución nativa. Activar float output en [PlaybackService.kt:63](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt) solicita una ruta disponible de salida flotante; no verifica toda la cadena de mezcla, dispositivo y salida. La etiqueta de [MainActivity.kt:282](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt) usa el formato de entrada. El bitrate calculado por tamaño/duración incluye la estructura del archivo y es aproximado. No se está midiendo la calidad efectiva de salida. [API oficial de float output](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory#setEnableAudioFloatOutput(boolean)).

Además, OGG es un contenedor y no garantiza compresión sin pérdida; la descripción del README debe distinguir contenedor y códec. El soporte FLAC universal tampoco queda cubierto en todo el mínimo Android: sin extensión de decodificación, la disponibilidad depende del dispositivo en API 26; MediaCodec FLAC es obligatorio desde API 27 según la documentación de Media3. No se probó un dispositivo API 26 afectado. [Formatos compatibles](https://developer.android.com/media/media3/exoplayer/supported-formats#progressive-container-formats).

Corrección: etiquetar los datos como propiedades del archivo, presentar bitrate aproximado cuando se calcule y limitar las garantías a resultados medidos en formatos y dispositivos concretos.

**K. [P2] Lint bloquea el control de calidad del proyecto. Confirmado al ejecutar.**

Los nueve errores se concentran en [MainActivity.kt:295](app/src/main/java/com/reproductor/audiolibros/MainActivity.kt) y en usos de APIs de ExoPlayer en [PlaybackService.kt:63](app/src/main/java/com/reproductor/audiolibros/PlaybackService.kt). Requieren reconocer explícitamente el uso de UnstableApi.

Corrección: realizar el opt-in con el alcance mínimo necesario, revisar los usos concretos y repetir Lint. Las 26 advertencias incluyen wake lock sin timeout, petición de excepción de batería, servicio exportado, dependencias con versiones posteriores, recursos sin uso y textos incrustados. Una advertencia de Lint requiere interpretación: por ejemplo, exportar una sesión multimedia puede ser correcto y una versión antigua no demuestra una vulnerabilidad.

**4. Mejoras secundarias y de experiencia**

| Prioridad | Observación | Acción sugerida |
| --- | --- | --- |
| P2 | La excepción de batería se vuelve a pedir al recrear la Activity si no fue concedida; README describe un botón que no existe. MainActivity:113,184. | Hacer la opción contextual, respetar la negativa y corregir instrucciones. |
| P2 | Tras denegación persistente, el botón vuelve a solicitar permiso sin orientar a ajustes; al volver de ajustes no se reevalúa en onResume. MainActivity:175. | Añadir recuperación del estado de permiso y recarga. |
| P2 | future.get no maneja fallo/cancelación de conexión al controlador. MainActivity:128. | Capturar el fallo y mostrar un estado recuperable. No se observó un crash por rotación. |
| P3 | La carga de biblioteca puede terminar después de conectar el controlador y no refresca bitrate. MainActivity:207,282. | Actualizar los datos de la pista actual al publicar la biblioteca. |
| P3 | Se sustituye y prepara toda la cola al seleccionar cualquier fila. MainActivity:228. | Reutilizar la cola y desplazar al índice cuando sea equivalente. Medir antes de optimizaciones mayores. |
| P3 | notifyDataSetChanged invalida la lista para cambiar el resaltado. MainActivity:404. | Actualizar las filas anterior/actual o utilizar diferencias. |
| P3 | Orden textual de títulos puede producir capítulos 1, 10, 2. AudioStore:76. | Elegir orden natural, número de pista o criterio configurable. |
| P3 | Audio actual indicado solo por color; descripción genérica de play/pausa y SeekBar sin etiqueta de finalidad. | Exponer estado seleccionado y acciones accesibles. |
| P3 | Tiempo y barra conservan valores anteriores cuando la duración es desconocida. MainActivity:343. | Representar duración desconocida y reiniciar datos obsoletos. |

El código no muestra servidor, inicio de sesión, publicidad ni analítica. La consulta utiliza una selección fija y URIs de MediaStore. No se encontró una ruta de envío de archivos a Internet en las fuentes revisadas. El riesgo concreto de acceso externo descrito arriba es la sesión multimedia exportada.

No se detectó una incompatibilidad estática de AGP 8.7.3 con SDK 35, Gradle 8.11.1 y compilación JVM 17. [Compatibilidad oficial de AGP 8.7](https://developer.android.com/build/releases/agp-8-7-0-release-notes).

Tampoco corresponde atribuir un corte obligatorio a las seis horas a este servicio: el límite de Android 15 documentado para dataSync/mediaProcessing no es un límite general de mediaPlayback. [Límites oficiales de servicios](https://developer.android.com/develop/background-work/services/fgs/timeout). El layout sí declara fitsSystemWindows y la apertura en la tableta API 35 funcionó; falta una matriz visual completa de teléfonos, orientaciones y tamaños de fuente.

Las notificaciones asociadas a MediaSession están exentas del permiso general POST_NOTIFICATIONS. Pedirlo añade fricción, pero denegarlo no implica por sí solo que fallen los controles multimedia. [Exención oficial](https://developer.android.com/develop/ui/compose/notifications/notification-permission#media-sessions).

**5. Orden recomendado de trabajo**

1. Corregir la duración del wake lock, limitar reintentos y establecer la política de acceso de la sesión. Resolver los errores de Lint.
2. Centralizar en el servicio el guardado, la restauración y las reglas de repetición. Definir el comportamiento exacto del producto ante pausa, final de pista e interrupciones.
3. Implementar actualización de biblioteca, recuperación de permisos y fallos de controlador, y trabajo asociado al ciclo de vida.
4. Corregir accesibilidad, estados de preparación y documentación de calidad. Revisar dependencias en una actualización controlada.
5. Añadir pruebas que protejan estas reglas antes de ampliar funciones.

Pruebas prioritarias: ONE/ALL/OFF y persistencia del valor elegido; pausa/siguiente/anterior desde notificación; guardado al desplazarse estando pausado; error permanente con una sola pista y con toda la cola ilegible; recreación del proceso y restauración de sesión; permisos concedidos/rechazados desde ajustes; archivos agregados y SD retirada; teclado/TalkBack; pantalla apagada, desconexión de auriculares y ensayo prolongado de reproducción.

La afirmación de 24 horas requiere una prueba de duración real, con condiciones y dispositivos documentados. El emulador utilizado valida comportamiento funcional, pero no consumo físico de batería, estabilidad Bluetooth ni calidad de un DAC.

La estructura actual permite abordar estos problemas de forma incremental. No se necesita una reescritura completa: el mayor beneficio consiste en dar al servicio una responsabilidad clara sobre el estado de reproducción y eliminar reglas contradictorias repartidas entre servicio, Activity y preferencias.
