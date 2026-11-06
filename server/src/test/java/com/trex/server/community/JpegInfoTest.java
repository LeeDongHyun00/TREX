package com.trex.server.community;

import com.trex.server.exception.InvalidRequestException;
import com.trex.server.storage.JpegInfo;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** JPEG 헤더만 읽어 가로·세로를 얻는다 — 서버는 사진을 디코딩하지 않는다(힙 384MB). */
class JpegInfoTest {

    static byte[] image(int w, int h, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    @Test
    void JPEG의_가로_세로를_읽는다() throws IOException {
        assertEquals(new JpegInfo(3, 2), JpegInfo.read(image(3, 2, "jpg")));
    }

    @Test
    void PNG와_임의_바이트는_거절한다() throws IOException {
        byte[] png = image(3, 2, "png");
        assertThrows(InvalidRequestException.class, () -> JpegInfo.read(png));
        assertThrows(InvalidRequestException.class, () -> JpegInfo.read(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3}));
        assertThrows(InvalidRequestException.class, () -> JpegInfo.read(new byte[0]));
    }
}
