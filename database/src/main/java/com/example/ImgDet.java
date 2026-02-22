package com.example;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Objects;

public class ImgDet {

    public static File convertToJpg(File f) throws IOException, InterruptedException {
        String ext = getExtension(f.getName()).toLowerCase();
        if (ext.equals("jpg") || ext.equals("jpeg"))
            return f;

        File jpgFile = new File(f.getParent(), removeExtension(f.getName()) + ".jpg");

        if (ext.equals("heic") || ext.equals("heif")) {
            ProcessBuilder pb = new ProcessBuilder(
                    "sips", "-s", "format", "jpeg", f.getAbsolutePath(), "--out", jpgFile.getAbsolutePath());
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0)
                throw new IOException("sips failed " + f.getName());

            if (!f.delete()) {
                System.err.println("Duplicate insertion HEIC" + f.getName());
            }

        } else {
            BufferedImage img = ImageIO.read(f);
            if (img == null)
                throw new IOException("Unsupported image format: " + f.getAbsolutePath());
            ImageIO.write(img, "jpg", jpgFile);
        }

        return jpgFile;
    }

    public static int getWidth(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null)
            throw new IOException("Not an image: " + f);
        return img.getWidth();
    }

    public static int getHeight(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null)
            throw new IOException("Not an image: " + f);
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
        if (ext.equals("jpg") || ext.equals("jpeg"))
            return f;
        if (!ext.equals("png"))
            throw new IOException("Unsupported image format: " + f.getAbsolutePath());

        File jpgFile = new File(f.getParent(), removeExtension(f.getName()) + ".jpg");

        byte[] pngBytes = Files.readAllBytes(f.toPath());
        byte[] exif = extractExifFromPng(pngBytes);

        BufferedImage img = ImageIO.read(f);
        if (img == null)
            throw new IOException("Unsupported image format: " + f.getAbsolutePath());

        BufferedImage rgb = img;
        if (img.getColorModel().hasAlpha()) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", baos);
        byte[] jpegBytes = baos.toByteArray();

        jpegBytes = insertExifIntoJpeg(jpegBytes, exif);

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(jpgFile))) {
            out.write(jpegBytes);
        }

        return jpgFile;
    }

    private static byte[] extractExifFromPng(byte[] pngBytes) throws IOException {
        if (pngBytes.length < 8)
            throw new IOException("Invalid PNG: too short");

        byte[] sig = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        for (int i = 0; i < sig.length; i++) {
            if (pngBytes[i] != sig[i])
                throw new IOException("Invalid PNG signature");
        }

        int offset = 8;
        while (offset + 12 <= pngBytes.length) {
            int length = readIntBE(pngBytes, offset);
            offset += 4;
            if (offset + 4 > pngBytes.length)
                break;

            String type = new String(pngBytes, offset, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            offset += 4;

            if (length < 0 || offset + length + 4 > pngBytes.length)
                break;

            if (type.equals("eXIf")) {
                byte[] exif = new byte[length];
                System.arraycopy(pngBytes, offset, exif, 0, length);
                return exif;
            }

            offset += length + 4; // data + CRC
        }

        return null;
    }

    public static EXIFParser.ExifData parseExifFromPng(File f) throws IOException {
        byte[] pngBytes = Files.readAllBytes(f.toPath());
        byte[] exifBytes = extractExifFromPng(pngBytes);
        if (exifBytes == null || exifBytes.length == 0)
            return null;

        byte[] normalizedExif = ensureExifHeader(exifBytes);
        return EXIFParser.parseExif(normalizedExif);
    }

    private static byte[] insertExifIntoJpeg(byte[] jpegBytes, byte[] exifBytes) throws IOException {
        if (exifBytes == null || exifBytes.length == 0)
            return jpegBytes;

        if (jpegBytes.length < 2 || (jpegBytes[0] != (byte) 0xFF || jpegBytes[1] != (byte) 0xD8))
            throw new IOException("Invalid JPEG: missing SOI");

        byte[] exifPayload = ensureExifHeader(exifBytes);
        int segmentLength = exifPayload.length + 2;
        if (segmentLength > 0xFFFF)
            throw new IOException("EXIF too large for JPEG APP1 segment");

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
            if (parsed != null)
                effectivePngMeta = parsed;
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
        if (meta == null)
            return true;
        boolean noDate = meta.datetime == null || meta.datetime.isEmpty();
        boolean noGps = !meta.gps_flag && meta.latitude == null && meta.longitude == null && meta.altitude == null;
        return noDate && noGps;
    }

    private static Metadata parsePngMetadata(String filename) {
        if (filename == null || filename.isEmpty())
            return null;

        File f = resolveFileByName(filename);
        if (f == null)
            return null;

        try {
            byte[] pngBytes = Files.readAllBytes(f.toPath());
            byte[] exifBytes = extractExifFromPng(pngBytes);
            if (exifBytes == null || exifBytes.length == 0)
                return null;

            byte[] normalizedExif = ensureExifHeader(exifBytes);
            EXIFParser.ExifData d = EXIFParser.parseExif(normalizedExif);
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
        if (direct.exists())
            return direct;

        File testPath = new File("src/test/java/com/example/", filename);
        if (testPath.exists())
            return testPath;

        File resourcesPath = new File("src/main/resources/", filename);
        if (resourcesPath.exists())
            return resourcesPath;

        return null;
    }

    private static boolean doubleEquals(Double a, Double b, double eps) {
        if (a == null || b == null)
            return a == null && b == null;
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
