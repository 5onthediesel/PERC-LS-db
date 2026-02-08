public class Metadata {
    public String filename;      // original filename
    public String datetime;      // ex 2026:02:07 21:18:06
    public Double latitude;      // nullable
    public Double longitude;     // nullable
    public Double altitude;      // nullable
    public String cameraMake;    // optional
    public String cameraModel;   // optional
    public String sha256;        // computed hash
    public boolean gps_flag;     // whether data is available
}
