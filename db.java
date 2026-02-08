import java.sql.*;
import java.io.*;
import java.io.File;
import java.util.*;

class db {

    static Connection connect() throws SQLException {
        String url = "jdbc:postgresql://localhost:5432/postgres";
        return DriverManager.getConnection(url, "postgres", "rubiks");
    }

    static Metadata loadMetadata(File f) throws Exception {
        Metadata meta = new Metadata();
        EXIFParser.ExifData d = EXIFParser.parse(f.getAbsolutePath());

        meta.filename = f.getName();
        meta.datetime = d.date;
        meta.latitude = d.lat;
        meta.longitude = d.lon;
        meta.altitude = d.alt;
        meta.gps_flag = (d.lat != null && d.lon != null && d.alt != null);
        meta.sha256 = "someHashingAlgo";

        return meta;
    }

    static void setupSchema(Connection conn) throws SQLException {
        Statement s = conn.createStatement();
        s.execute("set search_path to cs370");
        s.execute("drop table if exists images");
        s.execute("""
            create table images (
                img_hash char(64),
                filename text,
                gps_flag boolean,
                latitude double precision,
                longitude double precision,
                altitude double precision,
                datetime_taken timestamp
            )
        """);
    }

    static void insertMeta(Connection conn, Metadata meta) throws SQLException {
        String sql =
            "insert into images (img_hash, filename, gps_flag, latitude, longitude, altitude, datetime_taken) " +
            "values (?, ?, ?, ?, ?, ?, to_timestamp(?, 'YYYY:MM:DD HH24:MI:SS'))";

        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setString(1, meta.sha256);
        ps.setString(2, meta.filename);
        ps.setBoolean(3, meta.gps_flag);

        if (meta.gps_flag) {
            ps.setDouble(4, meta.latitude);
            ps.setDouble(5, meta.longitude);
            ps.setDouble(6, meta.altitude);
        } else {
            ps.setNull(4, Types.DOUBLE);
            ps.setNull(5, Types.DOUBLE);
            ps.setNull(6, Types.DOUBLE);
        }

        ps.setString(7, meta.datetime);
        ps.executeUpdate();
    }

    static List<File> prepareImages(File folder) {
        List<File> jpgFiles = new ArrayList<>();

        for (File f : folder.listFiles()) {
            if (!f.isFile() || f.getName().startsWith(".")) continue;
            try {
                File fileToProcess = ImgDet.convertToJpg(f);
                jpgFiles.add(fileToProcess);
            } catch (Exception e) {
                System.err.println("Failed to process file: " + f.getName());
                e.printStackTrace();
            }
        }
        return jpgFiles;
    }

    public static void main(String[] args) throws Exception {
        File folder = new File("images");
        List<File> jpgFiles = prepareImages(folder);

        try (Connection conn = connect()) {
            setupSchema(conn);

            for (File jpg : jpgFiles) {
                try {
                    Metadata meta = loadMetadata(jpg);
                    insertMeta(conn, meta);
                } catch (Exception e) {
                    System.err.println("Failed to insert metadata for: " + jpg.getName());
                    e.printStackTrace();
                }
            }
        }
    }
}
