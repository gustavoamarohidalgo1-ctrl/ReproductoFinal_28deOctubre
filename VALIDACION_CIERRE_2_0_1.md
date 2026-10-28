# Cierre 2.0.1: menos trabajo repetido y guardado más resistente

Versión 2.0.1, código 11. Mantiene las mejoras de 2.0 y añade dos cambios acotados.
No cambia el motor, los bucles, los buffers, los formatos originales ni la política
de recuperación. La calidad del archivo permanece intacta.

## Textos de tiempo

Los tiempos de la lista y del reproductor se calculan mediante operaciones enteras
y un constructor de cadena, sin crear un `Formatter` general. El resultado conserva
exactamente el formato anterior, incluidas las duraciones de más de 24 horas.

Cada una de las dos etiquetas del reproductor guarda únicamente el último segundo
y texto mostrado. Si no cambian, no vuelve a formatear ni asignar ese texto al control.
La vista previa al arrastrar y la posición del reproductor utilizan el mismo estado,
de modo que volver al inicio o cancelar un gesto no deja el tiempo anterior visible.
El cambio entre duración desconocida, conocida y cola vacía también invalida el
texto cuando corresponde. No hay una caché global que crezca con la biblioteca.
Este ajuste actúa mientras la interfaz está activa; sus actualizaciones siguen
suspendiéndose al salir de la pantalla, como antes.

Las comprobaciones comparan el resultado con el formato anterior en límites y
1.000 valores `Long` adicionales. Una simulación de 86.400 actualizaciones en pausa
exige una sola asignación de texto. Eso cuenta trabajo evitado en esa situación;
no es una medición de autonomía ni una escucha de 24 horas.

## Apertura temprana del guardado

Antes, una escritura de cola preparaba hasta 20.000 entradas antes de intentar
abrir su archivo temporal. Cuando esa apertura fallaba, toda la codificación
había sido innecesaria. Ahora se abre primero la transacción atómica y sólo
entonces se prepara la cola.

El formato, los límites, la prioridad de la pista activa y el orden de la cola se
mantienen. El token y los índices confirmados sólo se publican después de verificar
el commit. Una pista activa inválida revierte la escritura abierta y conserva el
estado anterior. La limpieza también se ejecuta al propagarse un `Error` durante
la preparación, sin convertirlo silenciosamente en éxito.

Esta mejora evita trabajo cuando falla **la apertura**. No predice que el disco
se llenará posteriormente ni elimina el coste de una escritura normal. Los reintentos
con espera y los checkpoints habituales conservan sus intervalos.

## Alcance de las pruebas

Las pruebas de Android se ejecutan únicamente en el emulador temporal dedicado,
porque sustituyen la cola y crean obstáculos de almacenamiento deliberados.
El teléfono físico recibe sólo la actualización del APK, conservando sus datos.

Compilaciones debug y optimizada correctas. **86 pruebas unitarias aprobadas**,
sin omisiones ni fallos. Lint mantiene **0 errores y 22 advertencias**.

**36 pruebas de persistencia Android aprobadas**, sin fallos ni omisiones,
en el emulador API 37. Las tres nuevas comprueban apertura bloqueada, rollback
por pista activa inválida y propagación de un `AssertionError` durante la
preparación. Esta última verifica además el cierre del descriptor en
`/proc/self/fd`, incluyendo referencias a archivos eliminados.

La prueba nueva de apertura bloqueada se ejecutó primero contra el APK 2.0:
falló con `expected:<0> but was:<20000>`. Sobre 2.0.1 pasó con cero accesos a
las entradas durante el fallo. Después comprobó que el marcador anterior
seguía siendo recuperable y que retirar el bloqueo permitía guardar y
restaurar las 20.000 entradas. La evidencia anterior y posterior se conserva
separada en `before-fail-fast/` y `storage/`.

La prueba del WAV sparse de 27 horas comprueba además la pantalla real: búsqueda
en pausa a `25:00:12`, retorno a `0:00` y limpieza de ambas etiquetas al vaciar la
cola. También se aplica al APK optimizado mediante el módulo `validation`.
La suite optimizada terminó con **21 casos aprobados**, cero fallos y una prueba
optativa de duración omitida, en 219,299 s según su XML. Incluye reproducción de
320 kbps, recuperación, bucles, pausa y reinicios reales del proceso.

No se repite ni se presenta como nueva la medición comparada o el ensayo de cinco
minutos de 2.0. Sus resultados están en [VALIDACION_VERSION_2_0.md](VALIDACION_VERSION_2_0.md).
No se ha completado una escucha física de 24 horas en el Xiaomi.

## Evidencia local

La carpeta `app/build/reports/validation-2.0.1/` contiene el log de compilación,
`static-validation.json`, los XML y logs de persistencia, los APK anteriores
utilizados para comparar y la verificación de la firma del APK nuevo.

Comandos ejecutados con `ANDROID_HOME` configurado:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleBenchmark :app:assembleDebugAndroidTest :validation:assembleBenchmark
ANDROID_SERIAL=emulator-5592 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.reproductor.audiolibros.PlaybackStateStoreInstrumentedTest
ANDROID_SERIAL=emulator-5592 ./gradlew :validation:connectedBenchmarkAndroidTest
```

## APK e instalación

APK optimizado, firmado con la misma clave local y con depuración desactivada:
`app/build/outputs/apk/benchmark/Reproductor-2.0.1.apk`.
Tamaño: **2.936.495 bytes**.
SHA-256: `f40acaa0827369370e6a3fca18c08b11e26117da3ca048ad1ed530c7fcd50602`.

**Actualización completada en el Xiaomi 24117RN76L** mediante `adb install -r`.
Se verificaron `versionName=2.0.1` y `versionCode=11`, sin desinstalar ni borrar
datos. Se conservaron el permiso de audio y la excepción de ahorro de batería;
las notificaciones continúan desactivadas, respetando la configuración existente.
No había reproducción en primer plano antes de instalar.

La copia **Descargas/Reproductor-2.0.1.apk** del teléfono tiene el mismo SHA-256.
`phone-before.json` y `phone-install.json` registran la comprobación anterior y
posterior. Se cerró únicamente el emulador temporal al terminar las pruebas.
