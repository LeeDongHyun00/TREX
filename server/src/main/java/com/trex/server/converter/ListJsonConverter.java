package com.trex.server.converter;

import jakarta.persistence.AttributeConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

// List<T> <-> JSON 문자열 컬럼 변환 공통 구현. 요소 타입별 하위 클래스에서 TypeReference만 지정한다.
public abstract class ListJsonConverter<T> implements AttributeConverter<List<T>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TypeReference<List<T>> typeReference;

    protected ListJsonConverter(TypeReference<List<T>> typeReference) {
        this.typeReference = typeReference;
    }

    @Override
    public String convertToDatabaseColumn(List<T> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
        } catch (JacksonException e) {
            throw new IllegalStateException("목록을 JSON으로 변환하지 못했습니다", e);
        }
    }

    @Override
    public List<T> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, typeReference);
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON을 목록으로 변환하지 못했습니다", e);
        }
    }
}
