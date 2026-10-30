// Demo de troubleshooting: ¿qué le pasa a /financial-summary cuando OTRO endpoint bloquea el event loop?
//
// Requiere la API arrancada con el perfil demo (banking.demo.blocking-endpoints-enabled=true).
//   k6 run -e BASE_URL=http://localhost:8081 -e MODE=event-loop load-tests/blocking-demo.js   # WebFlux, antipatrón
//   k6 run -e BASE_URL=http://localhost:8081 -e MODE=offload    load-tests/blocking-demo.js   # WebFlux, corregido
//   k6 run -e BASE_URL=http://localhost:8082 -e MODE=direct     load-tests/blocking-demo.js   # Virtual Threads
//
// Dos escenarios simultáneos:
//   blocking: BLOCKING_RATE req/s a /api/demo/legacy-score (cada una bloquea 300 ms)
//   probe:    20 req/s a /financial-summary -> su latencia es la "víctima" que medimos
import http from 'k6/http';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const MODE = __ENV.MODE || 'event-loop';
const BLOCKING_RATE = Number(__ENV.BLOCKING_RATE || 100);
const DURATION = __ENV.DURATION || '30s';

const probeDuration = new Trend('probe_summary_duration', true);
const probeErrors = new Rate('probe_errors');
const blockingDuration = new Trend('blocking_call_duration', true);

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    blocking: {
      executor: 'constant-arrival-rate', exec: 'blocking', rate: BLOCKING_RATE, timeUnit: '1s',
      duration: DURATION, preAllocatedVUs: BLOCKING_RATE, maxVUs: BLOCKING_RATE * 20,
    },
    probe: {
      executor: 'constant-arrival-rate', exec: 'probe', rate: 20, timeUnit: '1s',
      duration: DURATION, preAllocatedVUs: 50, maxVUs: 500,
    },
  },
};

export function blocking() {
  const res = http.get(`${BASE_URL}/api/demo/legacy-score/C1234?mode=${MODE}`, { timeout: '30s' });
  blockingDuration.add(res.timings.duration);
}

export function probe() {
  const id = `C${1000 + Math.floor(Math.random() * 8000)}`;
  const res = http.get(`${BASE_URL}/api/clients/${id}/financial-summary`, { timeout: '30s' });
  probeDuration.add(res.timings.duration);
  probeErrors.add(res.status !== 200);
}

export function handleSummary(data) {
  const v = (m, s) => (data.metrics[m] && data.metrics[m].values[s]) || 0;
  const f = (n) => n.toFixed(0);
  return {
    stdout: [
      '',
      `===== blocking-demo ${BASE_URL} mode=${MODE} blocking_rate=${BLOCKING_RATE}/s =====`,
      `legacy-score (300 ms bloqueante): avg=${f(v('blocking_call_duration', 'avg'))} ms p95=${f(v('blocking_call_duration', 'p(95)'))} ms`,
      `financial-summary (víctima):      avg=${f(v('probe_summary_duration', 'avg'))} ms p95=${f(v('probe_summary_duration', 'p(95)'))} ms ` +
        `p99=${f(v('probe_summary_duration', 'p(99)'))} ms errores=${(v('probe_errors', 'rate') * 100).toFixed(1)}%`,
      `dropped_iterations=${v('dropped_iterations', 'count')}`,
      '',
    ].join('\n'),
  };
}
