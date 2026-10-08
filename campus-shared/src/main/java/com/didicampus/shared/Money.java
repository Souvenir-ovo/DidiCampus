package com.didicampus.shared;

import java.util.Objects;

/**
 * 资金值对象，内部单位固定为“分”。
 *
 * <p>金额使用整数保存，避免浮点类型带来的精度误差。
 * 该对象不可变，所有算术操作都会返回新的实例。</p>
 */
public record Money(long cents) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("金额不能为负: " + cents);
        }
    }

    public static Money fromCents(long cents) {
        return new Money(cents);
    }

    /**
     * 从元字符串创建金额，例如 "12.50" 表示 1250 分。
     * 展示输入最多保留两位小数，避免静默截断资金。
     */
    public static Money fromYuan(String yuan) {
        Objects.requireNonNull(yuan, "yuan");
        String normalized = yuan.trim();
        if (!normalized.matches("\\d+(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("金额格式必须为非负数字且最多两位小数: " + yuan);
        }

        int point = normalized.indexOf('.');
        String whole = point < 0 ? normalized : normalized.substring(0, point);
        String fraction = point < 0 ? "" : normalized.substring(point + 1);
        String paddedFraction = (fraction + "00").substring(0, 2);

        try {
            long yuanPart = Math.multiplyExact(Long.parseLong(whole), 100L);
            long centPart = Long.parseLong(paddedFraction);
            return new Money(Math.addExact(yuanPart, centPart));
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("金额超出可表示范围: " + yuan, ex);
        }
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "other");
        return new Money(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        Objects.requireNonNull(other, "other");
        return new Money(Math.subtractExact(cents, other.cents));
    }

    public boolean isZero() {
        return cents == 0;
    }

    public boolean greaterThan(Money other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Money other) {
        Objects.requireNonNull(other, "other");
        return Long.compare(cents, other.cents);
    }

    @Override
    public String toString() {
        return "%d.%02d".formatted(cents / 100, cents % 100);
    }
}
