# Guía de troubleshooting

Cada caso sigue el mismo formato: **síntoma → causa probable → cómo reproducirlo → diagnóstico → solución → cómo verificar**. Los logs que se citan salieron de ejecuciones reales de este laboratorio (Windows 11, Java 25.0.4, Spring Boot 4.1.1). En Linux o macOS los mensajes del sistema operativo cambian; por ejemplo, `Connection refused` en lugar del texto en español de Windows.

Herramientas que se usan en toda la guía:

| Herramienta | Para qué |
|---|---|
| `/actuator/health` | ¿La API ve al core? (`components.coreBanking`) |
| `/actuator/metrics/<nombre>` y `/actuator/prometheus` | latencia, errores, pool y JVM |
| `/actuator/threaddump` o `jcmd <pid> Thread.print` | qué hace cada hilo de plataforma |
| `jcmd <pid> Thread.dump_to_file -format=json f.json` | volcado que **incluye los hilos virtuales** |
| `jcmd <pid> JFR.start duration=60s filename=rec.jfr` | eventos `jdk.VirtualThreadPinned`, CPU, GC y asignación de memoria |
| `jcmd <pid> GC.heap_info` | uso del heap en ese momento |
| perfil `demo` | logs DEBUG con el hilo de cada llamada al core |

Métricas del pool de conexiones hacia el core:

| | WebFlux (Reactor Netty) | Virtual Threads (Apache HttpClient 5) |
|---|---|---|
| en uso | `reactor.netty.connection.provider.active.connections` | `httpcomponents.httpclient.pool.total.connections{state=leased}` |
| libres | `reactor.netty.connection.provider.idle.connections` | `httpcomponents.httpclient.pool.total.connections{state=available}` |
| esperando conexión | `reactor.netty.connection.provider.pending.connections` | `httpcomponents.httpclient.pool.total.pending` |
| máximo | `reactor.netty.connection.provider.max.connections` | `httpcomponents.httpclient.pool.total.max` |

---

## 1. `WebClient` (o `RestClient`) con errores de conexión

**Síntoma:** la API responde `503 DOWNSTREAM_UNAVAILABLE` y `/actuator/health` responde 503 con `coreBanking: DOWN`.

**Causas probables:** el core está caído o en otro puerto (`banking.downstream.base-url` mal configurada), o el core rechaza conexiones nuevas porque su *accept backlog* está lleno durante una ráfaga.

**Reproducir:**
```bash
# a) core caído: parar el mock (o arrancar la API apuntando a un puerto vacío)
java -jar banking-api-webflux/target/banking-api-webflux-1.0.0-SNAPSHOT.jar --banking.downstream.base-url=http://127.0.0.1:1
curl -s localhost:8081/api/clients/C0001/financial-summary
```
Test automático: `CoreUnavailableApiTest` (en ambas APIs).

**Logs esperados:**
```text
WebFlux: /api/clients/C0001/financial-summary -> DOWNSTREAM_UNAVAILABLE (operación=clients,
         causa=java.net.ConnectException: Connection refused: getsockopt)
VT:      ... DOWNSTREAM_UNAVAILABLE (operación=balances,
         causa=org.apache.hc.client5.http.HttpHostConnectException: Connect to http://127.0.0.1:1 failed: Connection refused: getsockopt)
```

**Variante real encontrada al desarrollar el laboratorio, "Connection refused" solo bajo ráfagas:** el core falso de los tests (`HttpServer` del JDK) se creó al principio con el backlog por defecto del sistema operativo (~50 en Windows). Con 100 peticiones simultáneas, Reactor Netty abrió unas 300 conexiones nuevas a la vez y 40 recibieron `Connection refused` aunque el servidor estaba arriba. **Solución:** aumentar el backlog en el servidor (`HttpServer.create(addr, 1024)`; en Tomcat, `server.tomcat.accept-count`; en Linux, también `net.core.somaxconn`) y/o precalentar el pool de conexiones del cliente.

**Variante real 2, arranque en frío de dos APIs a la vez contra el mock real:** con las dos APIs recién arrancadas y 150 req/s a cada una desde el primer segundo, hubo un **13–16 % de `Connection refused` desde el mock** (`HttpHostConnectException` / `ConnectException`). Medición aislando variables:

| Condición | Errores |
|---|---|
| 1 API caliente (con tracing y Docker en marcha) | 0 % |
| 2 APIs a la vez, pools calientes | 0 % |
| 2 APIs a la vez, **pools en frío** | 13–16 % |

Los dos pools vacíos intentan abrir cientos de conexiones en el mismo segundo y el mock (en la misma máquina Windows) no las acepta a tiempo. Subir el `SO_BACKLOG` del mock a 4096 **no mejoró nada medible** y se descartó. La solución efectiva es **calentar** (`SCENARIO=warmup`) antes de medir o de enseñar gráficos, que es lo que ya hace `run-comparison`. En producción el equivalente es precalentar los pools de conexiones o arrancar con poco tráfico (*slow start* en el balanceador).

**Diagnóstico:** `curl -v <base-url>/actuator/health` desde la máquina de la API. Si falla, el problema es de red o del core. Si funciona pero hay errores bajo carga, revisa el backlog y los límites de conexiones del core.

**Verificar:** `coreBanking: UP` en el health y `compare-contracts.sh` en verde.

---

## 2. Event loop bloqueado en WebFlux

**Síntoma:** con carga moderada, **todas** las rutas de la API WebFlux se vuelven lentas, incluidas las que no tienen nada que ver con la llamada lenta. La CPU está baja y no hay errores en los logs.

**Causa:** una llamada bloqueante (JDBC, SDK SOAP, `Thread.sleep`, `InputStream.read`, `synchronized` con contención, `block()`) se ejecuta en un hilo del event loop (`reactor-http-nio-N`). Solo hay unos pocos de esos hilos (≈ nº de cores) y mientras uno está bloqueado no atiende a ninguna de sus conexiones.

**Reproducir** (perfil `demo`):
```bash
PROFILE=demo ./scripts/start-all.sh
curl -s "localhost:8081/api/demo/legacy-score/C0001?mode=event-loop"
# {"mode":"event-loop","thread":"Thread[#66,reactor-http-nio-12,5,main]",...}   <- bloqueando el event loop
curl -s "localhost:8081/api/demo/legacy-score/C0001?mode=block"
# 500 INTERNAL_ERROR
```
Log de `mode=block` (Reactor **sí** lo detecta):
```text
java.lang.IllegalStateException: block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-nio-14
```
`Thread.sleep` o un driver JDBC **no** producen ningún error. Para ver el impacto hay que meter carga:
```bash
k6 run -e BASE_URL=http://localhost:8081 -e MODE=event-loop load-tests/blocking-demo.js
k6 run -e BASE_URL=http://localhost:8081 -e MODE=offload    load-tests/blocking-demo.js
k6 run -e BASE_URL=http://localhost:8082 -e MODE=direct     load-tests/blocking-demo.js
```

**Resultados medidos** (30 s, 100 req/s bloqueantes de 300 ms + 20 req/s a `/financial-summary`, 20 hilos de event loop en esta máquina, lo que da un techo de ~66 llamadas bloqueantes por segundo):

| Configuración | legacy-score avg / p95 | `/financial-summary` (víctima) avg / p95 / p99 | errores en la víctima |
|---|---|---|---|
| WebFlux `mode=event-loop` (antipatrón) | 2938 / 15 517 ms | **6471 / 25 669 / 26 847 ms** | **45,3 %** |
| WebFlux `mode=offload` (`boundedElastic`) | 309 / 316 ms | 637 / 797 / 810 ms | 0 % |
| VT `mode=direct` (bloqueo en el hilo virtual) | 304 / 311 ms | 641 / 802 / 811 ms | 0 % |

Con el event loop bloqueado, un endpoint que no tiene nada que ver (`/financial-summary`) pasa de ~640 ms a varios segundos. La mayoría de los errores son peticiones que k6 abandonó a los 30 s. También hubo 66 `DOWNSTREAM_TIMEOUT` **espurios**: el core respondió a tiempo, pero **WebClient comparte el event loop bloqueado** y no procesó la respuesta antes de que venciera el `response-timeout`. Bloquear el event loop rompe también el cliente HTTP, no solo el servidor. El mismo código bloqueante en VT no causa ningún problema.

**Diagnóstico:**
1. Thread dump durante la carga: `curl -s localhost:8081/actuator/threaddump -H "Accept: text/plain" | grep -A6 "reactor-http-nio"`. Si un hilo `reactor-http-nio-*` está en `TIMED_WAITING`/`WAITING`/`BLOCKED` dentro de código de aplicación (`Thread.sleep`, `SocketInputStream.read`, `HikariPool.getConnection`...), ese es el culpable.
2. Logs con el nombre del hilo: si una operación de I/O "lenta" aparece en `reactor-http-nio-*`, está bloqueando.
3. [BlockHound](https://github.com/reactor/BlockHound) detecta llamadas bloqueantes en los hilos no bloqueantes y lanza una excepción. **No se incluyó en el laboratorio** porque necesita un agente Java (`-XX:+AllowRedefinitionToAddDeleteMethods`) y no se ha verificado su compatibilidad con Java 25. Si se añade, debe ser solo en tests.

**Solución:** aislar la llamada bloqueante con `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` (`mode=offload`) o sustituir la dependencia por una reactiva (R2DBC, WebClient). `boundedElastic` está limitado a 10 × nº de cores hilos: es un pool de hilos clásico, con sus mismos límites.

**Verificar:** con `mode=offload` la respuesta indica `"thread":"...boundedElastic-1..."` y la latencia de `/financial-summary` vuelve a su valor normal con la misma carga.

---

## 3. Timeouts y cancelaciones

**Síntoma:** respuestas `504 DOWNSTREAM_TIMEOUT` a los ~2 s.

**Causa:** el core no respondió dentro de `banking.downstream.response-timeout` (2 s).

**Reproducir:**
```bash
curl -s -w "\n%{http_code} en %{time_total}s\n" localhost:8081/api/clients/C0504/financial-summary
curl -s -w "\n%{http_code} en %{time_total}s\n" localhost:8082/api/clients/C0504/financial-summary
# medido: 504 en 2.024s (WebFlux) y 504 en 2.033s (VT)
```
Tests automáticos: `downstreamTimeoutReturns504` y `slowResponseIsMappedToTimeout` (en ambas APIs).

**Logs esperados (mismo contrato, distinta excepción de origen):**
```text
WebFlux: -> DOWNSTREAM_TIMEOUT (operación=balances, causa=io.netty.handler.timeout.ReadTimeoutException: null)
VT:      -> DOWNSTREAM_TIMEOUT (operación=balances, causa=java.net.SocketTimeoutException: Read timed out)
```

**Los tres timeouts y lo que cubre cada uno** (los mismos valores en ambas APIs):

| Propiedad | Cubre | WebFlux | VT |
|---|---|---|---|
| `connect-timeout` (1 s) | handshake TCP | `ChannelOption.CONNECT_TIMEOUT_MILLIS` | `ConnectionConfig.connectTimeout` |
| `pending-acquire-timeout` (1 s) | esperar una conexión libre del pool | `pendingAcquireTimeout` | `RequestConfig.connectionRequestTimeout` |
| `response-timeout` (2 s) | esperar la respuesta | `HttpClient.responseTimeout` | `RequestConfig.responseTimeout` (+ socket timeout) |

Ojo: en el peor caso una llamada puede tardar `pending-acquire + connect + response` = 4 s. Si se necesita un límite total por petición, hay que añadirlo de forma explícita (`Mono.timeout(...)` en WebFlux; `Future.get(timeout)` o un deadline en VT).

**Cancelación (fail-fast):** si cliente o saldo fallan, la API responde sin esperar a las demás llamadas y las cancela:
- WebFlux: `Mono.zip` cancela las suscripciones pendientes y Reactor Netty cierra esas conexiones.
- VT: `FailFastTasks.close()` llama a `Future.cancel(true)`, que interrumpe el hilo virtual. Interrumpir un hilo virtual bloqueado en un socket cierra el socket.

Verificado con tests en ambas: `mandatoryFailureFailsFastAndCancelsPendingCalls` (la llamada pendiente recibe la cancelación o la interrupción) y `mandatoryFailureFailsFast` (502 en menos de 900 ms aunque la otra llamada tarde 1 s).

**Desconexión del cliente** (observado con trazas, ver `observability.md`): en WebFlux, cuando k6 cerró conexiones con peticiones en vuelo, el span del servidor terminó con `outcome=UNKNOWN` y **las tres llamadas al core se cortaron en el mismo instante** (1942,7 ms): la cancelación se propagó hasta el core. En la métrica aparece como `http_server_requests{status="UNKNOWN"}` (9 peticiones en esa corrida). En VT no se registró ninguna: Tomcat no interrumpe el hilo de la petición y las llamadas al core siguen hasta que terminan o vence su timeout (esto último es el comportamiento documentado de Tomcat; no se midió en el core). Bajo una tormenta de reintentos de clientes impacientes, el servicio MVC sigue gastando conexiones del pool en respuestas que nadie va a leer.

---

## 4. Saturación del pool de conexiones HTTP

**Síntoma:** respuestas `503 CONNECTION_POOL_EXHAUSTED` y una latencia que sube justo hasta `pending-acquire-timeout` (1 s) antes de fallar. Las métricas de conexiones pendientes son mayores que 0.

**Causa:** hay más llamadas simultáneas al core que conexiones en el pool. Cada petición usa 3 conexiones durante ~0,45 s de media (unos 1,35 s·conexión), así que con 500 conexiones el techo teórico es de **~370 req/s para los dos modelos**. Es un límite de recursos, no de hilos.

**Reproducir:**
```bash
./scripts/stop-all.sh
EXTRA_ARGS="--banking.downstream.max-connections=20" ./scripts/start-all.sh
k6 run -e BASE_URL=http://localhost:8081 -e LABEL=webflux -e SCENARIO=low -e DURATION=20s load-tests/financial-summary.js
k6 run -e BASE_URL=http://localhost:8082 -e LABEL=virtual-threads -e SCENARIO=low -e DURATION=20s load-tests/financial-summary.js
```
Tests automáticos: `exhaustedPoolIsMappedToPoolExhausted` (1 conexión, 2 llamadas simultáneas).

**Resultados medidos** (20 conexiones, 50 req/s durante 20 s; capacidad teórica de 20 / 1,35 ≈ 15 req/s):

| API | errores | 503 | p50 | p95 |
|---|---|---|---|---|
| webflux | 62,5 % | 626 / 1001 | 1014 ms | 1761 ms |
| virtual-threads | 66,7 % | 667 / 1000 | 1010 ms | 1745 ms |

Logs:
```text
WebFlux: -> CONNECTION_POOL_EXHAUSTED (causa=reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException:
         Pool#acquire(Duration) has been pending for more than the configured timeout of 1000ms)
VT:      -> CONNECTION_POOL_EXHAUSTED (causa=org.apache.hc.core5.http.ConnectionRequestTimeoutException:
         Timeout deadline: 1000 MILLISECONDS, actual: 1000 MILLISECONDS)
```

Los dos modelos chocan con el mismo límite y se comportan igual: el p50 ≈ 1 s es justo el `pending-acquire-timeout`. Durante este experimento apareció además `org.apache.hc.core5.util.DeadlineTimeoutException: Deadline: ..., -1 MILLISECONDS overdue`: es la misma espera del pool, que Apache lanza por otra ruta cuando el plazo ya venció al comprobarlo. Al principio se mapeaba por error a `DOWNSTREAM_UNAVAILABLE`; se corrigió y lo cubre `DownstreamErrorsTest`.

**Tomcat y los 503:** en el escenario `stress` se vio que Tomcat añade `Connection: close` a las respuestas 503 (comprobado con `curl -v`; las 502 y 504 mantienen la conexión). Netty no lo hace. Si el servicio rechaza mucho con 503, sus clientes tienen que reconectar en cada rechazo, lo que en una sola máquina Windows agotó los puertos efímeros de k6. Con un balanceador delante no es tan grave, pero conviene tenerlo en cuenta al diseñar los rechazos por sobrecarga.

**Diagnóstico:** `reactor_netty_connection_provider_pending_connections` y `httpcomponents_httpclient_pool_total_pending` mayores que 0, `active`/`leased` igual al máximo y, en el log, `PoolAcquireTimeoutException` (WebFlux) o `ConnectionRequestTimeoutException` (VT).

**Solución:** dimensionar el pool según la Ley de Little, **conexiones ≈ tasa × latencia del core × llamadas por petición**, sin pasarse de lo que acepta el core. Si el core no aguanta más, la solución no es ampliar el pool sino limitar la entrada: rate limiting, un bulkhead (semáforo) o devolver 429/503 rápido.

**Variante real encontrada al desarrollar el laboratorio, conexiones *stale* (solo en VT/Apache):** tras el test de 100 peticiones simultáneas, los siguientes tests de la API VT fallaban con:
```text
DOWNSTREAM_UNAVAILABLE (operación=clients, causa=java.net.SocketException: Se ha anulado una conexión establecida por el software en su equipo host.)
```
Causa: el `HttpServer` del JDK solo mantiene **200** conexiones keep-alive ociosas (`sun.net.httpserver.maxIdleConnections`) y cierra el resto. Reactor Netty recibe el evento de cierre y saca la conexión del pool al momento. Apache HttpClient, con I/O bloqueante, no se entera hasta que intenta reutilizarla. WebFlux no tuvo este fallo con el mismo servidor. En producción el mismo síntoma aparece cuando el *keep-alive timeout* del core o de un balanceador es **menor** que el `max-idle-time` del cliente. **Soluciones:** `max-idle-time` del cliente menor que el keep-alive del servidor, `ConnectionConfig.setValidateAfterInactivity(...)` en Apache, o reintentar solo los GET idempotentes ante `NoHttpResponseException`. En el laboratorio se subió el límite del servidor falso (`FakeCoreBanking`) para que el test mida lo que debe medir.

**Variante real encontrada en el primer benchmark, `BindException: Address already in use` (solo en WebFlux):** en el escenario `high` (300 req/s), WebFlux devolvió un **41,9 % de 503**, con un pool de 500 conexiones que en teoría aguanta ~370 req/s:
```text
-> DOWNSTREAM_UNAVAILABLE (operación=clients, causa=java.net.BindException: Address already in use: getsockopt)
```
- `Get-NetTCPConnection -State TimeWait -RemotePort 8090` mostraba **12 168 sockets en TIME_WAIT** hacia el core, con 16 384 puertos efímeros disponibles (`netsh int ipv4 show dynamicport tcp`). Las conexiones no se estaban reutilizando.
- Medición aislada con 500 peticiones: **VT 0 TIME_WAIT, WebFlux 545**, aproximadamente uno por cada llamada a movimientos.
- Causa: `bodyToFlux(CoreMovement.class).take(limit)`. `take` **cancela** el `Flux` al recibir el elemento N, antes de que termine el cuerpo, y Reactor Netty cierra una conexión cancelada (no puede devolver al pool una conexión con datos sin leer). A 300 req/s eso son unas 300 conexiones nuevas por segundo; con el TIME_WAIT de Windows (~2 min) se agotan los puertos.
- Solución: limitar sin cancelar (`index().filter(i -> i < limit)`, que lee el cuerpo completo) o pedir al core solo los N elementos. Después de la corrección, la misma medición dio 0 TIME_WAIT. El test `limitingMovementsDoesNotKillPooledConnections` cuenta las conexiones TCP distintas que ve el core falso: falla con `take(limit)` y pasa con la corrección.
- Lección general: en WebFlux, **cualquier cancelación** (`take`, `next`, `timeout`, `zip` que falla, cliente que se desconecta) cuesta una conexión del pool. Es el precio de una cancelación real, y bajo carga se nota. El síntoma aparece en el sistema operativo (puertos), no en la aplicación.

**Otra trampa relacionada: reintentos implícitos.** Apache HttpClient 5 reintenta por defecto los métodos idempotentes ante algunos `IOException` y **también las respuestas 503/429** (esperando hasta 1 s). Eso multiplica la carga sobre un core que ya está degradado y falsea las comparaciones. Este laboratorio usa `disableAutomaticRetries()` en VT y `disableRetry(true)` en Reactor Netty, y el test `serviceUnavailableIsNotRetried` lo comprueba.

---

## 5. El pool de base de datos como cuello de botella (conceptual)

El laboratorio no tiene base de datos, pero este es el error más habitual al migrar a Virtual Threads:

- Con 200 hilos de plataforma, Tomcat limitaba *de forma implícita* la concurrencia a 200. Con hilos virtuales ese límite desaparece y 5 000 peticiones simultáneas llegan a la capa de datos.
- HikariCP tiene 10 conexiones por defecto. Las 4 990 peticiones restantes esperan en `HikariPool.getConnection()` hasta `connectionTimeout` (30 s por defecto) y luego fallan con `SQLTransientConnectionException: Connection is not available, request timed out after 30000ms`.
- En WebFlux con R2DBC pasa exactamente lo mismo: el pool de `r2dbc-pool` tiene su propio máximo y su propia cola.

**Diagnóstico:** `hikaricp_connections_pending` > 0 y `hikaricp_connections_active` = máximo. En el thread dump JSON, miles de hilos virtuales aparcados en `HikariPool.getConnection`.

**Solución:** la misma que en el caso 4. Dimensionar con la Ley de Little, bajar `connectionTimeout` (fallar rápido es mejor que esperar 30 s), poner un **bulkhead** antes de la BD (un `Semaphore` con `tryAcquire(timeout)` es suficiente con hilos virtuales) y no ampliar el pool por encima de lo que aguanta la base de datos.

---

## 6. Uso excesivo de memoria o CPU

| Síntoma | Causa probable | Diagnóstico | Solución |
|---|---|---|---|
| El heap crece con la concurrencia en VT | Las pilas de los hilos virtuales viven en el heap; miles de hilos aparcados con pilas profundas ocupan memoria | `jcmd <pid> GC.heap_info`, JFR (asignación de memoria) y thread dump JSON (número de hilos virtuales) | Limitar la concurrencia de entrada; evitar `ThreadLocal` con objetos grandes (cada hilo virtual tiene su copia) |
| `DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144` | WebFlux acumula como mucho 256 KB en memoria al decodificar un cuerpo | Stack trace con `LimitedDataBufferList` | `spring.http.codecs.max-in-memory-size`, o `codecs(c -> c.defaultCodecs().maxInMemorySize(...))` en el builder, o hacer streaming con `bodyToFlux` |
| CPU alta sin carga útil | Logs DEBUG bajo carga, histogramas con muchas etiquetas, reintentos en bucle | JFR, `process_cpu_usage`, perfil de logs | Logs en INFO para medir (no usar el perfil `demo`); etiquetas de baja cardinalidad (`uri` como plantilla, nunca la URL concreta) |
| Scrape de Prometheus pesado | `percentiles-histogram` activado: cada timer publica unas 70 *buckets* | Tamaño de `/actuator/prometheus` | Activar los histogramas solo para los timers que interesan (como en este laboratorio) |

**Caso real del laboratorio:** el test `exposesHealthAndMetrics` de WebFlux falló al principio con `DataBufferLimitException`, porque con los histogramas activados `/actuator/prometheus` pesa más de 256 KB y `WebTestClient` aplica el mismo límite de buffer que `WebClient`.

---

## 7. Errores de compilación por incompatibilidad de versiones

| Mensaje | Causa | Solución |
|---|---|---|
| `Rule 0: RequireJavaVersion failed ... Este laboratorio requiere Java 25` | Maven se ejecuta con un JDK < 25 | `java -version` y `mvn -version` (¡pueden ser JDK distintos!). Ajustar `JAVA_HOME` |
| `release version 25 not supported` | `JAVA_HOME` apunta a un JDK antiguo aunque `java` en el PATH sea el 25 | Igual que el anterior |
| `RequireMavenVersion failed` | Maven < 3.9 | Actualizar Maven |
| `package org.springframework.boot.test.autoconfigure.web.servlet does not exist` | Código de Boot 3 con Boot 4: los módulos se separaron | Boot 4: `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, `org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient` |
| `Could not find artifact ...spring-boot-starter-web-test` | Starters renombrados en Boot 4 | `spring-boot-starter-webmvc`, `-webmvc-test`, `-webflux-test`, `-restclient`, `-webclient` |
| `HealthIndicator cannot be resolved` (`org.springframework.boot.actuate.health`) | Boot 4 movió health a su propio módulo | `org.springframework.boot.health.contributor.HealthIndicator` |
| `ObjectMapper` no encontrado o tipos `com.fasterxml.jackson.databind` | Boot 4 usa Jackson 3 (`tools.jackson.*`) | Usar `tools.jackson.databind`. Las anotaciones siguen en `com.fasterxml.jackson.annotation` |
| `Mockito is currently self-attaching to enable the inline-bytecode...` (warning) | Java 21+ avisa de los agentes que se cargan dinámicamente | `-XX:+EnableDynamicAgentLoading` en surefire (ya configurado en el POM padre) |
| IntelliJ: `java: error: invalid source release: 25` | El SDK o el *language level* del proyecto no están en 25 | *Project Structure → SDK 25, Language level 25*; *Settings → Build Tools → Maven → Runner → JRE 25* |
| `Failed to delete ...target\*.jar ... está siendo utilizado por otro proceso` en `mvn clean` | Windows bloquea el jar de una aplicación en ejecución | `./scripts/stop-all.sh` (o `.\scripts\stop-all.ps1`) antes de `mvn clean` |
| Logs con `operaci�n` en la consola de Windows | La consola no usa UTF-8 | `chcp 65001` o la terminal de IntelliJ |

---

## 8. Concurrencia frente a paralelismo

- **Concurrencia**: gestionar muchas tareas *en curso* a la vez, que pueden estar esperando. Este laboratorio es casi solo concurrencia: cada petición pasa ~99 % del tiempo esperando al core.
- **Paralelismo**: ejecutar cálculo *simultáneo* en varios cores. Aquí solo existe en lo poco que se calcula (parsear JSON y ensamblar la respuesta).

Ni WebFlux ni Virtual Threads añaden cores. Lo que hacen es que esperar no cueste un hilo de plataforma. Por eso ninguno de los dos acelera un cálculo intensivo de CPU (calcular intereses de un millón de cuentas, por ejemplo). Para eso se usan pools de hilos de plataforma de tamaño ≈ nº de cores, `parallelStream` o `ForkJoinPool`.

En este laboratorio sí hay concurrencia *dentro* de cada petición (las 3 llamadas al core en paralelo), y por eso la latencia es `max(t1, t2, t3)` ≈ 625 ms de media y no `t1 + t2 + t3` ≈ 1350 ms. Lo verifican los tests `independentCallsAreConcurrent` / `independentCallsRunConcurrently` en ambas APIs.

---

## 9. Por qué los Virtual Threads no eliminan los límites de conexiones ni aceleran el servicio externo

- Un hilo virtual que espera una conexión del pool **sigue esperando**. Que esperar sea barato no hace que el pool sea más grande.
- El core tarda lo que tarda. Con un core de 600 ms, la latencia mínima de la API es de 600 ms en cualquier modelo.
- Lo que sí cambia: con hilos de plataforma el **primer** límite que se alcanzaba era el pool de hilos de Tomcat (200). Con hilos virtuales ese límite desaparece y el siguiente aparece en el pool HTTP, el de la BD, el *rate limit* del core o la memoria. **Es mejor que el límite esté donde uno lo pone de forma explícita** (pool dimensionado, bulkhead, rate limiter) que donde aparezca por accidente.
- Hay que vigilar también el *pinning*: hasta Java 23 un hilo virtual que bloqueaba dentro de `synchronized` se quedaba anclado a su *carrier*. Desde Java 24 (JEP 491) ya no ocurre con `synchronized`. Aún puede pasar con frames nativos (JNI). Se diagnostica con `jcmd <pid> JFR.start` y buscando eventos `jdk.VirtualThreadPinned`. El endpoint `?mode=synchronized` de la API VT sirve para comprobarlo.

Cómo se comporta el modelo VT con muchas solicitudes concurrentes y un core saturado: cada petición entrante crea un hilo virtual (Tomcat acepta hasta `server.tomcat.max-connections`, 8192 por defecto) y lanza 3 hilos virtuales más para las llamadas. Si el core se ralentiza, los hilos se acumulan aparcados: el uso de CPU no sube, pero sí el heap (pilas y estado) y la cola del pool HTTP. Cuando una petición pasa más de `pending-acquire-timeout` en esa cola, falla con `503 CONNECTION_POOL_EXHAUSTED`, así que el sistema se degrada rechazando peticiones en lugar de quedarse colgado. Las medidas del escenario `stress` están en `benchmark-guide.md`.

---

## 10. Por qué una implementación reactiva puede perder sus ventajas con dependencias bloqueantes

WebFlux rinde porque **ningún** hilo espera. Basta con una llamada bloqueante en el camino para que pase esto:

1. Un hilo del event loop se queda parado los N ms que dura la llamada.
2. Todas las conexiones asignadas a ese hilo (cientos) dejan de avanzar: es un bloqueo *head-of-line*.
3. Con unos 20 hilos de event loop (en esta máquina, uno por core lógico) y llamadas de 300 ms, el techo es de unas 66 llamadas bloqueantes por segundo para **toda** la API. Por encima de eso todo se encola.

Peor aún: **no hay error**. `block()` lanza `IllegalStateException`, pero `Thread.sleep`, JDBC, `RestTemplate`, un SDK de AWS v1 o `Files.readAllBytes` no avisan. El síntoma es solo latencia, y se confunde fácilmente con "el core está lento".

El caso 2 muestra la medición y la solución. Si la mayoría de las dependencias son bloqueantes (JDBC/JPA, SDKs corporativos), WebFlux obliga a envolver cada una en `boundedElastic`. En ese punto se está pagando la complejidad reactiva para acabar en un pool de hilos clásico, y MVC + Virtual Threads suele ser la opción más honesta.

---

## 11. Trazas rotas: cada llamada al core aparece como una traza separada (Virtual Threads)

**Síntoma:** con tracing activo, en Tempo aparece la traza de la petición (`GET /api/clients/...`) sin hijos, y las llamadas al core salen como trazas raíz separadas. En los logs, el `traceId` de la petición no coincide con el de las llamadas al core.

**Causa:** la observación actual vive en un `ThreadLocal` del hilo de la petición. `FailFastTasks` lanza cada llamada en **otro** hilo virtual, y los ThreadLocal no viajan solos. Pasa con cualquier executor propio, no solo con hilos virtuales. En WebFlux no ocurre porque la traza viaja en el Reactor Context.

**Reproducir y diagnosticar:** `TracePropagationApiTest` envía una petición con una cabecera `traceparent` conocida y comprueba que las tres llamadas al core la reciben. Sin la corrección, falla así:
```text
Expecting all elements of:
  ["00-a04b7f17580aea6f424aca40bc93ba7c-...", "00-269aafb55be156a9348dce20e3a3c7ff-...", "00-04b321731a9560f398ed1a04556a3db2-..."]
to start with "00-4bf92f3577b34da6a3ce929d0e0e4736-"
```

**Solución:** capturar el contexto al lanzar la tarea y restaurarlo en el hilo destino (`FailFastTasks.fork()`):
```java
Callable<Object> withContext = CONTEXT.captureAll().wrap(task::call);   // ContextSnapshotFactory (context-propagation)
```
Con Spring, la alternativa es un `TaskExecutor` con `ContextPropagatingTaskDecorator`.

**Verificar:** el test pasa y en Tempo cada petición es una sola traza con 3 hijos.
