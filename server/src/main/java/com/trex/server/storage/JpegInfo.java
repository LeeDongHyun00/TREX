package com.trex.server.storage;

import com.trex.server.exception.InvalidRequestException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * JPEG 의 가로·세로. 헤더(SOF)까지만 읽고 픽셀은 디코딩하지 않는다 — 서버 힙이 384MB 라 사진을 펼치지 않는다.
 * 앱이 긴 변 1080px JPEG 로 다시 만들어 올리므로 JPEG 만 받는다.
 */
public record JpegInfo(int width, int height) {

    public static JpegInfo read(byte[] bytes) {
        if (bytes == null || bytes.length < 3
                || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8 || (bytes[2] & 0xFF) != 0xFF) {
            throw notJpeg();
        }
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
        if (!readers.hasNext()) throw notJpeg();
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(in, true, true);
            return new JpegInfo(reader.getWidth(0), reader.getHeight(0));
        } catch (IOException | RuntimeException e) {
            throw notJpeg();
        } finally {
            reader.dispose();
        }
    }

    private static InvalidRequestException notJpeg() {
        return new InvalidRequestException("JPEG 사진만 올릴 수 있어요");
    }
}
