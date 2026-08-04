package com.trex.server.converter;

import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.List;

@Converter
public class StringListJsonConverter extends ListJsonConverter<String> {

    public StringListJsonConverter() {
        super(new TypeReference<List<String>>() {});
    }
}
