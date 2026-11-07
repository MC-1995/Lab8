package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;

public class Main {

    // Параметры подключения можно переопределить переменными окружения.
    private static final String URL  = System.getenv()
            .getOrDefault("DB_URL",  "jdbc:postgresql://localhost:5432/postgres");
    private static final String USER = System.getenv()
            .getOrDefault("DB_USER", "postgres");
    private static final String PASS = System.getenv()
            .getOrDefault("DB_PASS", "12345");

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
            prepareMusic(conn);

            task1(conn);
            task2(conn);
            task3(conn);
            task4(conn, Path.of("books.json"));
            task5(conn);
            task6(conn);
            task7(conn);
            task8(conn);
        }
    }


    private static void prepareMusic(Connection conn) throws Exception {
        executeSqlScript(conn, Path.of("music-create.sql"));
    }

    private static void executeSqlScript(Connection conn, Path path) throws Exception {
        String raw = Files.readString(path);
        StringBuilder cleaned = new StringBuilder();

        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("--")) continue;
            cleaned.append(line).append('\n');
        }

        for (String stmtText : cleaned.toString().split(";")) {
            if (stmtText.isBlank()) continue;
            try (Statement st = conn.createStatement()) {
                st.execute(stmtText);
            }
        }
    }

    // ---------- Задания ----------

    // №1. Все композиции
    private static void task1(Connection conn) throws SQLException {
        System.out.println("\n=== №1. Все композиции ===");

        String sql = "SELECT id, name FROM study.music ORDER BY id";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("%d: %s%n", rs.getInt("id"), rs.getString("name"));
            }
        }
    }

    // №2. Названия без букв m и t
    private static void task2(Connection conn) throws SQLException {
        System.out.println("\n=== №2. Композиции без букв m и t ===");

        String sql = """
                SELECT id, name
                FROM study.music
                WHERE LOWER(name) NOT LIKE '%m%'
                  AND LOWER(name) NOT LIKE '%t%'
                ORDER BY id
                """;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("%d: %s%n", rs.getInt("id"), rs.getString("name"));
            }
        }
    }

    // №3. Добавление любимой композиции
    private static void task3(Connection conn) throws SQLException {
        System.out.println("\n=== №3. Добавление любимой композиции ===");

        String sql = "INSERT INTO study.music (name) VALUES (?) RETURNING id";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "Moscow Calling");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.println("Добавлена запись с id = " + rs.getInt(1));
                }
            }
        }
    }

    // №4. Загрузка данных из JSON
    private static void task4(Connection conn, Path jsonPath) throws Exception {
        System.out.println("\n=== №4. Загрузка данных из JSON ===");

        createBookTables(conn);

        ObjectMapper mapper = new ObjectMapper();
        List<VisitorDto> visitors = mapper.readValue(
                jsonPath.toFile(),
                new TypeReference<List<VisitorDto>>() {}
        );

        for (VisitorDto visitor : visitors) {
            if (visitor.name == null || visitor.name.isBlank()) continue;

            int visitorId = upsertVisitor(conn, visitor.name);
            if (visitor.books == null) continue;

            for (BookDto book : visitor.books) {
                if (book.title == null || book.title.isBlank() || book.year == null) continue;

                int bookId = upsertBook(conn, book.title, book.year);
                linkVisitorBook(conn, visitorId, bookId);
            }
        }

        System.out.println("Данные из JSON загружены.");
    }

    private static void createBookTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS visitors (
                        id   SERIAL PRIMARY KEY,
                        name VARCHAR(255) NOT NULL UNIQUE
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS books (
                        id    SERIAL PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        year  INT NOT NULL,
                        CONSTRAINT books_title_year_uniq UNIQUE (title, year)
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS visitor_books (
                        visitor_id INT NOT NULL REFERENCES visitors(id) ON DELETE CASCADE,
                        book_id    INT NOT NULL REFERENCES books(id)    ON DELETE CASCADE,
                        PRIMARY KEY (visitor_id, book_id)
                    )
                    """);
        }
    }

    private static int upsertVisitor(Connection conn, String name) throws SQLException {
        // PostgreSQL-стиль upsert через ON CONFLICT ... RETURNING id.
        String sql = """
                INSERT INTO visitors (name)
                VALUES (?)
                ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int upsertBook(Connection conn, String title, int year) throws SQLException {
        String sql = """
                INSERT INTO books (title, year)
                VALUES (?, ?)
                ON CONFLICT (title, year) DO UPDATE SET title = EXCLUDED.title
                RETURNING id
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void linkVisitorBook(Connection conn, int visitorId, int bookId)
            throws SQLException {

        String sql = """
                INSERT INTO visitor_books (visitor_id, book_id)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, visitorId);
            ps.setInt(2, bookId);
            ps.executeUpdate();
        }
    }

    // №5. Книги, отсортированные по году
    private static void task5(Connection conn) throws SQLException {
        System.out.println("\n=== №5. Книги, отсортированные по году ===");

        String sql = "SELECT title, year FROM books ORDER BY year, title";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("%s — %d%n", rs.getString("title"), rs.getInt("year"));
            }
        }
    }

    // №6. Книги до 2000 года
    private static void task6(Connection conn) throws SQLException {
        System.out.println("\n=== №6. Книги до 2000 года ===");

        String sql = "SELECT title, year FROM books WHERE year < 2000 ORDER BY year, title";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("%s — %d%n", rs.getString("title"), rs.getInt("year"));
            }
        }
    }

    // №7. Добавление себя и любимых книг
    private static void task7(Connection conn) throws SQLException {
        System.out.println("\n=== №7. Добавление себя и любимых книг ===");

        String myName = "Потанин Филипп Андреевич";

        int visitorId = upsertVisitor(conn, myName);
        int book1 = upsertBook(conn, "С Земли на Луну", 2008);
        int book2 = upsertBook(conn, "20000 лье под водой", 2018);

        linkVisitorBook(conn, visitorId, book1);
        linkVisitorBook(conn, visitorId, book2);

        String sql = """
                SELECT v.name, b.title, b.year
                FROM visitors v
                JOIN visitor_books vb ON vb.visitor_id = v.id
                JOIN books b          ON b.id = vb.book_id
                WHERE v.name = ?
                ORDER BY b.year
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, myName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf("%s: %s — %d%n",
                            rs.getString("name"),
                            rs.getString("title"),
                            rs.getInt("year"));
                }
            }
        }
    }

    // №8. Удаление таблиц
    private static void task8(Connection conn) throws SQLException {
        System.out.println("\n=== №8. Удаление таблиц ===");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS visitor_books");
            stmt.execute("DROP TABLE IF EXISTS books");
            stmt.execute("DROP TABLE IF EXISTS visitors");
        }

        System.out.println("Таблицы visitors, books, visitor_books удалены.");
    }

    // ---------- DTO ----------

    public static class VisitorDto {
        public String name;
        public List<BookDto> books;
    }

    public static class BookDto {
        public String title;
        public Integer year;
    }
}