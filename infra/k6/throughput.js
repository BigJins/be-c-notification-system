import http from 'k6/http';
import { check } from 'k6';

export const options = {
    scenarios: {
        sustained: {
            executor: 'constant-arrival-rate',
            rate: 100,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 50,
            maxVUs: 200,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],   // <1% failure
        http_req_duration: ['p(95)<500'], // P95 < 500ms
    },
};

export default function () {
    const uniqueId = `throughput-${__VU}-${__ITER}-${Date.now()}`;
    const payload = JSON.stringify({
        eventId: uniqueId,
        recipientId: `u-${__VU}`,
        type: 'PAYMENT_CONFIRMED',
        channels: ['EMAIL', 'IN_APP'],
        payload: { subject: `throughput ${uniqueId}`, body: 'load test' },
    });
    const res = http.post('http://localhost:8080/v1/notifications', payload, {
        headers: { 'Content-Type': 'application/json' },
    });
    check(res, {
        'status is 202 (or 200 in rare race)': r => r.status === 200 || r.status === 202,
    });
}
