package com.example.unitrade.config;

import com.example.unitrade.entity.User;
import com.example.unitrade.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员权限拦截器 —— 修复"任何登录用户都能调用后台接口"的越权漏洞。
 *
 * <h2>漏洞是怎么来的</h2>
 * 原来的配置里，{@code /api/admin/**} 只挂在 {@link JwtInterceptor} 上，
 * 而 JwtInterceptor <b>只回答"你是谁"，不回答"你能不能"</b>。
 * 加上 8 个 {@code Admin*Controller} 内部也没有任何角色判断，
 * 结果是：任何注册用户登录后拿到 token，就能直接调用
 * {@code DELETE /api/admin/product/delete/{id}} 删掉别人的商品、
 * {@code GET /api/admin/user/list} 看到全部用户手机号。
 *
 * <p>这类问题在 OWASP 里排第一位（Broken Access Control），
 * 而且它的特点是：<b>功能测试全都会通过</b>——因为管理员确实能用，
 * 你只会发现"用起来没问题"，不会发现"普通人也能用"。
 * 所以它必须靠一条明确的安全断言来防，而不是靠"我记得加过校验"。
 *
 * <h2>为什么放在拦截器而不是每个 Controller 里</h2>
 * 8 个 Controller、几十个方法，逐个加注解的写法有两个问题：
 * <ol>
 *   <li><b>遗漏是不可见的</b>——新加一个后台接口时，忘了加注解没有任何提示；</li>
 *   <li>校验逻辑分散在几十处，改规则要改几十处。</li>
 * </ol>
 * 放在拦截器 + {@code /api/admin/**} 路径匹配上是<b>集中式</b>的：
 * 新接口只要落在该路径下就自动受保护，漏不掉。
 * 原则是：<b>安全规则应该默认生效（fail-safe），而不是默认放行（fail-open）。</b>
 *
 * <h2>为什么查库而不是用 token 里的角色</h2>
 * 把 role 放进 JWT 会带来一个隐患：<b>改了角色，旧 token 仍然带着旧角色有效</b>，
 * 撤销权限要等到 token 过期（本项目 7 天）。查库的代价是一次主键查询，
 * 换来的是"权限变更立即生效"。管理员接口调用频率极低，这点开销完全可以接受。
 */
@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);

    /** 管理员角色标识，与 t_user.role 的取值保持一致 */
    private static final String ROLE_ADMIN = "admin";

    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        Long userId = JwtInterceptor.getCurrentUserId();
        if (userId == null) {
            // 正常情况下 JwtInterceptor 已经拦住了未登录请求。
            // 这里兜底是为了"万一拦截器顺序被改动"时不至于静默放行 ——
            // 安全代码的默认分支必须是拒绝，不能是放行。
            deny(response, 401, "未登录，请先登录");
            return false;
        }

        User user = userMapper.selectById(userId);
        if (user == null || !ROLE_ADMIN.equals(user.getRole())) {
            // 越权尝试必须留痕：这是一条真实的攻击信号，不能只返回 403 就完事
            log.warn("越权访问被拦截：uri={} method={} userId={} role={}",
                    request.getRequestURI(), request.getMethod(), userId,
                    user == null ? "<用户不存在>" : user.getRole());
            deny(response, 403, "无权限：该接口仅管理员可访问");
            return false;
        }

        return true;
    }

    private void deny(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + message + "\"}");
    }
}
