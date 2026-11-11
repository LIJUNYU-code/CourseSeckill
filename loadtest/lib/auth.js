// 登录与鉴权工具。
//
// 为什么要把登录拆到 lib 里：k6 的 setup() 只在压测开始前跑一次，
// 我们在那里批量登录、把 token 塞进 data 传给每个 VU。
// 这样测出来的 P95 里不包含 BCrypt 的 ~60ms 开销 —— 那是登录接口的成本，
// 不是抢课接口的成本，混在一起会让数据失去意义。
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, PASSWORD, studentNo } from './config.js';

/**
 * 登录单个学生，返回 token。失败返回 null。
 */
export function loginStudent(account) {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ account: account, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } }
  );

  const ok = check(res, {
    'login status 200': (r) => r.status === 200,
  });
  if (!ok) {
    return null;
  }

  try {
    return res.json('data.token');
  } catch (e) {
    return null;
  }
}

/**
 * 批量登录 count 个学生，返回 token 数组。
 * 在 setup() 里调用。
 */
export function loginMany(count) {
  const tokens = [];
  for (let i = 0; i < count; i++) {
    const token = loginStudent(studentNo(i));
    if (token) {
      tokens.push(token);
    }
  }
  return tokens;
}

/**
 * 抢课请求。响应体解析成对象返回。
 */
export function seckill(courseId, token) {
  const res = http.post(`${BASE_URL}/api/seckill/${courseId}`, null, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'seckill' },
  });

  let body = null;
  try {
    body = res.json();
  } catch (e) {
    body = null;
  }
  return { status: res.status, body: body };
}
