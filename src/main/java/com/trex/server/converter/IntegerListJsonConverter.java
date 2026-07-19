package com.trex.server.converter;

import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.List;

@Converter
public class IntegerListJsonConverter extends ListJsonConverter<Integer> {

    public IntegerListJsonConverter() {
        super(new TypeReference<List<Integer>>() {});
    }
}
