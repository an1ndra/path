// k6 load test for Path.
//
//   k6 run -e BASE=http://127.0.0.1:8080 -e KEY=sk-gw-... tools/load-test.js
//
// Two scenarios: steady non-streaming traffic and a streaming burst. Thresholds fail
// the run when latency or error rate regress.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:8080';
const KEY = __ENV.KEY || '';
const MODEL = __ENV.MODEL || 'gpt-4o-mini';

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '30s',
      exec: 'chat',
    },
    streaming: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.STREAM_RATE || 5),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 10,
      exec: 'streamChat',
      startTime: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
  },
};

const headers = {
  'Content-Type': 'application/json',
  Authorization: `Bearer ${KEY}`,
};

export function chat() {
  const res = http.post(`${BASE}/v1/chat/completions`, JSON.stringify({
    model: MODEL,
    temperature: 0,
    messages: [{ role: 'user', content: 'Say hello in five words.' }],
  }), { headers });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'has choices': (r) => !!r.json('choices'),
  });
  sleep(0.5);
}

export function streamChat() {
  const res = http.post(`${BASE}/v1/chat/completions`, JSON.stringify({
    model: MODEL,
    stream: true,
    messages: [{ role: 'user', content: 'Count to five.' }],
  }), { headers });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'terminates with DONE': (r) => String(r.body).includes('[DONE]'),
  });
}
