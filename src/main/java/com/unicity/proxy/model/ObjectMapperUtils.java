package com.unicity.proxy.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class ObjectMapperUtils {
    public static ObjectMapper createObjectMapper() {
        final ObjectMapper result = new ObjectMapper();
        result.findAndRegisterModules(); // For Java 8 time support
        result.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        result.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return result;
    }
}
