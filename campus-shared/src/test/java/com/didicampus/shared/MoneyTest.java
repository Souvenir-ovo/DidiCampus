package com.didicampus.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void shouldCalculateWithCents() {
        Money reward = Money.fromYuan("12.5");

        assertEquals(1250, reward.cents());
        assertEquals("15.00", reward.plus(Money.fromCents(250)).toString());
        assertEquals("10.00", reward.minus(Money.fromCents(250)).toString());
    }

    @Test
    void shouldRejectNegativeAndOverPreciseInput() {
        assertThrows(IllegalArgumentException.class, () -> Money.fromCents(-1));
        assertThrows(IllegalArgumentException.class, () -> Money.fromYuan("1.234"));
        assertThrows(IllegalArgumentException.class, () -> Money.ZERO.minus(Money.fromCents(1)));
    }
}
