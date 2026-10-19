package com.openplan.backend.push.crypto;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * ECDSA 서명 표현 변환 — JDK {@link java.security.Signature}는 ASN.1 DER(SEQUENCE{INTEGER r, INTEGER s})만
 * 내놓는데, JWS ES256(RFC 7518 §3.4)은 고정 길이 {@code R || S}(JOSE) 를 요구한다. RFC 8292(VAPID)의
 * JWT가 바로 이 포맷이라 변환이 필요하다.
 *
 * <p>{@link #joseToDer}는 운영 경로에는 없다 — JDK {@code Signature.verify}가 DER만 받기 때문에
 * <b>테스트가</b> 우리가 만든 JOSE 서명을 검증하려 할 때만 쓴다(서명 생성은 {@link #derToJose} 한 방향).
 */
final class EcdsaSignatureCodec {

    private EcdsaSignatureCodec() {
    }

    /** DER → JOSE. P-256 전제로 R·S 각각 {@code fieldSize}(32)바이트로 좌측 0-패딩해 이어 붙인다. */
    static byte[] derToJose(byte[] der, int fieldSize) {
        int pos = 0;
        require(der[pos++] == 0x30, "SEQUENCE 태그가 아닙니다");
        pos = readLength(der, pos)[1]; // SEQUENCE 전체 길이는 쓰지 않는다 — r/s를 순서대로 읽으면 끝까지 소비된다
        require(der[pos++] == 0x02, "r INTEGER 태그가 아닙니다");
        int[] rLen = readLength(der, pos);
        pos = rLen[1];
        byte[] r = Arrays.copyOfRange(der, pos, pos + rLen[0]);
        pos += rLen[0];
        require(der[pos++] == 0x02, "s INTEGER 태그가 아닙니다");
        int[] sLen = readLength(der, pos);
        pos = sLen[1];
        byte[] s = Arrays.copyOfRange(der, pos, pos + sLen[0]);

        byte[] out = new byte[fieldSize * 2];
        copyUnsignedFixedLength(r, out, 0, fieldSize);
        copyUnsignedFixedLength(s, out, fieldSize, fieldSize);
        return out;
    }

    /** JOSE → DER. 테스트에서 {@code Signature.verify}(DER 전용)로 검증하기 위한 역방향. */
    static byte[] joseToDer(byte[] jose, int fieldSize) {
        byte[] r = toMinimalSignedInteger(Arrays.copyOfRange(jose, 0, fieldSize));
        byte[] s = toMinimalSignedInteger(Arrays.copyOfRange(jose, fieldSize, fieldSize * 2));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x02);
        out.write(r.length); // P-256 r/s DER 인코딩은 최대 33바이트라 길이가 항상 1바이트(short-form)로 표현된다
        out.write(r, 0, r.length);
        out.write(0x02);
        out.write(s.length);
        out.write(s, 0, s.length);
        byte[] content = out.toByteArray();
        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        seq.write(0x30);
        seq.write(content.length);
        seq.write(content, 0, content.length);
        return seq.toByteArray();
    }

    /** DER INTEGER 본문(최소 바이트 표현, 최상위 비트가 서면 0x00 패딩으로 양수 표시)으로 변환. */
    private static byte[] toMinimalSignedInteger(byte[] fixedLengthUnsigned) {
        int start = 0;
        while (start < fixedLengthUnsigned.length - 1 && fixedLengthUnsigned[start] == 0) {
            start++;
        }
        boolean needsPadding = (fixedLengthUnsigned[start] & 0x80) != 0;
        byte[] result = new byte[fixedLengthUnsigned.length - start + (needsPadding ? 1 : 0)];
        System.arraycopy(fixedLengthUnsigned, start, result, needsPadding ? 1 : 0, fixedLengthUnsigned.length - start);
        return result;
    }

    /** 고정 길이(len) 목적지에 가변 길이 비서명 정수를 우측 정렬(좌측 0-패딩)로 복사. */
    private static void copyUnsignedFixedLength(byte[] src, byte[] dest, int destOffset, int len) {
        int srcStart = 0;
        while (srcStart < src.length - 1 && src[srcStart] == 0) {
            srcStart++; // DER이 양수 표시를 위해 붙인 선두 0x00 제거
        }
        int srcLen = src.length - srcStart;
        require(srcLen <= len, "정수 길이가 필드 크기를 초과합니다");
        System.arraycopy(src, srcStart, dest, destOffset + (len - srcLen), srcLen);
    }

    /** @return {length, nextOffset} — short-form(0x7F 이하)·long-form(0x81/0x82...) 모두 처리. */
    private static int[] readLength(byte[] data, int pos) {
        int first = data[pos] & 0xFF;
        if ((first & 0x80) == 0) {
            return new int[]{first, pos + 1};
        }
        int numBytes = first & 0x7F;
        int len = 0;
        for (int i = 0; i < numBytes; i++) {
            len = (len << 8) | (data[pos + 1 + i] & 0xFF);
        }
        return new int[]{len, pos + 1 + numBytes};
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
