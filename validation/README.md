# Validación del APK optimizado

Este módulo ejecuta las pruebas de servicio/reinicio/continuidad mediante el protocolo
Media3 contra `:app:benchmark`, con R8 y reducción de recursos activos. Las fuentes se
comparten con las pruebas debug mediante `stageServiceTests`; no hay una segunda copia
mantenida manualmente ni dependencia de clases internas del APK de producción.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :validation:connectedBenchmarkAndroidTest
```

El controlador y runner del test llevan sus propias bibliotecas. AGP8.7.3 normalmente
excluye dependencias compartidas con el APK, aunque R8 haya eliminado sus clases; esto
hace fallar el runner antes de empezar. `self-instrumenting` evita esa exclusión. Como
este modo también genera un targetPackage propio, el adaptador documentado de
`ProcessTestManifest` fija el target a `com.reproductor.audiolibros` y comprueba que exista
exactamente uno. Es una adaptación del arnés ligada a AGP8.7.3; hay que verificarla al
actualizar AGP. No cambia el APK, firma, exportación del servicio ni controles de UID.

Antes de validar una actualización del arnés, comprueba el manifiesto binario con
`aapt2 dump xmltree validation-benchmark.apk --file AndroidManifest.xml`: el paquete
del test debe ser `com.reproductor.audiolibros.validation` y el target de instrumentation
`com.reproductor.audiolibros`. Ambos APK se firman con la misma clave local de pruebas.

Las pruebas directas de almacenamiento y del sink float/gapless permanecen en
`:app:connectedDebugAndroidTest`; este módulo comprueba el motor optimizado a través
de su servicio real. El ensayo prolongado es optativo, activado con
`continuitySoakSeconds`; sin ese argumento se omite deliberadamente.
