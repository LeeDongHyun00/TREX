package com.trex.server.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * 운영 사진 저장소 — 비공개 S3 버킷. 앱은 서버가 내준 서명 주소(수명 {@link #URL_TTL})로 사진을 받는다:
 * 버킷은 공개하지 않고, 주소가 새도 한 시간 뒤면 막힌다. 자격 증명은 기본 공급자(EC2 인스턴스 역할).
 */
public class S3ImageStorage implements ImageStorage {

    public static final Duration URL_TTL = Duration.ofHours(1);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ImageStorage(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] jpeg) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/jpeg").build(),
                RequestBody.fromBytes(jpeg));
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public String url(String key) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(URL_TTL).getObjectRequest(get).build())
                .url().toString();
    }
}
