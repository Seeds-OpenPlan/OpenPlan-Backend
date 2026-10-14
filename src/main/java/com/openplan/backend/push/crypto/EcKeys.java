package com.openplan.backend.push.crypto;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * P-256(secp256r1) 키 인코딩/디코딩 — RFC 8291(메시지 암호화)·RFC 8292(VAPID) 양쪽이 쓰는 형식을
 * JDK 표준 {@code java.security} 타입으로 왕복시킨다. 외부 암호 라이브러리(BouncyCastle 등)를 쓰지
 * 않기로 한 ADR-0015 결정 ⑤의 실행 지점 — 이 클래스가 유일하게 ASN.1/EC 파라미터를 직접 다룬다.
 *
 * <p>형식(ADR-0015 명세): 공개키 = 비압축 X9.62 점(0x04 || X(32) || Y(32), 65바이트) base64url.
 * 개인키 = 32바이트 스칼라 base64url. 둘 다 브라우저 Push API({@code applicationServerKey})·
 * 흔한 web-push 툴링이 쓰는 그 형식이다 — 변환 없이 그대로 교환 가능해야 운영 편의가 생긴다.
 */
public final class EcKeys {

    /** 비압축 점 1개 길이: 0x04 + X(32) + Y(32). */
    public static final int UNCOMPRESSED_POINT_LENGTH = 65;
    private static final int FIELD_SIZE = 32;

    private static final ECParameterSpec P256_PARAMS = loadP256Params();

    private EcKeys() {
    }

    private static ECParameterSpec loadP256Params() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            // JDK 표준 알고리즘 조회 실패 — 로컬 JRE 구성 문제. 기동 시점에 바로 드러나야 한다.
            throw new IllegalStateException("P-256 파라미터를 불러올 수 없습니다", e);
        }
    }

    public static ECParameterSpec p256Params() {
        return P256_PARAMS;
    }

    /** 65바이트 비압축 점 → {@link ECPublicKey}. 길이·선두 바이트(0x04)가 아니면 예외. */
    public static ECPublicKey decodePublicKey(byte[] uncompressedPoint) throws GeneralSecurityException {
        if (uncompressedPoint.length != UNCOMPRESSED_POINT_LENGTH || uncompressedPoint[0] != 0x04) {
            throw new java.security.InvalidKeyException("비압축 P-256 점(65바이트, 0x04 시작)이 아닙니다");
        }
        BigInteger x = new BigInteger(1, slice(uncompressedPoint, 1, FIELD_SIZE));
        BigInteger y = new BigInteger(1, slice(uncompressedPoint, 1 + FIELD_SIZE, FIELD_SIZE));
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256_PARAMS));
    }

    public static ECPublicKey decodePublicKeyBase64Url(String base64Url) throws GeneralSecurityException {
        return decodePublicKey(Base64.getUrlDecoder().decode(base64Url));
    }

    /** 32바이트 스칼라 → {@link ECPrivateKey}. */
    public static ECPrivateKey decodePrivateKey(byte[] scalar) throws GeneralSecurityException {
        BigInteger s = new BigInteger(1, scalar);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPrivateKey) kf.generatePrivate(new ECPrivateKeySpec(s, P256_PARAMS));
    }

    public static ECPrivateKey decodePrivateKeyBase64Url(String base64Url) throws GeneralSecurityException {
        return decodePrivateKey(Base64.getUrlDecoder().decode(base64Url));
    }

    /** {@link ECPublicKey} → 65바이트 비압축 점(X,Y 각각 32바이트로 좌측 0-패딩). */
    public static byte[] encodePublicKey(ECPublicKey key) {
        byte[] out = new byte[UNCOMPRESSED_POINT_LENGTH];
        out[0] = 0x04;
        writeFixedLength(key.getW().getAffineX(), out, 1);
        writeFixedLength(key.getW().getAffineY(), out, 1 + FIELD_SIZE);
        return out;
    }

    public static String encodePublicKeyBase64Url(ECPublicKey key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encodePublicKey(key));
    }

    /** BigInteger → 정확히 {@code len}바이트(부족하면 선두 0 패딩, {@code BigInteger}의 부호 바이트는 제거). */
    private static void writeFixedLength(BigInteger value, byte[] dest, int offset) {
        byte[] raw = value.toByteArray();
        int rawStart = (raw.length > FIELD_SIZE) ? raw.length - FIELD_SIZE : 0; // 선두 0x00 부호 바이트 제거
        int rawLen = raw.length - rawStart;
        int destStart = offset + (FIELD_SIZE - rawLen);
        System.arraycopy(raw, rawStart, dest, destStart, rawLen);
    }

    private static byte[] slice(byte[] src, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(src, offset, out, 0, length);
        return out;
    }
}
