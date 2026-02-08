import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

public class ImgDet {

    public static File convertToJpg(File f) throws IOException, InterruptedException {
        String ext = getExtension(f.getName()).toLowerCase();
        if (ext.equals("jpg") || ext.equals("jpeg")) return f;

        File jpgFile = new File(f.getParent(), removeExtension(f.getName()) + ".jpg");

        if (ext.equals("heic") || ext.equals("heif")) {
            ProcessBuilder pb = new ProcessBuilder(
                "sips", "-s", "format", "jpeg", f.getAbsolutePath(), "--out", jpgFile.getAbsolutePath()
            );
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) throw new IOException("sips failed " + f.getName());

            if (!f.delete()) {
                System.err.println("Duplicate insertion HEIC" + f.getName());
            }

        } else {
            BufferedImage img = ImageIO.read(f);
            if (img == null) throw new IOException("Unsupported image format: " + f.getAbsolutePath());
            ImageIO.write(img, "jpg", jpgFile);
        }

        return jpgFile;
    }

    public static int getWidth(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IOException("Not an image: " + f);
        return img.getWidth();
    }

    public static int getHeight(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IOException("Not an image: " + f);
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
}
