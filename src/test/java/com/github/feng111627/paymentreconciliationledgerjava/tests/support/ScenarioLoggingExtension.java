package com.github.feng111627.paymentreconciliationledgerjava.tests.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试日志扩展：在控制台按“业务模块 -> 用例”打印覆盖与通过情况，
 * 使 mvn test 的日志可以直接看出覆盖了哪些业务、各业务下用例与结果。
 */
public class ScenarioLoggingExtension implements BeforeAllCallback, BeforeEachCallback,
        AfterAllCallback, TestWatcher {

    private static final Map<String, Integer> PASSED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> FAILED = new ConcurrentHashMap<>();

    private static String business(ExtensionContext ctx) {
        Class<?> clazz = ctx.getTestClass().orElse(Object.class);
        BusinessScenario ann = clazz.getAnnotation(BusinessScenario.class);
        return ann == null ? clazz.getSimpleName() : ann.value();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        String biz = business(context);
        System.out.println();
        System.out.println("================ 业务模块: " + biz + " ================");
        PASSED.put(biz, 0);
        FAILED.put(biz, 0);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        String biz = business(context);
        String name = context.getDisplayName();
        System.out.println("[运行] " + biz + " > " + name);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        String biz = business(context);
        PASSED.merge(biz, 1, Integer::sum);
        System.out.println("  [通过] " + context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        String biz = business(context);
        FAILED.merge(biz, 1, Integer::sum);
        System.out.println("  [失败] " + context.getDisplayName()
                + " -> " + (cause == null ? "" : cause));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        System.out.println("  [中止] " + context.getDisplayName());
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        System.out.println("  [跳过] " + context.getDisplayName() + " 原因: " + reason.orElse(""));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String biz = business(context);
        int p = PASSED.getOrDefault(biz, 0);
        int f = FAILED.getOrDefault(biz, 0);
        System.out.println("---------------- " + biz + " 小结: 通过 " + p
                + " / 失败 " + f + " ----------------");
        System.out.println();
    }
}
