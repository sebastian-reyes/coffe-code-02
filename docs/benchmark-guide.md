# Guía de benchmark

## Qué se mide

`GET /api/clients/{clientId}/financial-summary`, con `clientId` aleatorio entre `C1000` y `C8999`. Cada petición hace 3 llamadas concurrentes al mock.

| Métrica | Fuente |
|---|---|
| Requests por segundo | `summary_requests` (k6, solo la petición de negocio) |
| Latencia media, p50, p95, p99 y máxima | `summary_duration` (k6, tiempo de respuesta visto por el cliente) |
| Tasa de errores | `summary_errors` (status ≠ 200) |
| Timeouts | `summary_timeouts` (504 de la API + status 0 del propio k6) |
| Desglose 200/502/503/504 y respuestas degradadas | contadores `status_*`, `summary_degraded` |
| `dropped_iterations` | iteraciones que k6 no pudo lanzar por falta de VUs: si es > 0, la tasa objetivo no se alcanzó |
| CPU, heap e hilos de plataforma de la API | muestreo cada 1 s de `/actuator/metrics/process.cpu.usage`, `jvm.memory.used{area=heap}` y `jvm.threads.live` |

Sobre CPU y memoria: `process.cpu.usage` es la fracción de **toda la máquina** (20 hilos lógicos) que usa la JVM, así que un 5 % equivale aproximadamente a un core. `jvm.threads.live` solo cuenta **hilos de plataforma**: los hilos virtuales no aparecen ahí (para verlos: `jcmd <pid> Thread.dump_to_file -format=json`). El heap usado incluye basura que todavía no se ha recolectado; es un indicador, no la memoria "necesaria".

## Escenarios (`-e SCENARIO=...`)

Todos usan **tasa de llegada constante** (modelo abierto): k6 lanza N peticiones por segundo aunque las anteriores no hayan terminado. Así la latencia medida no se "auto-regula" cuando el servicio se degrada (evita la *coordinated omission*).

| Escenario | Tasa | Duración | Mock |
|---|---|---|---|
| `warmup` | 50 req/s | 30 s | 100–800 ms |
| `low` | 50 req/s | 60 s | 100–800 ms |
| `medium` | 150 req/s | 60 s | 100–800 ms |
| `high` | 300 req/s | 60 s | 100–800 ms |
| `stress` | rampa 10 → 100 → 800 req/s | 140 s | 100–800 ms |
| `slow-downstream` | 50 req/s | 60 s | **1000–2500 ms** (parte supera el timeout de 2 s) |
| `failing-downstream` | 100 req/s | 60 s | 100–800 ms, **10 % de 503 y 2 % colgadas** por endpoint |

`RATE` y `DURATION` sobrescriben los valores del escenario. El script configura el mock en `setup()` (`PUT /mock/admin/scenario`) y lo restaura en `teardown()`.

**Expectativas teóricas** (sirven para validar que el banco de pruebas mide lo que debe):
- Latencia media ≈ E[max(U₁, U₂, U₃)] con U ~ Uniforme(100, 800) = 100 + 700·¾ = **625 ms**; p99 ≈ 100 + 700·0,99^(1/3) ≈ **798 ms**.
- Techo del pool: cada petición ocupa 3 conexiones durante 450 ms de media = 1,35 s·conexión → 500 conexiones / 1,35 ≈ **370 req/s**, *para ambos modelos*. En `stress`, a partir de esa tasa deberían aparecer `503 CONNECTION_POOL_EXHAUSTED`.
- `failing-downstream`: el mock evalúa primero el 2 % de cuelgues y después el 10 % de errores sobre el resto, así que P(fallo por llamada) = 0,02 + 0,98·0,10 = 11,8 %. P(fallan cliente o saldo) = 1 − 0,882² ≈ **22,2 %** de errores (≈ 17,7 % de 502 + ≈ 4,0 % de 504). Un ~11,8 % de las respuestas 200 deberían venir degradadas (fallo en movimientos).
- `slow-downstream`: P(una llamada > 2 s) = 0,5/1,5 = 1/3, así que P(cliente o saldo superan 2 s) = 1 − (2/3)² ≈ **55,6 %** de 504.

## Cómo ejecutar

```bash
# 1. Levantar SIN perfil demo (logs INFO) y con los mismos flags de JVM
./scripts/start-all.sh                    # PowerShell: .\scripts\start-all.ps1

# 2a. Una corrida suelta
k6 run -e BASE_URL=http://localhost:8081 -e LABEL=webflux -e SCENARIO=medium load-tests/financial-summary.js
k6 run -e BASE_URL=http://localhost:8082 -e LABEL=virtual-threads -e SCENARIO=medium load-tests/financial-summary.js

# 2b. Comparación completa: calentamiento + N repeticiones con orden alterno (A,B / B,A / ...)
./load-tests/run-comparison.sh medium 3
./load-tests/run-comparison.sh all 2      # todos los escenarios (~45 min)
# PowerShell:
.\load-tests\run-comparison.ps1 -Scenario medium -Repetitions 3 -K6 k6

# Opcional: ver la corrida en Grafana en tiempo real (docker compose up -d; ver docs/observability.md)
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg"   k6 run -o experimental-prometheus-rw -e BASE_URL=http://localhost:8081 -e LABEL=webflux -e SCENARIO=medium load-tests/financial-summary.js

# Sin k6 instalado, con Docker:
docker compose run --rm k6 run -e BASE_URL=http://host.docker.internal:8081 -e MOCK_URL=http://host.docker.internal:8090 \
  -e LABEL=webflux -e SCENARIO=medium -e RESULTS_DIR=/scripts/results /scripts/financial-summary.js
```

Cada corrida escribe en `load-tests/results/<sesión>/`:
- `<label>-<escenario>-<run>.json`: el resumen completo de k6.
- `<label>-<escenario>-<run>.csv`: una fila con las métricas clave.
- `summary.csv`: todas las corridas de la sesión, sin calentamientos.

### Reglas para que la comparación sea válida

1. **Calentar** cada JVM antes de medir (JIT, pools de conexiones, clases). El runner lo hace con 30 s a 50 req/s.
2. **Alternar el orden** (A→B, B→A): evita que una API se beneficie siempre del estado del mock o de la máquina. El runner lo hace solo.
3. **Repetir** al menos 3 veces y mirar la dispersión. Si la diferencia entre A y B es menor que la variación entre repeticiones de una misma API, no hay diferencia.
4. **Mismos flags de JVM** (`-Xms512m -Xmx512m -XX:+UseG1GC`), **sin perfil demo** y con la máquina en reposo (sin compilaciones, IDE indexando ni navegador reproduciendo vídeo).
5. Comprobar `dropped_iterations = 0`. Si es mayor, falta capacidad en el *generador* y el resultado no es válido.
6. **No comparar** resultados de máquinas, sistemas operativos o versiones distintas.

## Resultados medidos

> **Nota:** estas cifras se midieron **antes** de añadir la observabilidad con trazas (`docs/observability.md`). Con el perfil por defecto el tracing sigue desactivado, y el código nuevo en el camino de cada petición (captura de contexto en `FailFastTasks` y un `ObservationPredicate`) es mínimo, pero **no se ha vuelto a medir**. Para comparar con tracing activo, hay que activarlo en las dos APIs (`PROFILE=observability`).

Medidos el **2026-10-26** con la configuración por defecto del repositorio y `./load-tests/run-comparison.sh all 2` (calentamiento de 30 s, 2 repeticiones, orden alterno). Datos completos en `load-tests/results/reference/summary-20261026.csv`.

**Entorno:** Intel Core i7-12700KF (20 hilos lógicos), 32 GB RAM, Windows 11 Pro 26200, Temurin 25.0.4, Spring Boot 4.1.1, k6 v2.3.0, con todo en la misma máquina. JVM: `-Xms512m -Xmx512m -XX:+UseG1GC`.

Valores = media de las 2 repeticiones; entre paréntesis, (mín–máx) entre repeticiones.

| Escenario | API | req/s | media ms | p50 | p95 | p99 | errores | 502 / 503 / 504 / status 0 de k6 | degradadas | CPU media / máx % | heap máx MB | hilos plataforma máx |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| low (50/s) | webflux | 49.4 | 635 (633–638) | 668 | 797 (797–797) | 809 | 0.0 % | 0 / 0 / 0 / 0 | 0 | 1.0 / 2.1 | 305 | 41 |
| low (50/s) | virtual-threads | 49.3 | 635 (634–637) | 666 | 798 (797–799) | 809 | 0.0 % | 0 / 0 / 0 / 0 | 0 | 1.1 / 3.0 | 311 | 38 |
| medium (150/s) | webflux | 148.0 | 634 (634–635) | 665 | 798 (798–798) | 809 | 0.0 % | 0 / 0 / 0 / 0 | 0 | 1.5 / 3.8 | 305 | 45 |
| medium (150/s) | virtual-threads | 148.0 | 635 (635–635) | 665 | 798 (798–798) | 809 | 0.0 % | 0 / 0 / 0 / 0 | 0 | 1.7 / 3.8 | 322 | 39 |
| high (300/s) | webflux | 295.8 | 634 (633–636) | 663 | 798 (798–798) | 809 | 0.0 % | 0 / 0 / 0 / 0 | 0 | 2.7 / 4.7 | 319 | 49 |
| high (300/s) | virtual-threads | 295.9 | 635 (635–635) | 665 | 798 (797–798) | 808 | 0.0 % | 0 / 0 / 0 / 0 | 0 | 2.7 / 5.3 | 365 | 39 |
| slow-downstream | webflux | 47.9 | 1938 (1935–1941) | 2005 | 2016 (2016–2017) | 2018 | 57.0 % | 0 / 0 / 1694 / 0 | 416 | 1.1 / 3.1 | 322 | 53 |
| slow-downstream | virtual-threads | 48.0 | 1932 (1929–1936) | 2002 | 2013 (2013–2014) | 2016 | 56.5 % | 0 / 0 / 1684 / 0 | 446 | 1.1 / 2.3 | 377 | 32 |
| failing-downstream | webflux | 97.3 | 668 (666–671) | 649 | 1438 (875–2001) | 2012 | 22.5 % | 1135 / 0 / 206 / 0 | 554 | 1.1 / 2.5 | 321 | 56 |
| failing-downstream | virtual-threads | 97.3 | 669 (665–672) | 649 | 2001 (2001–2001) | 2010 | 22.5 % | 1126 / 0 / 218 / 0 | 536 | 1.2 / 3.1 | 378 | 39 |
| stress (→800/s) | webflux | 437.8 | 1215 (1213–1217) | 1009 | 1776 (1776–1777) | 1801 | 34.0 % | 0 / 21184 / 0 / 0 | 2440 | 3.4 / 12.3 | 409 | 56 |
| stress (→800/s) | virtual-threads | 440.9 | 1012 (1008–1016) | 1001 | 1769 (1769–1770) | 1800 | 38.0 % | 0 / 15624 / 0 / 7909 | 1486 | 3.4 / 15.3 | 454 | 39 |

### Lectura de los resultados

1. **Hasta 300 req/s (low, medium, high) no hay diferencia medible.** La latencia es la que fija el core: una media de 634–635 ms frente a los 625 teóricos (los ~10 ms restantes son el overhead de la API, el mock y k6), un p99 de 808–809 ms frente a 798 teóricos, cero errores y `dropped_iterations = 0`. La diferencia entre las APIs es menor que la variación entre repeticiones de una misma API.
2. **Los escenarios de fallo se comportan igual en ambos y coinciden con la teoría:** `slow-downstream` da un 56,5–57,0 % de 504 (teoría 55,6 %) y `failing-downstream` un 22,5 % de errores (teoría 22,2 %), con ~11,7 % de respuestas degradadas en las dos. El p95 de `failing-downstream` es **inestable** (875 o 2001 ms) porque el percentil 95 cae justo en la frontera entre las respuestas normales (< 800 ms) y los timeouts (2 s): no es una diferencia entre modelos. En `slow-downstream` hubo 26 `dropped_iterations` (< 1 %) en las dos APIs: a ~2 s por petición, k6 se quedó justo de VUs; afecta a ambas por igual.
3. **CPU:** baja y equivalente en todos los escenarios (1–3,4 % de la máquina en media, ≈ 0,2–0,7 cores).
4. **Hilos de plataforma:** de 32 a 56 en las dos APIs, aunque en `high` hay ~190 peticiones (≈ 570 llamadas al core) en vuelo. VT usa algo menos (Tomcat sin pool de hilos de plataforma, solo carriers); WebFlux, algo más (event loops del servidor y del cliente, `parallel`, `boundedElastic`).
5. **Heap:** VT muestra entre 10 y 60 MB más de heap máximo en los escenarios con más concurrencia (high, slow, failing y stress). Encaja con que las pilas de los hilos virtuales aparcados viven en el heap, pero la medición incluye basura no recolectada y con `-Xmx512m` no se acercó al límite. Es una tendencia, no una conclusión.
6. **`stress`: ambos chocan con el mismo techo**, el pool de 500 conexiones (~370 req/s en teoría). El 503 dominante es `CONNECTION_POOL_EXHAUSTED` en los dos (`PoolAcquireTimeoutException` / `ConnectionRequestTimeoutException`). Las diferencias en este escenario **no son del modelo de concurrencia**:
   - Los ~7 900 "status 0" de VT son fallos de k6 al **conectar** con Tomcat (`connectex: Only one usage of each socket address`): se agotaron los puertos efímeros de Windows. La causa es que **Tomcat cierra la conexión keep-alive al responder 503** (añade `Connection: close`; comprobado con `curl -v`), así que cada rechazo obligaba a k6 a abrir una conexión nueva. Netty mantiene la conexión con los 503. Se trata de Tomcat sobre Windows con todo en la misma máquina, no de los hilos virtuales.
   - La media más baja de VT (1012 frente a 1215 ms) es un artefacto de esos fallos instantáneos (≈ 0 ms), que tiran de la media hacia abajo. El p50, el p95 y el p99 son prácticamente iguales.
   - El caudal útil (respuestas 200) fue de ~289 req/s en WebFlux y ~273 req/s en VT. Ese 6 % de diferencia está dominado por el artefacto anterior.

**Conclusión del benchmark:** con el mismo pool, los mismos timeouts y un core lento, **los dos modelos rinden igual** en este laboratorio. Los límites que se alcanzaron (pool de conexiones, puertos efímeros, comportamiento de Tomcat con los 503) son de recursos y bibliotecas, no de WebFlux frente a Virtual Threads. Para separar mejor los efectos haría falta repetir las pruebas en Linux, con k6 en otra máquina y con más repeticiones.

### Primer intento descartado (y por qué es interesante)

La primera ejecución de la suite se descartó: en `high`, WebFlux dio un **41,9 % de 503** por `BindException`, con 12 168 sockets en TIME_WAIT. La causa era un bug del laboratorio, `take(limit)` en `getMovements`, que cancelaba cada respuesta y forzaba a cerrar la conexión. Después de corregirlo (y añadir un test de regresión), todas las cifras de arriba se volvieron a medir desde cero. Detalle completo en `troubleshooting.md`, caso 4.

### Experimentos complementarios (medidos el mismo día)

**Pool reducido a 20 conexiones** (`EXTRA_ARGS="--banking.downstream.max-connections=20"`, escenario `low` 20 s): capacidad teórica de 20 / 1,35 ≈ 15 req/s frente a 50 req/s de carga.

| API | errores | 503 `CONNECTION_POOL_EXHAUSTED` | p50 | p95 |
|---|---|---|---|---|
| webflux | 62,5 % | 626 de 1001 | 1014 ms | 1761 ms |
| virtual-threads | 66,7 % | 667 de 1000 | 1010 ms | 1745 ms |

Mismo comportamiento: las peticiones esperan `pending-acquire-timeout` (1 s) y fallan. Los hilos virtuales no evitan el límite.

**Event loop bloqueado** (`load-tests/blocking-demo.js`: 100 req/s a un endpoint que bloquea 300 ms, más 20 req/s "víctima" a `/financial-summary`; resultados en `troubleshooting.md`, caso 2): con `mode=event-loop` la víctima pasó de 637 ms a **6471 ms de media y 25,7 s de p95, con un 45 % de errores**. Con `mode=offload` (WebFlux) o en VT (`mode=direct`), la víctima quedó en 637–641 ms y 0 % de errores.

## Limitaciones y factores de confusión

- **Todo en una máquina.** k6, el mock y las dos APIs compiten por CPU, memoria y la pila TCP de loopback. k6 en particular consume bastante CPU a tasas altas. La latencia de red real (1–5 ms entre centros de datos, o más) no existe aquí.
- **Las dos APIs están arrancadas a la vez** durante todas las corridas. La que no recibe carga está ociosa, pero ocupa memoria y hace sus *housekeeping*.
- **El mock es compartido** y reactivo. Se diseñó para no ser el cuello de botella (latencia con timers, sin hilos bloqueados), pero a tasas muy altas también consume CPU de la misma máquina.
- **Servidor y cliente HTTP distintos** (Netty/Reactor Netty frente a Tomcat/Apache HttpClient 5). Las diferencias medidas mezclan el modelo de concurrencia con la eficiencia de esas bibliotecas. Ver "Diferencias que no son del modelo" en `architecture.md`.
- **Windows.** El *scheduler*, la pila TCP (puertos efímeros, `TIME_WAIT`) y el antivirus en tiempo real difieren de un servidor Linux de producción.
- **Carga dominada por I/O.** El endpoint casi no tiene CPU de negocio. Con lógica pesada (cifrado, reglas, serialización grande) los resultados cambiarían.
- **Duración corta** (60 s). No captura fugas de memoria, GC de la generación vieja a largo plazo ni la expiración de conexiones ociosas.
- **Distribución sintética** (uniforme). Las latencias de un core real tienen colas largas (log-normal, picos), que castigan más los p99.
- **Muestreo de CPU y heap cada 1 s** desde el mismo k6. Es una aproximación: para un análisis serio, usar JFR o Prometheus.
- **Dos repeticiones** por escenario en la ejecución de referencia, por tiempo. Para sacar conclusiones finas, usar 3–5.
