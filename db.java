import java.sql.*;
import java.io.*;

class db {

    public static void main(String args[]) throws IOException
    {
        String url;
        Connection conn;
        PreparedStatement pStatement;
        ResultSet rs;
        String queryString;

        try {
            Class.forName("org.postgresql.Driver");
        }
        catch (ClassNotFoundException e) {
            System.out.println("Failed to find the JDBC driver");
        }

        try
        {
            url = "jdbc:postgresql://localhost:5432/postgres";
            conn = DriverManager.getConnection(url, "postgres", "rubiks");

            queryString = "drop table if exists cs370";
            pStatement = conn.prepareStatement(queryString);
            int result = pStatement.executeUpdate();

            queryString = "create table cs370 (latitude char(40), longitude char(40), url char(40))";
            pStatement = conn.prepareStatement(queryString);
            result = pStatement.executeUpdate();


        }
        catch (SQLException se)
        {
            System.err.println("SQL Exception." +
                    "<Message>: " + se.getMessage());
        }

    }

}
