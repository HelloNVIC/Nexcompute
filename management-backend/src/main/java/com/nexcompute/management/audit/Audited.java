package com.nexcompute.management.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计注解（任务 2.5）
 * 标注于 Controller 方法上，AOP 切面自动记录操作至审计日志。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 操作类型（如 CONTAINER_CREATE / STORAGE_POOL_MIGRATE / POWERSHELL_EXEC） */
    String action();

    /** 操作目标类型（如 CONTAINER / STORAGE_POOL / PHYSICAL_INSTANCE） */
    String targetType() default "";

    /** 目标 ID 的 SpEL 表达式（如 #id 或 #request.containerId） */
    String targetIdExpr() default "";
}
