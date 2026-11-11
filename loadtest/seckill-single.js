// 场景 A：单课极限 —— 所有人抢同一门课，看会不会超卖。
//
// 核心设计：
//   1. setup() 里一次性登录 N 个不同学生，把 token 传给各 VU。
//      这样压测耗时里不含 BCrypt（~60ms/次），测的纯是抢课接口。
//   2. 每个 VU 对应一个不同学生，各抢 1 次。
//      VU 数（= 学生数）必须远大于 capacity，否则即使丢了更新也压不出超卖：
//      100 个学生抢 100 个名额，最多也只会成功 100 单，看不出问题。
//   3. 用 shared-iterations 之外的方式：per-vu-iterations + iterations=1，
//      让所有请求在同一瞬间砸下来，最大化竞态窗口的重叠。
//
// 运行：
//   k6 run -e VUS=300 -e STUDENT_COUNT=300 seckill-single.js
//
// ⚠️ k6 把 HTTP >= 400 一律算进 http_req_failed。
//    这里的 409（已选过 / 名额满）是**预期**的拒绝，不是错误。
//    所以判断成败要看自定义的 biz_* 计数器，不看 http_req_failed。
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { loginMany, seckill } from './lib/auth.js';
import { COURSE_ID, STUDENT_COUNT } from './lib/config.js';

const VUS = Number(__ENV.VUS || STUDENT_COUNT);

// 四个分桶计数器：压测结束后直接读出「成功了多少单」
const bizSuccess = new Counter('biz_success'); // 抢到了
const bizSoldOut = new Counter('biz_sold_out'); // 名额已满
const bizDuplicate = new Counter('biz_duplicate'); // 同一学生重复抢
const bizOther = new Counter('biz_other'); // 其它（含 5xx，需要关注）

export const options = {
  scenarios: {
    seckill_burst: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  // 阈值故意只卡「致命错误」：5xx 出现就说明服务端崩了，数据不可信。
  // 超卖本身不是 k6 能判断的，要压测后查数据库。
  thresholds: {
    biz_other: ['count<1'], // 出现任何非预期响应就失败
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
    bizSuccess.add(1);
  } else if (res.status === 409) {
    const msg = (res.body && res.body.message) || '';
    if (msg.indexOf('名额已满') >= 0) {
      bizSoldOut.add(1);
    } else if (msg.indexOf('已选过') >= 0) {
      bizDuplicate.add(1);
    } else {
      bizOther.add(1);
    }
  } else {
    bizOther.add(1);
  }

  check(res, {
    'no 5xx': (r) => r.status < 500,
  });
}

export function teardown(data) {
  console.log('压测结束。请立刻执行判定 SQL（见 docs/压测报告.md）。');
}
