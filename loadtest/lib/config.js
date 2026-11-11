// 压测公共配置。所有值都可以用环境变量覆盖，例如：
//   k6 run -e VUS=200 -e COURSE_ID=77 seckill-single.js
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 被抢的课程 id。88 号课的 capacity = 100
export const COURSE_ID = Number(__ENV.COURSE_ID || 88);

// 种子数据里 1 万名学生的统一密码
export const PASSWORD = __ENV.PASSWORD || 'Abc12345';

// 本次压测用多少个不同学生（每个学生只能抢到 1 个名额，所以 VU 数不能超过它）
export const STUDENT_COUNT = Number(__ENV.STUDENT_COUNT || 100);

// 从第几个学生开始取。改成 1001 就是另一批人，避免和上一轮的脏数据撞车
export const STUDENT_OFFSET = Number(__ENV.STUDENT_OFFSET || 1);

// 学号格式：S + 9 位数字，例如 S000000001
export function studentNo(i) {
  return 'S' + String(STUDENT_OFFSET + i).padStart(9, '0');
}
