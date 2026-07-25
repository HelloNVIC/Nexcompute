package com.nexcompute.management.security;

import com.nexcompute.management.domain.UserRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式权限校验注解（任务 2.4）
 * 在 Controller 方法上标注所需模块与操作，拦截器按权限矩阵校验。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 模块代码（如 container / storage-pool） */
    String module();

    /** 操作：view / edit / delete */
    Action action() default Action.VIEW;

    enum Action {
        VIEW, EDIT, DELETE
    }
}
