package com.khmerbank.util;

/**
 * CRC-16/CCITT-FALSE used by KHQR / EMV QR.
 * Polynomial: 0x1021, Initial: 0xFFFF, no reflection, XorOut: 0x0000.
 */
public final class CrcUtil {

    private CrcUtil() {}

    public static String crc16Hex(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes()) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}
