package com.example;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.util.Objects;
import javax.imageio.ImageIO;

public class ImageUtils {

    static class ExifData {
        String date;
        Double lat, lon, alt;
    }

    private static int tiffBase;

    public static File convertToJpg(File f) throws IOException, InterruptedException {
        String ext = getExtension(f.getName()).toLowerCase();
        if (ext.equals("jpg") || ext.equals("jpeg")) {
            return f;
        }

        File jpgFile = new File(f.getParent(), removeExtension(f.getName()) + ".jpeg");

        if (ext.equals("heic") || ext.equals("heif")) {
            ProcessBuilder pb = new ProcessBuilder(
                    "sips", "-s", "format", "jpeg", f.getAbsolutePath(), "--out", jpgFile.getAbsolutePath());
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new IOException("sips failed " + f.getName());
            }

            if (!f.delete()) {
                System.err.println("Duplicate insertion HEIC" + f.getName());
            }

        } else {
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                throw new IOException("Unsupported image format: " + f.getAbsolutePath());
            }
            ImageIO.write(img, "jpg", jpgFile);
        }

        return jpgFile;
    }

    public static int getWidth(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) {
            throw new IOException("Not an image: " + f);
        }
        return img.getWidth();
    }

    public static int getHeight(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) {
            throw new IOException("Not an image: " + f);
        }
        return img.getHeight();
    }

    public static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot == -1) ? "" : filename.substring(dot + 1);
    }

    private static String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot == -1) ? filename : filename.substring(0, dot);
    }

    public static File convertPngToJpg(File f) throws IOException {
        String ext = getExtension(f.getName()).toLowerCase();
        if (ext.equals("jpg") || ext.equals("jpeg")) {
            return f;
        }
        if (!ext.equals("png")) {
            throw new IOException("Unsupported image format: " + f.getAbsolutePath());
        }

        File jpgFile = new File(f.getParent(), removeExtension(f.getName()) + ".jpeg");

        byte[] pngBytes = Files.readAllBytes(f.toPath());
        byte[] exif = extractExifFromPng(pngBytes);

        BufferedImage img = ImageIO.read(f);
        if (img == null) {
            throw new IOException("Unsupported image format: " + f.getAbsolutePath());
        }

        BufferedImage rgb = img;
        if (img.getColorModel().hasAlpha()) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpeg", baos);
        byte[] jpegBytes = baos.toByteArray();

        jpegBytes = insertExifIntoJpeg(jpegBytes, exif);

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(jpgFile))) {
            out.write(jpegBytes);
        }

        return jpgFile;
    }

    private static byte[] extractExifFromPng(byte[] pngBytes) throws IOException {
        if (pngBytes.length < 8) {
            throw new IOException("Invalid PNG: too short");
        }

        byte[] sig = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        for (int i = 0; i < sig.length; i++) {
            if (pngBytes[i] != sig[i]) {
                throw new IOException("Invalid PNG signature");
            }
        }

        int offset = 8;
        while (offset + 12 <= pngBytes.length) {
            int length = readIntBE(pngBytes, offset);
            offset += 4;
            if (offset + 4 > pngBytes.length) {
                break;
            }

            String type = new String(pngBytes, offset, 4, StandardCharsets.ISO_8859_1);
            offset += 4;

            if (length < 0 || offset + length + 4 > pngBytes.length) {
                break;
            }

            if (type.equals("eXIf")) {
                byte[] exif = new byte[length];
                System.arraycopy(pngBytes, offset, exif, 0, length);
                return exif;
            }

            offset += length + 4;
        }

        return null;
    }

    public static ExifData parseExifFromPng(File f) throws IOException {
        byte[] pngBytes = Files.readAllBytes(f.toPath());
        byte[] exifBytes = extractExifFromPng(pngBytes);
        if (exifBytes == null || exifBytes.length == 0) {
            return null;
        }

        byte[] normalizedExif = ensureExifHeader(exifBytes);
        return parseExif(normalizedExif);
    }

    public static ExifData parse(String file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.readUnsignedShort() != 0xFFD8) {
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
                        return parseExif(exif);
                    }
                }
            }
            return new ExifData();
        }
    }

    public static ExifData parseExif(byte[] buf) {
        ByteBuffer bb = ByteBuffer.wrap(buf);

        byte[] hdr = new byte[6];
        bb.get(hdr);
        if (!new String(hdr, StandardCharsets.ISO_8859_1).startsWith("Exif")) {
            throw new RuntimeException("Not EXIF APP1");
        }

        tiffBase = bb.position();

        short endian = bb.getShort();
        boolean little = (endian == 0x4949);
        bb.order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        bb.getShort();
        int ifd0Offset = bb.getInt();

        ExifData d = new ExifData();
        parseIFD(bb, tiffBase + ifd0Offset, d);
        return d;
    }

    private static void parseIFD(ByteBuffer bb, int offset, ExifData d) {
        bb.position(offset);
        int entries = bb.getShort() & 0xFFFF;

        int exifOffset = -1;
        int gpsOffset = -1;

        for (int i = 0; i < entries; i++) {
            int tag = bb.getShort() & 0xFFFF;
            bb.getShort();
            bb.getInt();
            int value = bb.getInt();

            if (tag == 0x8769) {
                exifOffset = value;
            }
            if (tag == 0x8825) {
                gpsOffset = value;
            }
        }
        if (exifOffset > 0) {
            parseExifIFD(bb, tiffBase + exifOffset, d);
        }
        if (gpsOffset > 0) {
            parseGPSIFD(bb, tiffBase + gpsOffset, d);
        }
    }

    private static void parseExifIFD(ByteBuffer bb, int offset, ExifData d) {
        bb.position(offset);
        int entries = bb.getShort() & 0xFFFF;

        for (int i = 0; i < entries; i++) {
            int tag = bb.getShort() & 0xFFFF;
            bb.getShort();
            int count = bb.getInt();
            int value = bb.getInt();

            if (tag == 0x9003) {
                int pos = bb.position();
                bb.position(tiffBase + value);
                byte[] s = new byte[count];
                bb.get(s);
                d.date = new String(s, StandardCharsets.ISO_8859_1).trim();
                bb.position(pos);
            }
        }
    }

    private static char charRef(ByteBuffer bb, int type, int count, int value) {
        if (type != 2 || count < 1) {
            return 0;
        }

        int size = count;
        int pos = bb.position();
        byte b;

        if (size <= 4) {
            if (bb.order() == ByteOrder.LITTLE_ENDIAN) {
                b = (byte) (value & 0xFF);
            } else {
                b = (byte) ((value >> 24) & 0xFF);
            }
        } else {
            int target = tiffBase + value;
            if (target < 0 || target >= bb.limit()) {
                return 0;
            }

            bb.position(target);
            b = bb.get();
            bb.position(pos);
        }
        return (char) b;
    }

    private static void parseGPSIFD(ByteBuffer bb, int offset, ExifData d) {
        bb.position(offset);
        int entries = bb.getShort() & 0xFFFF;

        char latRef = 0;
        char lonRef = 0;
        int latOffset = 0;
        int lonOffset = 0;
        int altRef = 0;
        int altOffset = 0;

        for (int i = 0; i < entries; i++) {
            int tag = bb.getShort() & 0xFFFF;
            int type = bb.getShort() & 0xFFFF;
            int count = bb.getInt();
            int value = bb.getInt();

            if (tag == 1) {
                latRef = charRef(bb, type, count, value);
            }
            if (tag == 2) {
                latOffset = value;
            }
            if (tag == 3) {
                lonRef = charRef(bb, type, count, value);
            }
            if (tag == 4) {
                lonOffset = value;
            }
            if (tag == 5) {
                altRef = value;
            }
            if (tag == 6) {
                altOffset = value;
            }
        }
        if (latOffset > 0 && lonOffset > 0) {
            d.lat = readRationalTriplet(bb, tiffBase + latOffset);
            d.lon = readRationalTriplet(bb, tiffBase + lonOffset);
            if (latRef == 'S') {
                d.lat = -d.lat;
            }
            if (lonRef == 'W') {
                d.lon = -d.lon;
            }
        }
        if (altOffset > 0) {
            double alt = readRational(bb, tiffBase + altOffset);
            if (altRef == 1) {
                alt = -alt;
            }
            d.alt = alt;
        }
    }

    private static double readRationalTriplet(ByteBuffer bb, int offset) {
        bb.position(offset);
        double[] v = new double[3];

        for (int i = 0; i < 3; i++) {
            long num = bb.getInt() & 0xFFFFFFFFL;
            long den = bb.getInt() & 0xFFFFFFFFL;
            v[i] = (double) num / den;
        }
        return v[0] + v[1] / 60.0 + v[2] / 3600.0;
    }

    private static double readRational(ByteBuffer bb, int offset) {
        bb.position(offset);
        long num = bb.getInt() & 0xFFFFFFFFL;
        long den = bb.getInt() & 0xFFFFFFFFL;
        return (double) num / den;
    }

    public static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream fis = new FileInputStream(file);
                DigestInputStream dis = new DigestInputStream(fis, digest)) {
            byte[] buffer = new byte[8192];
            while (dis.read(buffer) != -1) {
                // digest automatically updated from stream
            }
        }
        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }

    public static String sha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(data);
        return bytesToHex(hashBytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] insertExifIntoJpeg(byte[] jpegBytes, byte[] exifBytes) throws IOException {
        if (exifBytes == null || exifBytes.length == 0) {
            return jpegBytes;
        }

        if (jpegBytes.length < 2 || (jpegBytes[0] != (byte) 0xFF || jpegBytes[1] != (byte) 0xD8)) {
            throw new IOException("Invalid JPEG: missing SOI");
        }

        byte[] exifPayload = ensureExifHeader(exifBytes);
        int segmentLength = exifPayload.length + 2;
        if (segmentLength > 0xFFFF) {
            throw new IOException("EXIF too large for JPEG APP1 segment");
        }

        byte[] result = new byte[jpegBytes.length + 2 + 2 + exifPayload.length];
        int pos = 0;

        result[pos++] = jpegBytes[0];
        result[pos++] = jpegBytes[1];
        result[pos++] = (byte) 0xFF;
        result[pos++] = (byte) 0xE1;
        result[pos++] = (byte) ((segmentLength >> 8) & 0xFF);
        result[pos++] = (byte) (segmentLength & 0xFF);
        System.arraycopy(exifPayload, 0, result, pos, exifPayload.length);
        pos += exifPayload.length;
        System.arraycopy(jpegBytes, 2, result, pos, jpegBytes.length - 2);

        return result;
    }

    private static byte[] ensureExifHeader(byte[] exifBytes) {
        if (exifBytes.length >= 6 && exifBytes[0] == 0x45 && exifBytes[1] == 0x78
                && exifBytes[2] == 0x69 && exifBytes[3] == 0x66 && exifBytes[4] == 0x00
                && exifBytes[5] == 0x00) {
            return exifBytes;
        }

        byte[] prefixed = new byte[exifBytes.length + 6];
        prefixed[0] = 0x45;
        prefixed[1] = 0x78;
        prefixed[2] = 0x69;
        prefixed[3] = 0x66;
        prefixed[4] = 0x00;
        prefixed[5] = 0x00;
        System.arraycopy(exifBytes, 0, prefixed, 6, exifBytes.length);
        return prefixed;
    }

    private static int readIntBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24) | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
    }

    public static void printMetadataComparison(String label, Metadata pngMeta, Metadata jpgMeta) {
        Metadata effectivePngMeta = pngMeta;
        if (isMetadataEmpty(pngMeta)) {
            Metadata parsed = parsePngMetadata(pngMeta.filename);
            if (parsed != null) {
                effectivePngMeta = parsed;
            }
        }

        boolean dateSame = Objects.equals(effectivePngMeta.datetime, jpgMeta.datetime);
        boolean gpsFlagSame = effectivePngMeta.gps_flag == jpgMeta.gps_flag;
        boolean latSame = doubleEquals(effectivePngMeta.latitude, jpgMeta.latitude, 1e-6);
        boolean lonSame = doubleEquals(effectivePngMeta.longitude, jpgMeta.longitude, 1e-6);
        boolean altSame = doubleEquals(effectivePngMeta.altitude, jpgMeta.altitude, 1e-3);

        boolean metadataSame = dateSame && gpsFlagSame && latSame && lonSame && altSame;

        System.out.println("Metadata comparison for " + label + ": " + (metadataSame ? "MATCH" : "MISMATCH"));
        System.out.println("  datetime: png=" + effectivePngMeta.datetime + ", jpg=" + jpgMeta.datetime);
        System.out.println("  gps_flag: png=" + effectivePngMeta.gps_flag + ", jpg=" + jpgMeta.gps_flag);
        System.out.println("  latitude: png=" + effectivePngMeta.latitude + ", jpg=" + jpgMeta.latitude);
        System.out.println("  longitude: png=" + effectivePngMeta.longitude + ", jpg=" + jpgMeta.longitude);
        System.out.println("  altitude: png=" + effectivePngMeta.altitude + ", jpg=" + jpgMeta.altitude);
    }

    private static boolean isMetadataEmpty(Metadata meta) {
        if (meta == null) {
            return true;
        }
        boolean noDate = meta.datetime == null || meta.datetime.isEmpty();
        boolean noGps = !meta.gps_flag && meta.latitude == null && meta.longitude == null && meta.altitude == null;
        return noDate && noGps;
    }

    private static Metadata parsePngMetadata(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }

        File f = resolveFileByName(filename);
        if (f == null) {
            return null;
        }

        try {
            byte[] pngBytes = Files.readAllBytes(f.toPath());
            byte[] exifBytes = extractExifFromPng(pngBytes);
            if (exifBytes == null || exifBytes.length == 0) {
                return null;
            }

            byte[] normalizedExif = ensureExifHeader(exifBytes);
            ExifData d = parseExif(normalizedExif);
            Metadata meta = new Metadata();
            meta.filename = f.getName();
            meta.filesize = f.length();
            meta.datetime = d.date;

            if (d.lat != null && d.lon != null) {
                meta.latitude = d.lat;
                meta.longitude = -d.lon;
                meta.altitude = d.alt;
                meta.gps_flag = true;
            } else {
                meta.latitude = null;
                meta.longitude = null;
                meta.altitude = null;
                meta.gps_flag = false;
            }

            return meta;
        } catch (Exception e) {
            return null;
        }
    }

    private static File resolveFileByName(String filename) {
        File direct = new File(filename);
        if (direct.exists()) {
            return direct;
        }

        File testPath = new File("src/test/java/com/example/", filename);
        if (testPath.exists()) {
            return testPath;
        }

        File resourcesPath = new File("src/main/resources/", filename);
        if (resourcesPath.exists()) {
            return resourcesPath;
        }

        return null;
    }

    private static boolean doubleEquals(Double a, Double b, double eps) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Math.abs(a - b) <= eps;
    }

    public static void main(String[] args) throws Exception {
        String basePath = "src/test/java/com/example/";
        File png1 = new File(basePath + "test_image_1.png");
        File png2 = new File(basePath + "test_image_2.png");

        File png1Copy = new File(basePath + "test_image_1_copy.png");
        Files.copy(png1.toPath(), png1Copy.toPath());
        File jpg1 = convertPngToJpg(png1Copy);
        File jpg1Final = new File(basePath + "test_image_1.jpg");
        jpg1.renameTo(jpg1Final);
        png1Copy.delete();

        File png2Copy = new File(basePath + "test_image_2_copy.png");
        Files.copy(png2.toPath(), png2Copy.toPath());
        File jpg2 = convertPngToJpg(png2Copy);
        File jpg2Final = new File(basePath + "test_image_2.jpg");
        jpg2.renameTo(jpg2Final);
        png2Copy.delete();

        Metadata png1Meta = db.loadMetadata(png1);
        Metadata jpg1Meta = db.loadMetadata(jpg1Final);
        printMetadataComparison("test_image_1", png1Meta, jpg1Meta);

        Metadata png2Meta = db.loadMetadata(png2);
        Metadata jpg2Meta = db.loadMetadata(jpg2Final);
        printMetadataComparison("test_image_2", png2Meta, jpg2Meta);
    }
}
