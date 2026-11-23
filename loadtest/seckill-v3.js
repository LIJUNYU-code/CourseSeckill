// 场景 A（v3 版）：单课极限 —— 所有人抢同一门课。
//
// 与 seckill-single.js 的唯一区别：**成功率的判定口径变了**。
//
// v0/v1/v2 是同步的，HTTP 200 就代表真抢到了，所以计数器叫 biz_success。
// v3 是异步的，HTTP 200 只代表「请求受理、消息已进队列」，
// 订单此时还是 status=0（待处理）。所以：
//
//   biz_queued   ← HTTP 200，已入队（≠ 已抢到）
//   biz_sold_out ← HTTP 409，Redis 门禁挡下（名额已满）
//   biz_duplicate← HTTP 409，该学生已有成功/待处理订单
//   biz_rejected ← HTTP 4xx 其它 / 5xx（出现就说明有问题）
//
// **真正的成功单数只能压测结束后查数据库**：
//   SELECT COUNT(*) FROM seckill_order WHERE course_id=? AND status=1;
// 这一点是 v3 压测和前面几版最大的不同，脚本里数不出来。
//
// 运行：
//   k6 run -e VUS=300 -e STUDENT_COUNT=300 seckill-v3.js
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { loginMany, seckill } from './lib/auth.js';
import { COURSE_ID, STUDENT_COUNT } from './lib/config.js';

const VUS = Number(__ENV.VUS || STUDENT_COUNT);

const bizQueued = new Counter('biz_queued');       // 200：已受理入队
const bizSoldOut = new Counter('biz_sold_out');    // 409：名额已满
const bizDuplicate = new Counter('biz_duplicate'); // 409：已选过
const bizRejected = new Counter('biz_rejected');   // 其它：需要关注

export const options = {
  scenarios: {
    seckill_burst: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  // 只卡「致命错误」：非 200/409 一律算异常。
  // 注意 v3 的 200 不代表抢到，超卖要看数据库。
  thresholds: {
    biz_rejected: ['count<1'],
  },
};

export function setup() {
  const tokens = loginMany(STUDENT_COUNT);
  if (tokens.length === 0) {
    throw new Error('一个学生都没登录成功，检查 BASE_URL / 密码 / 账号是否存在');
  }
  console.log(`setup 完成：成功登录 ${tokens.length} 个学生，准备抢课程 ${COURSE_ID}`);
  return { tokens: tokens };
}

export default function (data) {
  // __VU 从 1 开始，所以要减 1
  const token = data.tokens[(__VU - 1) % data.tokens.length];

  const res = seckill(COURSE_ID, token);

  if (res.status === 200 && res.body && res.body.code === 200) {
    // 关键：这里只代表入队，status 字段应为 0（待处理）
    bizQueued.add(1);
    check(res, {
      'queued returns status=0': (r) => r.body && r.body.data && r.body.data.status === 0,
      'queued has orderNo': (r) => r.body && r.body.data && !!r.body.data.orderNo,
    });
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

  check(res, {
    'no 5xx': (r) => r.status < 500,
  });
}

export function teardown(data) {
  console.log('压测结束。v3 的成功单数必须查库确认，见 loadtest/verify-v3.ps1');
}
