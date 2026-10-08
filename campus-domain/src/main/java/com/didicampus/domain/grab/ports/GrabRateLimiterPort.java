package com.didicampus.domain.grab.ports;

public interface GrabRateLimiterPort {

    boolean tryPass(long errandId, long runnerId);
}
