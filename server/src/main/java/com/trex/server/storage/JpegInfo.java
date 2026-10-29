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

    /** 한 변 상한 — 앱은 1080 으로 보낸다. 헤더만 크게 선언한 작은 파일이 다른 사람 앱의 피드 디코딩을 터뜨리지 않게(리뷰 2026-10-29). */
    public static final int MAX_SIDE = 4096;

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
            JpegInfo info = new JpegInfo(reader.getWidth(0), reader.getHeight(0));
            if (info.width() > MAX_SIDE || info.height() > MAX_SIDE) {
                throw new InvalidRequestException("사진이 너무 커요(한 변 4096px 까지)");
            }
            return info;
        } catch (InvalidRequestException e) {
            throw e;
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
