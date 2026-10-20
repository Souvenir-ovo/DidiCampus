package com.didicampus.presentation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonConfigTest {

    @Test
    void serializes_long_ids_as_strings() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        String json = mapper.writeValueAsString(new IdView(9007199254740993L, 7L));

        assertTrue(json.contains("\"id\":\"9007199254740993\""));
        assertTrue(json.contains("\"count\":\"7\""));
    }

    private record IdView(long id, Long count) {
    }
}
