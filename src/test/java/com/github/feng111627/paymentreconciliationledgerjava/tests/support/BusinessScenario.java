package com.github.feng111627.paymentreconciliationledgerjava.tests.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标注测试类对应的业务模块名，用于在测试日志中分组展示覆盖情况。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BusinessScenario {
    String value();
}
