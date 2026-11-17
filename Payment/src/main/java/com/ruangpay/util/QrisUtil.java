package com.ruangpay.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class QrisUtil {
    private QrisUtil() {}

    public static String makeDynamic(String rawQris, BigDecimal amount) {
        if (rawQris == null || rawQris.isBlank())
            throw new IllegalArgumentException("QRIS raw belum dikonfigurasi");
        String raw = rawQris.replaceAll("\\s+", "");
        if (raw.length() < 16 || !raw.matches("[\\x20-\\x7E]+"))
            throw new IllegalArgumentException("QRIS raw tidak valid");
        StringBuilder rebuilt = new StringBuilder();
        int i = 0;
        while (i + 4 <= raw.length()) {
            String tag = raw.substring(i, i + 2);
            int length;
            try { length = Integer.parseInt(raw.substring(i + 2, i + 4)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Format QRIS tidak valid"); }
            int end = i + 4 + length;
            if (end > raw.length()) break;
            String value = raw.substring(i + 4, end);
            if (tag.equals("63")) break;
            if (!tag.equals("54")) {
                if (tag.equals("01")) value = "12";
                rebuilt.append(tag).append(String.format("%02d", value.length())).append(value);
            }
            i = end;
        }
        String nominal = amount.setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        rebuilt.append("54").append(String.format("%02d", nominal.length())).append(nominal);
        rebuilt.append("6304");
        return rebuilt + crc16(rebuilt.toString());
    }

    public static String crc16(String text) {
        int crc = 0xFFFF;
        for (int c = 0; c < text.length(); c++) {
            crc ^= text.charAt(c) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}