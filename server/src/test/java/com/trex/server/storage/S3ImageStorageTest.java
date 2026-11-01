package com.trex.server.storage;

import com.trex.server.config.CommunityProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S3 사진 저장소 — 서명 주소는 네트워크 없이 만들어진다(가짜 자격 증명). 버킷이 없으면 메모리, 운영이면 거절. */
class S3ImageStorageTest {

    private static final StaticCredentialsProvider FAKE = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATESTTEST", "secret"));

    @Test
    void 서명_주소는_버킷_키_수명_1시간을_담는다() {
        Region region = Region.AP_SOUTHEAST_2;
        try (S3Client client = S3Client.builder().region(region).credentialsProvider(FAKE).build();
             S3Presigner presigner = S3Presigner.builder().region(region).credentialsProvider(FAKE).build()) {
            String url = new S3ImageStorage(client, presigner, "trex-community-test").url("posts/1/0-a.jpg");
            assertTrue(url.contains("trex-community-test"), url);
            assertTrue(url.contains("posts/1/0-a.jpg"), url);
            assertTrue(url.contains("X-Amz-Expires=3600"), url);
        }
    }

    @Test
    void 버킷이_없으면_메모리_저장소() {
        ImageStorage storage = new ImageStorageConfig().imageStorage(new CommunityProperties("", null, null, null, false));
        assertInstanceOf(InMemoryImageStorage.class, storage);
    }

    @Test
    void 운영에서_버킷이_비면_뜨지_않는다() {
        assertThrows(IllegalStateException.class,
                () -> new ImageStorageConfig().imageStorage(new CommunityProperties("", null, null, null, true)));
    }

    @Test
    void 버킷_이름_형식이_아니면_뜨지_않고_까닭을_말한다() {
        // 2026-10-29 운영: 안내 문구의 꺾쇠를 남긴 채 넣어 사진 올릴 때에야 "서버 오류"로 드러났다
        for (String bad : new String[] {"<trex-community-k7m2qp>", "s3://trex-community-k7m2qp", "arn:aws:s3:::trex-community-k7m2qp", "Trex-Community"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new ImageStorageConfig().imageStorage(new CommunityProperties(bad, null, null, null, true)), bad);
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("COMMUNITY_S3_BUCKET"), e.getMessage());
        }
    }
}
