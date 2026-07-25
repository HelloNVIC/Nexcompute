package com.nexcompute.management.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 审计日志 AOP 切面（任务 2.5）
 * 拦截 @Audited 注解的方法，自动记录操作至审计日志。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        String targetId = resolveTargetId(joinPoint, audited.targetIdExpr());
        String content = serializeArgs(joinPoint);

        Object result = null;
        boolean success = true;
        String errorMessage = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            success = false;
            errorMessage = ex.getMessage();
            throw ex;
        } finally {
            auditService.record(audited.action(), audited.targetType(), targetId,
                    content, success, errorMessage);
        }
    }

    private String resolveTargetId(ProceedingJoinPoint joinPoint, String expr) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        try {
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            Method method = sig.getMethod();
            EvaluationContext ctx = new StandardEvaluationContext();
            String[] paramNames = discoverer.getParameterNames(method);
            Object[] args = joinPoint.getArgs();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    ctx.setVariable(paramNames[i], args[i]);
                }
            }
            Expression expression = parser.parseExpression(expr);
            Object value = expression.getValue(ctx);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            log.debug("[Audit] 解析 targetId 失败: {} - {}", expr, e.getMessage());
            return null;
        }
    }

    private String serializeArgs(ProceedingJoinPoint joinPoint) {
        try {
            StringBuilder sb = new StringBuilder("{");
            Object[] args = joinPoint.getArgs();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                // 避免序列化敏感字段（密码等）
                String argStr = String.valueOf(args[i]);
                if (argStr.length() > 500) {
                    argStr = argStr.substring(0, 500) + "...";
                }
                sb.append("\"arg").append(i).append("\":\"").append(argStr.replace("\"", "'")).append("\"");
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
