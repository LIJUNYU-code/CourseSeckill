package com.example.seckill.common;

/**
 * 全局错误码字典。
 *
 * <p>设计原则：<b>响应体里的 code 只有一个来源，就是这个类。</b>
 * Controller / Service 一律引用这里的常量，绝不写字面量 {@code 409}。
 * 原因很实际：字面量每次都能编译通过，改错值也不会报错，
 * 只有等到前端或压测脚本按错误的码做判断时才会暴露。
 *
 * <p>为什么用 int 常量而不是 enum：
 * <ol>
 *   <li>{@link com.example.seckill.exception.BusinessException} 就是拿 int 构造的，
 *       enum 每次都要 .getCode() 转换一层，白白多绕一道；</li>
 *   <li>JSON 序列化后就是数字，enum 还要额外配置序列化器；</li>
 *   <li>分段留白（4xx 是 HTTP 语义、1xxx 是业务语义）在 int 上很自然。</li>
 * </ol>
 */
public final class ResultCode {

    /** 工具类禁止实例化：私有构造器 + final 类，编译期就挡住 new ErrorCode() */
    private ResultCode() {
        throw new AssertionError("常量类不允许实例化");
    }

    // ==================== 通用（与 HTTP 状态码语义一致）====================

    /** 成功 */
    public static final int SUCCESS = 200;
    /** 参数错误：字段校验失败、JSON 格式错误、类型不匹配 */
    public static final int BAD_REQUEST = 400;
    /** 未登录 / token 无效或过期 */
    public static final int UNAUTHORIZED = 401;
    /** 已登录但无权限（角色不够） */
    public static final int FORBIDDEN = 403;
    /** 资源不存在 */
    public static final int NOT_FOUND = 404;
    /** 冲突：重复提交、状态不允许、库存不足 */
    public static final int CONFLICT = 409;
    /** 请求过于频繁：限流、排队超时 */
    public static final int TOO_MANY_REQUESTS = 429;
    /** 服务器内部错误（兜底，永远不该主动抛） */
    public static final int INTERNAL_ERROR = 500;

    // ==================== 业务码（1xxx 段，与 HTTP 解耦）====================
    // 什么时候该用业务码而不是 409：
    //   当"客户端需要按码做不同处理"时才需要。比如前端要把"库存不足"显示成
    //   红色提示、"重复选课"显示成灰色"你已选过"，两者共用一个 409 就区分不了。
    //   但现阶段不要提前造 —— 先只用上面 8 个，等真的需要区分前再加。

    /** 库存不足（v2 起会用到） */
    public static final int STOCK_EMPTY = 1001;
    /** 重复下单 / 重复选课 */
    public static final int DUPLICATE_ORDER = 1002;
    /** 课程不在选课时间内 */
    public static final int NOT_IN_TIME_RANGE = 1003;
    /** 已有成绩，不允许退课（对应项目一的真实规则） */
    public static final int SCORE_EXISTS = 1004;
}