package com.trex.server.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 메모리 사진 저장소 — 테스트와 버킷 없는 로컬 실행용. 주소는 {@code memory://<키>}(앱이 받을 수는 없다). */
public class InMemoryImageStorage implements ImageStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] jpeg) {
        objects.put(key, jpeg.clone());
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    @Override
    public String url(String key) {
        return "memory://" + key;
    }

    /** 테스트 확인용. */
    public boolean has(String key) {
        return objects.containsKey(key);
    }
}
