package com.didicampus.domain.auth.ports;

import java.util.Optional;

public interface AuthPort {

    String login(long userId);

    Optional<Long> resolve(String token);

    void logout(String token);
}
