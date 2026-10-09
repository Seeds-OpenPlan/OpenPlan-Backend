package com.openplan.backend.push.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * RFC 8291 "Message Encryption for Web Push" — {@code aes128gcm} 콘텐츠 인코딩 구현.
 *
 * <p>왜 직접 구현하는가(ADR-0015 결정 ⑤): 대표 Java web-push 라이브러리가 BouncyCastle을 끌고 오고
 * 유지보수가 멈춰 있다. 이 RFC가 쓰는 1차 암호(ECDH P-256 · HKDF-SHA256 · AES-128-GCM)는 전부 JDK
 * 표준 {@code javax.crypto}/{@code java.security}에 있어, 라이브러리 의존보다 80줄 직접 구현이 가볍다.
 * 정확성은 라이브러리 신뢰 대신 <b>RFC 8291 §5 예시 벡터 전체 일치</b>로 증명한다({@code WebPushEncryptorTest}).
 *
 * <p>단일 레코드 전제(RFC §4 MUST): 푸시 메시지는 항상 레코드 1개로 암호화한다 — nonce에 레코드
 * 시퀀스 번호를 XOR하는 단계(§3.4 pseudocode의 생략 설명)가 그래서 없다. record size(rs)는 호출자가
 * 지정한다(평문+패딩구분자(1)+AEAD 확장(16)보다 커야 한다 — RFC §4).
 */
public final class WebPushEncryptor {

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CEK_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    /** 패딩 구분자(0x02) — 이 뒤에 패딩 0x00들을 더 붙일 수 있으나 우리는 패딩을 쓰지 않는다(길이 은닉 불필요). */
    private static final byte PADDING_DELIMITER = 0x02;

    private WebPushEncryptor() {
    }

    /**
     * 메시지를 암호화해 (헤더+ciphertext) 바이트열을 돌려준다 — HTTP 바디에 그대로 싣는다.
     *
     * @param receiverPublicKey 구독의 {@code p256dh}(수신자 ECDH 공개키)
     * @param authSecret        구독의 {@code auth}(16바이트 인증 비밀)
     * @param salt              16바이트 — 매 발송마다 새로 뽑는다(재사용 금지, RFC §3.4)
     * @param senderEcKeyPair   <b>1회용</b> ECDH 키쌍 — VAPID 서명 키와 무관하게 매 발송 직전에 새로 만든다
     * @param recordSize        단일 레코드 크기(rs) — 평문+1+16보다 커야 한다
     * @param plaintext         원문 페이로드(JSON)
     */
    public static byte[] encrypt(ECPublicKey receiverPublicKey, byte[] authSecret, byte[] salt,
                                  KeyPair senderEcKeyPair, int recordSize, byte[] plaintext)
            throws GeneralSecurityException {
        byte[] senderPublicRaw = EcKeys.encodePublicKey((ECPublicKey) senderEcKeyPair.getPublic());
        byte[] receiverPublicRaw = EcKeys.encodePublicKey(receiverPublicKey);

        byte[] ecdhSecret = ecdh((ECPrivateKey) senderEcKeyPair.getPrivate(), receiverPublicKey);

        // RFC 8291 §3.3: ECDH 비밀과 인증 비밀을 HKDF로 섞어 RFC 8188용 IKM을 만든다.
        byte[] prkKey = hmacSha256(authSecret, ecdhSecret);
        byte[] keyInfo = concat(KEY_INFO_PREFIX, receiverPublicRaw, senderPublicRaw);
        byte[] ikm = hkdfExpand(prkKey, keyInfo, 32);

        // RFC 8188 §2.2: salt + IKM → PRK → CEK/NONCE.
        byte[] prk = hmacSha256(salt, ikm);
        byte[] cek = hkdfExpand(prk, CEK_INFO, CEK_LENGTH);
        byte[] nonce = hkdfExpand(prk, NONCE_INFO, NONCE_LENGTH);

        byte[] header = buildHeader(salt, recordSize, senderPublicRaw);
        byte[] padded = concat(plaintext, new byte[]{PADDING_DELIMITER});
        byte[] ciphertext = aesGcmEncrypt(cek, nonce, padded);
        return concat(header, ciphertext);
    }

    /** RFC 8188 §2.1 헤더: salt(16) | rs(4, big-endian) | idlen(1) | keyid(idlen=65, 발신자 공개키). */
    private static byte[] buildHeader(byte[] salt, int recordSize, byte[] senderPublicRaw) {
        ByteBuffer buf = ByteBuffer.allocate(16 + 4 + 1 + senderPublicRaw.length);
        buf.put(salt);
        buf.putInt(recordSize);
        buf.put((byte) senderPublicRaw.length);
        buf.put(senderPublicRaw);
        return buf.array();
    }

    private static byte[] ecdh(ECPrivateKey privateKey, ECPublicKey publicKey) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(privateKey);
        ka.doPhase(publicKey, true);
        return ka.generateSecret();
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        // RFC 5869 HKDF-Extract(salt, IKM) = HMAC(key=salt, data=IKM) — 여기서 salt 역할은 호출부에
        // 따라 auth_secret(키 결합 단계) 또는 RFC 8188의 salt(콘텐츠 암호화 단계) 둘 다를 가리킨다.
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /**
     * RFC 5869 HKDF-Expand의 첫 블록만 계산한다(T(1) = HMAC(PRK, info || 0x01)) — 이 RFC가 요구하는
     * 모든 길이(32·16·12바이트)가 SHA-256 출력(32바이트) 이하라 블록 1개로 충분하다(RFC 8291 §3.4 pseudocode 그대로).
     */
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        byte[] t1 = hmacSha256(prk, concat(info, new byte[]{0x01}));
        byte[] out = new byte[length];
        System.arraycopy(t1, 0, out, 0, length);
        return out;
    }

    private static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // 태그 길이 128비트(16바이트) — JCE가 ciphertext 뒤에 태그를 자동으로 덧붙인다(AEAD_AES_128_GCM과 동일 출력 형태).
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(plaintext);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
