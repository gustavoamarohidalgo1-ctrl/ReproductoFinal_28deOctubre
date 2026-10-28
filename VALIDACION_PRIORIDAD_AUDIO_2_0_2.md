# Continuidad de audio bajo carga en la versión 2.0.2

Nota de publicación: este informe conserva sus conclusiones históricas. Las referencias de líneas corresponden a la versión revisada; los enlaces al código abren los archivos actuales.

27 de octubre de 2026, America/Lima. Versión 2.0.2, código 12.

Esta versión se centra en los entrecortes al abrir otras aplicaciones que el usuario observa al escuchar por el altavoz de su Xiaomi Redmi Note 14 4G, modelo 24117RN76L. Amplía la capacidad de audio decodificado en la salida y reduce la prioridad del trabajo de biblioteca. Las pruebas funcionales pasan, incluida una ejecución con carga de CPU en un emulador. La reducción de los cortes en el Redmi todavía necesita medición en ese teléfono.

## Cambios aplicados

Media3 1.6.1 ya crea su hilo de reproducción con `THREAD_PRIORITY_AUDIO`. Se conserva ese mecanismo, el servicio en primer plano, el proceso independiente y `WAKE_MODE_LOCAL`. Una app normal no obtiene una reserva exclusiva de CPU ni prioridad absoluta frente al sistema o los límites térmicos del dispositivo. [Fuente de Media3 1.6.1](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/PlaybackLooperProvider.java).

La nueva [ContinuousAudioRenderersFactory](app/src/main/java/com/reproductor/audiolibros/ContinuousAudioRenderersFactory.kt) conserva la construcción del `DefaultAudioSink` y sus opciones de salida flotante y parámetros de reproducción. Cambia el objetivo de búfer PCM de 250–750 ms a 750–1.500 ms. El mínimo de plataforma y los mecanismos de recuperación del sink siguen siendo aplicables: estos valores son una solicitud de capacidad, no una garantía de audio disponible ni un límite rígido universal. [Política original de Media3](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioTrackBufferSizeProvider.java).

La precarga del archivo de 60–120 segundos permanece igual. Ese búfer contiene datos antes de la salida; ampliar la reserva PCM busca tolerar retrasos breves al alimentar `AudioTrack`. Aumenta el consumo de memoria de salida según el formato y puede afectar la latencia de inicio en algunos dispositivos. No cambia bitrate, muestras, velocidad, tono, volumen digital, recorte gapless ni repetición.

El trabajador de consultas, diferencias de biblioteca y metadatos usa ahora [BackgroundThreadFactory](app/src/main/java/com/reproductor/audiolibros/BackgroundThreadFactory.kt). Establece prioridad de fondo dentro del hilo trabajador. La prioridad del hilo llamante y la del motor no se modifican; tampoco se rebaja la prioridad del executor que restaura o guarda la sesión. La biblioteca puede tardar más bajo saturación. [Guía de Android sobre prioridades](https://developer.android.com/topic/performance/threads).

[AudioOutputDiagnostics](app/src/main/java/com/reproductor/audiolibros/AudioOutputDiagnostics.kt) recoge vaciados del búfer de salida, errores del sink y configuración de `AudioTrack`. Solo mantiene contadores y últimos valores en memoria. Publica cambios por la sesión privada agrupando eventos durante un segundo y limita el log de underruns a una entrada cada 30 segundos. Los contadores sobreviven a una reconstrucción del reproductor dentro del mismo servicio. No provocan reinicios ni escriben archivos de diagnóstico durante reproducción normal.

El ensayo de continuidad incorpora esos contadores y exige dos checkpoints distintos que correspondan al ID, generación y token de su propia cola. Su lector inspecciona solo el archivo publicado; no usa `AtomicFile.openRead()` sobre el estado del servicio, para evitar interferir con una escritura concurrente.

## Resultados comprobados

Entorno: Android 15/API 35 ARM64, emulador temporal `emulator-5564`, 2 GiB de RAM, sin ventana, sin salida de audio del anfitrión y sin snapshots. Solo ese emulador recibió instalaciones y pruebas; el Redmi no estaba conectado.

| Comprobación | Resultado |
| --- | --- |
| Compilación debug y benchmark con R8 | Correcta |
| Pruebas unitarias | 86 aprobadas; 0 fallos, errores u omisiones |
| Pruebas Android debug seleccionadas | 26 aprobadas; 0 fallos u omisiones |
| Suite Android sobre APK optimizado | 22 aprobadas; 0 fallos; 1 ensayo optativo omitido |
| Lint final | 0 errores; 21 advertencias |
| Guardado bloqueado durante ensayo de 10 s | Falló como se esperaba: no observó dos checkpoints de su sesión |
| Mismo ensayo con guardado disponible | Aprobado; 3 checkpoints observados |
| Ensayo optimizado de 60 s con carga de CPU | Aprobado; 4 vueltas y 13 checkpoints |

Las pruebas debug comprueban la prioridad del trabajador, la capacidad PCM para varios formatos, la independencia de las instantáneas de diagnóstico y la configuración que llega al servicio real. También comprueban identidad de muestras float tras recorte de delay/padding, selección, búsqueda, pausa, detención, bucles cortos, archivos inaccesibles y muerte del proceso. La suite optimizada verifica el comportamiento por IPC con R8 activo.

La prueba de guardado bloqueado es un control negativo deliberado: su `FAILURES` demuestra que el arnés rechaza la condición incorrecta. El bloqueo se retiró antes del ensayo sano. No se cuenta como un fallo inesperado de las suites de la aplicación.

En el ensayo de carga se ejecutaron dos procesos de cálculo en shell con un límite de 75 segundos. Una captura de `top` los mostró utilizando aproximadamente un núcleo cada uno. El MP3 sintético de 320 kbps se reprodujo durante 60.001 ms medidos por reloj monotónico. Los contadores publicados al final fueron 0 underruns y 0 errores del sink; no hubo errores del reproductor. La capacidad de salida informada fue de 132.300 bytes, correspondiente a 750 ms de PCM estéreo de 16 bits a 44,1 kHz.

Los contadores de sesión pueden publicarse con retraso bajo carga y no equivalen a una captura física. El archivo de prueba es silencio sintético y el emulador no tiene salida audible. El resultado prueba funcionamiento bajo esa carga concreta; no acredita ausencia de cortes en el altavoz real, una mejora porcentual frente a 2.0.1 ni una sesión de 24 horas.

## APK y evidencia

APK optimizado para instalar: Reproductor 2.0.2 (`app/build/outputs/apk/benchmark/Reproductor-2.0.2.apk`, referencia local histórica).

- Tamaño: 2.937.799 bytes.
- SHA-256: `71eaa66af71718d7bd098d9468644ad8454c9f8787a9f4434a60f90b85f37fe2`.
- Firma local de desarrollo verificada. El APK de validación usa el mismo certificado.
- El APK de aplicación no activa depuración; el arnés de pruebas sí la utiliza.

El [resumen de evidencia (archivo de evidencias)](https://github.com/gustavoamarohidalgo1-ctrl/Reproductor_28_Octubre/releases/download/v2.1.0/Evidencias-2.1.0.zip) acompaña los logs de compilación, XML de pruebas, manifiestos binarios, firmas, control negativo de guardado, captura de CPU y JSON del ensayo. La carpeta conserva además el APK anterior compilado localmente para una comparación posterior.

## Comprobación pendiente en el Redmi

En el teléfono, utilizar el botón **Evitar restricciones de batería** y comprobar la autorización real de Android. Durante la comparación conviene mantener desactivado el ahorro de batería, ya que Xiaomi documenta que limita actividades y procesos en segundo plano en este modelo. [Ayuda oficial del Redmi Note 14](https://www.mi.com/uk/support/faq/details/KA-529972/).

Comparar con el mismo audio audible, altavoz, volumen y secuencia de aplicaciones que suele provocar los cortes. Alternar varias repeticiones entre versiones, manteniendo temperatura y nivel de batería comparables; registrar los instantes de corte y los eventos de salida. Un ensayo inicial de 15–30 minutos permitirá decidir si esta reserva es suficiente o si hace falta investigar foco, decodificador, almacenamiento o limitación térmica en ese dispositivo. Las pruebas que reemplazan la cola o bloquean el almacenamiento deben seguir reservadas al entorno de pruebas.
