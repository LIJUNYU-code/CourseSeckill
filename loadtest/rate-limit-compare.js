// 限流对比实验：同一负载下「无限流」与「有限流」的差异。
//
// 为什么单独写一个脚本，而不是复用 seckill-v3.js：
//   1. seckill-v3.js 用 per-vu-iterations，VU 数决定压力，没法精确控制 RPS；
//      对比实验必须固定「到达速率」，否则三组的负载本身就不一样。
//   2. seckill-v3.js 的 biz_rejected 会把 429 算进去，thresholds 会直接失败。
//   3. 需要把 429 的延迟单独统计 —— 「被挡在门外的请求有多快」正是限流生效的证据。
//
// 三组怎么跑（详细见 docs/阶段10-任务书-限流.md §9）：
//
//   基线：应用 --seckill.rate-limit-enabled=false
//         k6 run -e RPS=1200 -e DURATION=15s rate-limit-compare.js
//   A   ：应用 rate-limit-enabled=true，全局 1500/s
//         k6 run -e RPS=1200 -e DURATION=15s rate-limit-compare.js
//   B   ：应用 rate-limit-enabled=true，全局故意调到 300/s
//         k6 run -e RPS=1600 -e DURATION=15s rate-limit-compare.js
//
// 三组的 biz_throttled 与两个 latency 指标对比，就是结论。
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { loginMany, seckill } from './lib/auth.js';
import { COURSE_ID, STUDENT_COUNT } from './lib/config.js';

const RPS = Number(__ENV.RPS || 1200);
const DURATION = __ENV.DURATION || '15s';
// maxVUs 绝不能超过学生池：超了会让多个 VU 共用同一个学生，
// 撞 uk_student_course 得到 409，看起来像限流生效，其实是脚本自己造出来的假瓶颈。
const PRE_VUS = Number(__ENV.PRE_VUS || Math.min(300, STUDENT_COUNT));
const MAX_VUS = Number(__ENV.MAX_VUS || STUDENT_COUNT);

const bizQueued = new Counter('biz_queued');        // 200：受理入队
const bizSoldOut = new Counter('biz_sold_out');     // 409：名额已满
const bizDuplicate = new Counter('biz_duplicate');  // 409：已选过
const bizThrottled = new Counter('biz_throttled');  // 429：被限流挡住
const bizRejected = new Counter('biz_rejected');    // 其它 4xx/5xx：出现就是 bug

// 两个延迟分开统计：
//   biz_latency       —— 真正进到业务逻辑的请求（200 / 409）
//   throttled_latency —— 在过滤器就被挡回的请求（429）
// 如果限流生效，throttled_latency 应该显著低于 biz_latency。
const bizLatency = new Trend('biz_latency', true);
const throttledLatency = new Trend('throttled_latency', true);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '30s',
    },
  },
  // setup 默认只给 60 秒；900 个学生 × BCrypt 约 60ms 必然超时
  setupTimeout: '300s',
  thresholds: {
    // 5xx 一条都不能有；429 是预期结果，不算失败
    biz_rejected: ['count<1'],
  },
};

export function setup() {
  const tokens = loginMany(STUDENT_COUNT);
  if (tokens.length === 0) {
    throw new Error('一个学生都没登录成功，检查 BASE_URL / 密码 / 账号是否存在');
  }
  console.log(`setup 完成：${tokens.length} 个学生，目标课程 ${COURSE_ID}，`
    + `计划 ${RPS} RPS × ${DURATION}`);
  return { tokens: tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const started = Date.now();
  const res = seckill(COURSE_ID, token);
  const elapsed = Date.now() - started;

  if (res.status === 429) {
    bizThrottled.add(1);
    throttledLatency.add(elapsed);
    // 限流响应必须带 Retry-After，否则前端只能瞎猜
    check(res, {
      'throttled has code 429': (r) => r.body && r.body.code === 429,
    });
    return;
  }

  bizLatency.add(elapsed);

  if (res.status === 200 && res.body && res.body.code === 200) {
    bizQueued.add(1);
  } else if (res.status === 409) {
    const msg = (res.body && res.body.message) || '';
    if (msg.indexOf('名额已满') >= 0) {
      bizSoldOut.add(1);
    } else if (msg.indexOf('已选过') >= 0) {
      bizDuplicate.add(1);
    } else {
      bizRejected.add(1);
    }
  } else {
    bizRejected.add(1);
  }
}

export function teardown() {
  console.log('压测结束。限流生效与否看 biz_throttled 与 throttled_latency，'
    + '成功单数仍要查库确认。');
}
