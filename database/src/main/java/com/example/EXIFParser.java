package com.example;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class EXIFParser {

    static class ExifData {
        String date;
        Double lat, lon, alt;
    }

    static int tiffBase;

    public static ExifData parse(String file) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "r");

        // Returns empty instead of crashing
        if (raf.readUnsignedShort() != 0xFFD8) {
            raf.close();
            return new ExifData();
        }

        int b;
        while ((b = raf.read()) != -1) {
            if (b == 0xFF) {
                int marker = raf.read();
                if (marker == 0xE1) {
                    int len = raf.readUnsignedShort();
                    byte[] exif = new byte[len - 2];
                    raf.readFully(exif);
                    raf.close();
                    return parseExif(exif);
                }
            }
        }
        raf.close();
        return new ExifData();
    }

    public static ExifData parseExif(byte[] buf) {
        ByteBuffer bb = ByteBuffer.wrap(buf);

        byte[] hdr = new byte[6];
        bb.get(hdr);
        if (!new String(hdr).startsWith("Exif"))
            throw new RuntimeException("Not EXIF APP1");

        tiffBase = bb.position();

        short endian = bb.getShort();
        boolean little = (endian == 0x4949);
        bb.order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        bb.getShort(); // 0x002A
        int ifd0Offset = bb.getInt();

        ExifData d = new ExifData();
        parseIFD(bb, tiffBase + ifd0Offset, d);
        return d;
    }

    static void parseIFD(ByteBuffer bb, int offset, ExifData d) {
        bb.position(offset);
        int entries = bb.getShort() & 0xFFFF;

        int exifOffset = -1, gpsOffset = -1;

        for (int i = 0; i < entries; i++) {
            int tag = bb.getShort() & 0xFFFF;
            int type = bb.getShort() & 0xFFFF;
            int count = bb.getInt();
            int value = bb.getInt();

            if (tag == 0x8769)
                exifOffset = value; // Exif SubIFD
            if (tag == 0x8825)
                gpsOffset = value; // GPS IFD
        }
        if (exifOffset > 0)
            parseExifIFD(bb, tiffBase + exifOffset, d);
        if (gpsOffset > 0)
            parseGPSIFD(bb, tiffBase + gpsOffset, d);
    }

    static void parseExifIFD(ByteBuffer bb, int offset, ExifData d) {
        bb.position(offset);
        int entries = bb.getShort() & 0xFFFF;

        for (int i = 0; i < entries; i++) {
            int tag = bb.getShort() & 0xFFFF;
            int type = bb.getShort() & 0xFFFF;
            int count = bb.getInt();
            int value = bb.getInt();

            if (tag == 0x9003) { // DateTimeOriginal
                int pos = bb.position();
                bb.position(tiffBase + value);
                byte[] s = new byte[count];
                bb.get(s);
                d.date = new String(s).trim();
                bb.position(pos);
            }
        }
    }

    static char charRef(ByteBuffer bb, int type, int count, int value) {
        if (type != 2 || count < 1) return 0;

        int size = count; // 1 byte each
        int pos = bb.position();
        byte b;

        if (size <= 4) {
            if (bb.order() == ByteOrder.LITTLE_ENDIAN)
                b = (byte)(value & 0xFF);
            else
                b = (byte)((value >> 24) & 0xFF);
        } else {
            int target = tiffBase + value;
            if (target < 0 || target >= bb.limit()) return 0;

            bb.position(target);
            b = bb.get();
            bb.position(pos);
        }
        return (char)b;
    }

    static void parseGPSIFD(ByteBuffer bb, int offset, ExifData d) {
        bb.position(offset);
        int entries = bb.getShort() & 0xFFFF;

        char latRef = 0, lonRef = 0;
        int latOffset = 0, lonOffset = 0;
        int altRef = 0, altOffset = 0;

        for (int i = 0; i < entries; i++) {
            int tag = bb.getShort() & 0xFFFF;
            int type = bb.getShort() & 0xFFFF;
            int count = bb.getInt();
            int value = bb.getInt();

            if (tag == 1)
                latRef = charRef(bb, type, count, value);
            if (tag == 2)
                latOffset = value;
            if (tag == 3)
                lonRef = charRef(bb, type, count, value);
            if (tag == 4)
                lonOffset = value;
            if (tag == 5)
                altRef = value;
            if (tag == 6)
                altOffset = value;
        }
        if (latOffset > 0 && lonOffset > 0) {
            d.lat = readRationalTriplet(bb, tiffBase + latOffset);
            d.lon = readRationalTriplet(bb, tiffBase + lonOffset);
            if (latRef == 'S') d.lat = -d.lat;
            if (lonRef == 'W') d.lon = -d.lon;
        }
        if (altOffset > 0) {
            double alt = readRational(bb, tiffBase + altOffset);
            if (altRef == 1)
                alt = -alt;
            d.alt = alt;
        }
    }

    static double readRationalTriplet(ByteBuffer bb, int offset) {
        bb.position(offset);
        double[] v = new double[3];

        for (int i = 0; i < 3; i++) {
            long num = bb.getInt() & 0xFFFFFFFFL;
            long den = bb.getInt() & 0xFFFFFFFFL;
            v[i] = (double) num / den;
        }
        return v[0] + v[1] / 60.0 + v[2] / 3600.0;
    }

    static double readRational(ByteBuffer bb, int offset) {
        bb.position(offset);
        long num = bb.getInt() & 0xFFFFFFFFL;
        long den = bb.getInt() & 0xFFFFFFFFL;
        return (double) num / den;
    }
}
