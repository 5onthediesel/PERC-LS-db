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
        meta.filesize = f.length();
        meta.datetime = d.date;
        meta.latitude = d.lat;
        meta.longitude = -d.lon;
        meta.altitude = d.alt;
        meta.gps_flag = (d.lat != null && d.lon != null && d.alt != null);
        meta.sha256 = ImgHash.sha256(f);
        meta.width = ImgDet.getWidth(f);
        meta.height = ImgDet.getHeight(f);
        meta.cloud_uri = "";

        return meta;
    }

    static void setupSchema(Connection conn) throws SQLException {
        Statement s = conn.createStatement();
        s.execute("set search_path to cs370");
        s.execute("drop table if exists images");
        s.execute("""
            create table if not exists images (
                id serial primary key,
                img_hash varchar(64) unique,
                cloud_uri text not null,
                filename text,

                filesize_bytes bigint,
                width int,
                height int,

                gps_flag boolean,
                latitude double precision,
                longitude double precision,
                altitude double precision,
                datetime_taken timestamptz,
                datetime_uploaded timestamptz default now()
            )
        """);
    }
    
    static void insertMeta(Connection conn, Metadata meta) throws SQLException {
        String sql =
            "insert into images (" +
            "img_hash, filename, gps_flag, latitude, longitude, altitude, datetime_taken, " +
            "cloud_uri, width, height, filesize_bytes) " +
            "values (?, ?, ?, ?, ?, ?, to_timestamp(?, 'YYYY:MM:DD HH24:MI:SS'), " +
            "?, ?, ?, ?)";

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
        ps.setString(8, meta.cloud_uri);
        ps.setInt(9, meta.width);
        ps.setInt(10, meta.height);
        ps.setLong(11, meta.filesize);
        ps.executeUpdate();
    }

    static void shipImgs(Metadata meta, Connection conn, List<File> jpgFiles) throws Exception {
        for (File jpg : jpgFiles) {
            try {
                meta = loadMetadata(jpg);
                insertMeta(conn, meta);

            } catch (SQLException e) {
                if (e.getSQLState().equals("23505")) {
                    System.out.println("Duplicate image skipped: " + (meta != null ? meta.filename : jpg.getName()));
                } else {
                    throw e;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    static List<File> prepareImages(File folder) {
        List<File> jpgFiles = new ArrayList<>();

        for (File f : folder.listFiles()) {
            if (!f.isFile() || f.getName().startsWith(".")) continue;
            try {
                File fileToProcess = ImgDet.convertToJpg(f);
                jpgFiles.add(fileToProcess);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return jpgFiles;
    }

/* -------------------------------------------------------------------------- */

    public static void main(String[] args) throws Exception {
        File folder = new File("images");
        List<File> jpgs = prepareImages(folder);
        Metadata meta = new Metadata();

        try (Connection conn = connect()) {
            setupSchema(conn);
            shipImgs(meta, conn, jpgs);
        }
    }
}
