package com.irina.vkbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Db {
  private final String dbPath;
  private Connection conn;
  private final Gson gson = new Gson();

  public Db(String dbPath) {
    this.dbPath = dbPath;
  }

  public synchronized void init() {
    try {
      conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
      conn.setAutoCommit(true);
      try (Statement st = conn.createStatement()) {
        st.execute("PRAGMA journal_mode=WAL");
        st.execute("PRAGMA synchronous=NORMAL");
        st.execute("CREATE TABLE IF NOT EXISTS users (" +
          "user_id INTEGER PRIMARY KEY," +
          "first_seen INTEGER," +
          "last_seen INTEGER," +
          "is_subscribed INTEGER DEFAULT 0," +
          "is_admin INTEGER DEFAULT 0," +
          "pending_ref TEXT" +
          ")");

        st.execute("CREATE TABLE IF NOT EXISTS magnets (" +
          "id INTEGER PRIMARY KEY AUTOINCREMENT," +
          "title TEXT NOT NULL," +
          "description TEXT," +
          "type TEXT NOT NULL," +
          "attachment TEXT," +
          "url TEXT," +
          "ref_code TEXT UNIQUE," +
          "is_active INTEGER DEFAULT 1," +
          "created_at INTEGER," +
          "updated_at INTEGER" +
          ")");

        st.execute("CREATE TABLE IF NOT EXISTS events (" +
          "id INTEGER PRIMARY KEY AUTOINCREMENT," +
          "user_id INTEGER NOT NULL," +
          "event_type TEXT NOT NULL," +
          "magnet_id INTEGER," +
          "ts INTEGER" +
          ")");

        st.execute("CREATE TABLE IF NOT EXISTS admin_states (" +
          "user_id INTEGER PRIMARY KEY," +
          "state TEXT," +
          "data TEXT," +
          "updated_at INTEGER" +
          ")");
      }
    } catch (SQLException e) {
      throw new RuntimeException("DB init failed", e);
    }
  }

  public synchronized void upsertUser(int userId, boolean isAdmin) {
    long now = Instant.now().getEpochSecond();
    try (PreparedStatement ps = conn.prepareStatement(
      "INSERT INTO users(user_id, first_seen, last_seen, is_admin) VALUES(?,?,?,?) " +
        "ON CONFLICT(user_id) DO UPDATE SET last_seen=excluded.last_seen, is_admin=excluded.is_admin")) {
      ps.setInt(1, userId);
      ps.setLong(2, now);
      ps.setLong(3, now);
      ps.setInt(4, isAdmin ? 1 : 0);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized User getUser(int userId) {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT user_id, first_seen, last_seen, is_subscribed, is_admin, pending_ref FROM users WHERE user_id=?")) {
      ps.setInt(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        User u = new User();
        u.userId = rs.getInt("user_id");
        u.firstSeen = rs.getLong("first_seen");
        u.lastSeen = rs.getLong("last_seen");
        u.isSubscribed = rs.getInt("is_subscribed") == 1;
        u.isAdmin = rs.getInt("is_admin") == 1;
        u.pendingRef = rs.getString("pending_ref");
        return u;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void setSubscribed(int userId, boolean subscribed) {
    try (PreparedStatement ps = conn.prepareStatement(
      "UPDATE users SET is_subscribed=? WHERE user_id=?")) {
      ps.setInt(1, subscribed ? 1 : 0);
      ps.setInt(2, userId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void setPendingRef(int userId, String ref) {
    try (PreparedStatement ps = conn.prepareStatement(
      "UPDATE users SET pending_ref=? WHERE user_id=?")) {
      ps.setString(1, ref);
      ps.setInt(2, userId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void logEvent(int userId, String type, Integer magnetId) {
    long now = Instant.now().getEpochSecond();
    try (PreparedStatement ps = conn.prepareStatement(
      "INSERT INTO events(user_id, event_type, magnet_id, ts) VALUES(?,?,?,?)")) {
      ps.setInt(1, userId);
      ps.setString(2, type);
      if (magnetId == null) {
        ps.setNull(3, Types.INTEGER);
      } else {
        ps.setInt(3, magnetId);
      }
      ps.setLong(4, now);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized int createMagnet(Magnet m) {
    long now = Instant.now().getEpochSecond();
    try (PreparedStatement ps = conn.prepareStatement(
      "INSERT INTO magnets(title, description, type, attachment, url, ref_code, is_active, created_at, updated_at) " +
        "VALUES(?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, m.title);
      ps.setString(2, m.description);
      ps.setString(3, m.type);
      ps.setString(4, m.attachment);
      ps.setString(5, m.url);
      ps.setString(6, m.refCode);
      ps.setInt(7, m.isActive ? 1 : 0);
      ps.setLong(8, now);
      ps.setLong(9, now);
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (rs.next()) {
          return rs.getInt(1);
        }
      }
      throw new RuntimeException("Failed to get magnet id");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void updateMagnet(Magnet m) {
    long now = Instant.now().getEpochSecond();
    try (PreparedStatement ps = conn.prepareStatement(
      "UPDATE magnets SET title=?, description=?, type=?, attachment=?, url=?, ref_code=?, is_active=?, updated_at=? WHERE id=?")) {
      ps.setString(1, m.title);
      ps.setString(2, m.description);
      ps.setString(3, m.type);
      ps.setString(4, m.attachment);
      ps.setString(5, m.url);
      ps.setString(6, m.refCode);
      ps.setInt(7, m.isActive ? 1 : 0);
      ps.setLong(8, now);
      ps.setInt(9, m.id);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void deleteMagnet(int id) {
    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM magnets WHERE id=?")) {
      ps.setInt(1, id);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized Magnet getMagnetById(int id) {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT id, title, description, type, attachment, url, ref_code, is_active FROM magnets WHERE id=?")) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return readMagnet(rs);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized Magnet getMagnetByRef(String ref) {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT id, title, description, type, attachment, url, ref_code, is_active FROM magnets WHERE ref_code=?")) {
      ps.setString(1, ref);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return readMagnet(rs);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized List<Magnet> listMagnets(boolean onlyActive, int offset, int limit) {
    String sql = "SELECT id, title, description, type, attachment, url, ref_code, is_active FROM magnets" +
      (onlyActive ? " WHERE is_active=1" : "") + " ORDER BY id DESC LIMIT ? OFFSET ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setInt(1, limit);
      ps.setInt(2, offset);
      try (ResultSet rs = ps.executeQuery()) {
        List<Magnet> list = new ArrayList<>();
        while (rs.next()) {
          list.add(readMagnet(rs));
        }
        return list;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized int countMagnets(boolean onlyActive) {
    String sql = "SELECT COUNT(*) FROM magnets" + (onlyActive ? " WHERE is_active=1" : "");
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getInt(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private Magnet readMagnet(ResultSet rs) throws SQLException {
    Magnet m = new Magnet();
    m.id = rs.getInt("id");
    m.title = rs.getString("title");
    m.description = rs.getString("description");
    m.type = rs.getString("type");
    m.attachment = rs.getString("attachment");
    m.url = rs.getString("url");
    m.refCode = rs.getString("ref_code");
    m.isActive = rs.getInt("is_active") == 1;
    return m;
  }

  public synchronized AdminState getAdminState(int userId) {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT state, data FROM admin_states WHERE user_id=?")) {
      ps.setInt(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        AdminState st = new AdminState();
        st.userId = userId;
        st.state = rs.getString("state");
        st.data = rs.getString("data");
        return st;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void setAdminState(int userId, String state, JsonObject data) {
    long now = Instant.now().getEpochSecond();
    String json = data == null ? null : gson.toJson(data);
    try (PreparedStatement ps = conn.prepareStatement(
      "INSERT INTO admin_states(user_id, state, data, updated_at) VALUES(?,?,?,?) " +
        "ON CONFLICT(user_id) DO UPDATE SET state=excluded.state, data=excluded.data, updated_at=excluded.updated_at")) {
      ps.setInt(1, userId);
      ps.setString(2, state);
      ps.setString(3, json);
      ps.setLong(4, now);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized void clearAdminState(int userId) {
    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM admin_states WHERE user_id=?")) {
      ps.setInt(1, userId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized List<Integer> listAllUsers() {
    try (PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM users")) {
      try (ResultSet rs = ps.executeQuery()) {
        List<Integer> list = new ArrayList<>();
        while (rs.next()) {
          list.add(rs.getInt(1));
        }
        return list;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized Stats getStats() {
    Stats stats = new Stats();
    stats.startsTotal = countByType("start");
    stats.startsUnique = countDistinctByType("start");
    stats.subscribedUnique = countDistinctByType("subscribed");
    return stats;
  }

  private int countByType(String type) {
    try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM events WHERE event_type=?")) {
      ps.setString(1, type);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getInt(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private int countDistinctByType(String type) {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT COUNT(DISTINCT user_id) FROM events WHERE event_type=?")) {
      ps.setString(1, type);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getInt(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized List<MagnetStat> getMagnetStats() {
    String sql = "SELECT m.id, m.title, COUNT(e.id) AS downloads " +
      "FROM magnets m LEFT JOIN events e ON e.magnet_id=m.id AND e.event_type='magnet_sent' " +
      "GROUP BY m.id, m.title ORDER BY downloads DESC";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      try (ResultSet rs = ps.executeQuery()) {
        List<MagnetStat> list = new ArrayList<>();
        while (rs.next()) {
          MagnetStat ms = new MagnetStat();
          ms.id = rs.getInt(1);
          ms.title = rs.getString(2);
          ms.downloads = rs.getInt(3);
          list.add(ms);
        }
        return list;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
