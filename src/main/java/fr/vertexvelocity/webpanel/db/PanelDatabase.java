package fr.vertexvelocity.webpanel.db;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class PanelDatabase implements AutoCloseable {
    private final String jdbcUrl;
    private Connection connection;
    private final SecureRandom random = new SecureRandom();
    public PanelDatabase(Path file) { this.jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath(); }
    public synchronized void initialize() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver is missing from the plugin JAR; check the Maven Shade configuration.", e);
        }
        connection = DriverManager.getConnection(jdbcUrl);
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, salt TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL, created_at INTEGER NOT NULL, disabled INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS user_totp(username TEXT PRIMARY KEY, secret TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, event_type TEXT NOT NULL, uuid TEXT, username TEXT, server_name TEXT, details TEXT, created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_events_created ON events(created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS metrics(id INTEGER PRIMARY KEY AUTOINCREMENT, online_players INTEGER NOT NULL, backend_count INTEGER NOT NULL, uptime_seconds INTEGER NOT NULL, sampled_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_metrics_sampled ON metrics(sampled_at)");
            s.execute("CREATE TABLE IF NOT EXISTS backend_metrics(id INTEGER PRIMARY KEY AUTOINCREMENT, server_name TEXT NOT NULL, status TEXT NOT NULL, latency_ms INTEGER NOT NULL, sampled_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_backend_metrics_server_time ON backend_metrics(server_name,sampled_at)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_events_player_time ON events(uuid,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT, action TEXT NOT NULL, target TEXT, ip TEXT, details TEXT, created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS api_keys(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, key_hash TEXT NOT NULL UNIQUE, created_by TEXT NOT NULL, created_at INTEGER NOT NULL, last_used INTEGER, revoked INTEGER NOT NULL DEFAULT 0)");
        }
    }
    public synchronized int userCount() throws SQLException { try (Statement s=connection.createStatement(); ResultSet r=s.executeQuery("SELECT COUNT(*) FROM users")) { return r.next()?r.getInt(1):0; } }
    public synchronized String createSetupToken() throws Exception {
        String token = randomToken(24); String hash = sha256(token);
        try (PreparedStatement p=connection.prepareStatement("INSERT OR REPLACE INTO settings(key,value) VALUES('setup_token_hash',?)")) { p.setString(1,hash); p.executeUpdate(); }
        return token;
    }
    public synchronized boolean setupOwner(String token,String username,String salt,String passwordHash) throws SQLException {
        if (userCount()!=0 || username==null || !username.matches("[A-Za-z0-9_.-]{3,32}")) return false;
        try (PreparedStatement p=connection.prepareStatement("SELECT value FROM settings WHERE key='setup_token_hash'"); ResultSet r=p.executeQuery()) {
            if (!r.next() || !constantEquals(r.getString(1),sha256(token==null?"":token))) return false;
        } catch (Exception e) { return false; }
        try (PreparedStatement p=connection.prepareStatement("INSERT INTO users(username,salt,password_hash,role,created_at) VALUES(?,?,?,'OWNER',?)")) {
            p.setString(1,username); p.setString(2,salt); p.setString(3,passwordHash); p.setLong(4,Instant.now().getEpochSecond()); p.executeUpdate();
        } catch (SQLException e) { return false; }
        try (PreparedStatement p=connection.prepareStatement("DELETE FROM settings WHERE key='setup_token_hash'")) { p.executeUpdate(); }
        audit(username,"OWNER_CREATED",username,null,"Initial owner account created"); return true;
    }
    public synchronized Optional<UserRecord> findUser(String username) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT username,salt,password_hash,role,disabled FROM users WHERE username=?")) {
            p.setString(1,username); try(ResultSet r=p.executeQuery()) { if (!r.next()) return Optional.empty(); return Optional.of(new UserRecord(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5)!=0)); }
        }
    }
    public synchronized String totpSecret(String username) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("SELECT secret FROM user_totp WHERE username=? AND enabled=1")){p.setString(1,username);try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    public synchronized String pendingTotpSecret(String username) throws SQLException {try(PreparedStatement p=connection.prepareStatement("SELECT secret FROM user_totp WHERE username=? AND enabled=0")){p.setString(1,username);try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}}}
    public synchronized boolean totpEnabled(String username) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("SELECT enabled FROM user_totp WHERE username=?")){p.setString(1,username);try(ResultSet r=p.executeQuery()){return r.next()&&r.getInt(1)==1;}}
    }
    public synchronized String createPendingTotp(String username,String secret) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("INSERT INTO user_totp(username,secret,enabled,updated_at) VALUES(?,?,0,?) ON CONFLICT(username) DO UPDATE SET secret=excluded.secret,enabled=0,updated_at=excluded.updated_at")){p.setString(1,username);p.setString(2,secret);p.setLong(3,Instant.now().getEpochSecond());p.executeUpdate();} return secret;
    }
    public synchronized void enableTotp(String username) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("UPDATE user_totp SET enabled=1,updated_at=? WHERE username=?")){p.setLong(1,Instant.now().getEpochSecond());p.setString(2,username);if(p.executeUpdate()!=1)throw new SQLException("TOTP setup was not found");}
    }
    public synchronized void disableTotp(String username) throws SQLException {try(PreparedStatement p=connection.prepareStatement("DELETE FROM user_totp WHERE username=?")){p.setString(1,username);p.executeUpdate();}}
    public synchronized List<Map<String,Object>> users() throws SQLException {
        List<Map<String,Object>> out=new ArrayList<>(); try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT username,role,created_at,disabled FROM users ORDER BY username")) { while(r.next()){ Map<String,Object> m=new LinkedHashMap<>();m.put("username",r.getString(1));m.put("role",r.getString(2));m.put("createdAt",r.getLong(3));m.put("disabled",r.getInt(4)!=0);out.add(m);} } return out;
    }
    public synchronized void recordPlayerEvent(String type,String uuid,String username,String server,String details,Instant at) {
        if(connection==null)return; try(PreparedStatement p=connection.prepareStatement("INSERT INTO events(event_type,uuid,username,server_name,details,created_at) VALUES(?,?,?,?,?,?)")) { p.setString(1,type);p.setString(2,uuid);p.setString(3,username);p.setString(4,server);p.setString(5,details);p.setLong(6,at.getEpochSecond());p.executeUpdate(); } catch(SQLException ignored){}
    }
    public synchronized void audit(String actor,String action,String target,String ip,String details) {
        if(connection==null)return; try(PreparedStatement p=connection.prepareStatement("INSERT INTO audit(actor,action,target,ip,details,created_at) VALUES(?,?,?,?,?,?)")) { p.setString(1,actor);p.setString(2,action);p.setString(3,target);p.setString(4,ip);p.setString(5,details);p.setLong(6,Instant.now().getEpochSecond());p.executeUpdate(); } catch(SQLException ignored){}
    }
    public synchronized void sampleMetric(int players,int backends,long uptime) { try(PreparedStatement p=connection.prepareStatement("INSERT INTO metrics(online_players,backend_count,uptime_seconds,sampled_at) VALUES(?,?,?,?)")){p.setInt(1,players);p.setInt(2,backends);p.setLong(3,uptime);p.setLong(4,Instant.now().getEpochSecond());p.executeUpdate();}catch(SQLException ignored){} }
    public synchronized List<Map<String,Object>> events(int limit) throws SQLException {
        List<Map<String,Object>> out=new ArrayList<>(); try(PreparedStatement p=connection.prepareStatement("SELECT event_type,uuid,username,server_name,details,created_at FROM events ORDER BY id DESC LIMIT ?")){p.setInt(1,Math.max(1,Math.min(500,limit)));try(ResultSet r=p.executeQuery()){while(r.next()){Map<String,Object>m=new LinkedHashMap<>();m.put("type",r.getString(1));m.put("uuid",r.getString(2));m.put("username",r.getString(3));m.put("server",r.getString(4));m.put("details",r.getString(5));m.put("timestamp",r.getLong(6));out.add(m);}}}return out;
    }
    public synchronized List<Map<String,Object>> metrics(int limit) throws SQLException {
        List<Map<String,Object>> out=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT online_players,backend_count,uptime_seconds,sampled_at FROM metrics ORDER BY id DESC LIMIT ?")){p.setInt(1,Math.max(1,Math.min(2000,limit)));try(ResultSet r=p.executeQuery()){while(r.next()){Map<String,Object>m=new LinkedHashMap<>();m.put("players",r.getInt(1));m.put("backends",r.getInt(2));m.put("uptimeSeconds",r.getLong(3));m.put("timestamp",r.getLong(4));out.add(m);}}}Collections.reverse(out);return out;
    }
    public synchronized void sampleBackendMetric(String server, String status, long latencyMs, long sampledAt) {
        if(connection==null)return;
        try(PreparedStatement p=connection.prepareStatement("INSERT INTO backend_metrics(server_name,status,latency_ms,sampled_at) VALUES(?,?,?,?)")){
            p.setString(1,server);p.setString(2,status);p.setLong(3,latencyMs);p.setLong(4,sampledAt);p.executeUpdate();
        }catch(SQLException ignored){}
    }
    public synchronized List<Map<String,Object>> playerDirectory(String search,int limit) throws SQLException {
        String q=search==null?"":search.trim().toLowerCase(Locale.ROOT); int safeLimit=Math.max(1,Math.min(500,limit));
        List<Map<String,Object>> out=new ArrayList<>();
        String sql="SELECT uuid, MAX(username) username, COUNT(CASE WHEN event_type='LOGIN' THEN 1 END) logins, MIN(created_at) first_seen, MAX(created_at) last_seen, COUNT(CASE WHEN event_type='SERVER_SWITCH' THEN 1 END) transfers, COUNT(CASE WHEN event_type LIKE '%ERROR%' OR event_type LIKE '%FAILED%' THEN 1 END) errors FROM events WHERE uuid IS NOT NULL AND (?='' OR lower(username) LIKE ? OR lower(uuid) LIKE ?) GROUP BY uuid ORDER BY last_seen DESC LIMIT ?";
        try(PreparedStatement p=connection.prepareStatement(sql)){p.setString(1,q);p.setString(2,"%"+q+"%");p.setString(3,"%"+q+"%");p.setInt(4,safeLimit);try(ResultSet r=p.executeQuery()){while(r.next()){Map<String,Object> m=new LinkedHashMap<>();m.put("uuid",r.getString("uuid"));m.put("username",r.getString("username"));m.put("loginCount",r.getLong("logins"));m.put("firstSeen",r.getLong("first_seen"));m.put("lastSeen",r.getLong("last_seen"));long spanDays=Math.max(1,(r.getLong("last_seen")-r.getLong("first_seen"))/86400L+1);m.put("loginsPerWeek",Math.round(r.getLong("logins")*7.0/spanDays*100.0)/100.0);m.put("transferCount",r.getLong("transfers"));m.put("errorCount",r.getLong("errors"));m.put("sessionCount",r.getLong("logins"));out.add(m);}}}
        for(Map<String,Object> player:out){Map<String,Object> profile=playerProfile(String.valueOf(player.get("uuid")),50);player.put("username",profile.get("username"));player.put("totalPlaySeconds",profile.get("totalPlaySeconds"));player.put("averageSessionSeconds",profile.get("averageSessionSeconds"));player.put("serversVisited",profile.get("serversVisited"));}
        return out;
    }
    public synchronized Map<String,Object> playerProfile(String uuid,int limit) throws SQLException {
        List<Map<String,Object>> history=new ArrayList<>(); Set<String> visited=new TreeSet<>(String.CASE_INSENSITIVE_ORDER); long totalSeconds=0,completed=0,allLogins=0,allTransfers=0,allErrors=0,firstSeen=0; long lastLogin=0,lastSeen=0;String latestName="Unknown";
        try(PreparedStatement p=connection.prepareStatement("SELECT event_type,username,server_name,details,created_at FROM events WHERE uuid=? ORDER BY created_at DESC,id DESC LIMIT ?")){p.setString(1,uuid);p.setInt(2,Math.max(1,Math.min(500,limit)));try(ResultSet r=p.executeQuery()){while(r.next()){String type=r.getString(1),name=r.getString(2),server=r.getString(3),details=r.getString(4);long ts=r.getLong(5);if(name!=null&&!name.isBlank()&&"Unknown".equals(latestName))latestName=name;if(ts>lastSeen)lastSeen=ts;if(server!=null&&!server.isBlank()&&!server.equals("null")&&!server.startsWith("BACKEND_"))visited.add(server);Map<String,Object> e=new LinkedHashMap<>();e.put("type",type);e.put("username",name);e.put("server",server);e.put("details",details);e.put("timestamp",ts);history.add(e);}}}
        Long open=null; long completedSeconds=0;
        try(PreparedStatement p=connection.prepareStatement("SELECT event_type,server_name,username,created_at FROM events WHERE uuid=? ORDER BY created_at ASC,id ASC")){p.setString(1,uuid);try(ResultSet r=p.executeQuery()){while(r.next()){String type=r.getString(1),server=r.getString(2),name=r.getString(3);long ts=r.getLong(4);if(firstSeen==0)firstSeen=ts;if(name!=null&&!name.isBlank())latestName=name;if(server!=null&&!server.isBlank()&&!server.equals("null")&&!server.startsWith("BACKEND_"))visited.add(server);if("LOGIN".equals(type)){allLogins++;if(open==null)open=ts;lastLogin=ts;}else if("DISCONNECT".equals(type)&&open!=null){long duration=Math.max(0,ts-open);completedSeconds+=duration;completed++;open=null;}if("SERVER_SWITCH".equals(type))allTransfers++;if(type.contains("ERROR")||type.contains("FAILED"))allErrors++;}}}
        totalSeconds=completedSeconds+(open==null?0:Math.max(0,Instant.now().getEpochSecond()-open));
        long avg=completed>0?completedSeconds/completed:0;
        Map<String,Object> out=new LinkedHashMap<>();out.put("uuid",uuid);out.put("username",latestName);out.put("firstSeen",firstSeen);out.put("lastSeen",lastSeen);out.put("lastLogin",lastLogin);out.put("totalPlaySeconds",totalSeconds);out.put("averageSessionSeconds",avg);out.put("sessionCount",allLogins);out.put("transferCount",allTransfers);out.put("errorCount",allErrors);out.put("serversVisited",new ArrayList<>(visited));out.put("history",history);return out;
    }
    public synchronized Map<String,Object> analyticsSummary(int days) throws SQLException {
        int safeDays=Math.max(1,Math.min(365,days));long now=Instant.now().getEpochSecond(),cut=now-safeDays*86400L,previousCut=cut-safeDays*86400L;
        Map<String,Object> out=new LinkedHashMap<>();
        try(PreparedStatement p=connection.prepareStatement("SELECT COALESCE(MAX(online_players),0),COALESCE(AVG(online_players),0),COUNT(*) FROM metrics WHERE sampled_at>=?")){p.setLong(1,cut);try(ResultSet r=p.executeQuery()){if(r.next()){out.put("peakPlayers",r.getInt(1));out.put("averagePlayers",Math.round(r.getDouble(2)*100.0)/100.0);out.put("sampleCount",r.getLong(3));}}}
        try(PreparedStatement p=connection.prepareStatement("SELECT COUNT(DISTINCT uuid),COUNT(CASE WHEN event_type='LOGIN' THEN 1 END),COUNT(CASE WHEN event_type='DISCONNECT' THEN 1 END),COUNT(CASE WHEN event_type='SERVER_SWITCH' THEN 1 END),COUNT(CASE WHEN event_type LIKE '%ERROR%' OR event_type LIKE '%FAILED%' THEN 1 END) FROM events WHERE created_at>=? AND uuid IS NOT NULL")){p.setLong(1,cut);try(ResultSet r=p.executeQuery()){if(r.next()){out.put("uniquePlayers",r.getLong(1));out.put("connections",r.getLong(2));out.put("disconnections",r.getLong(3));out.put("transfers",r.getLong(4));out.put("playerErrors",r.getLong(5));}}}
        long current=scalar("SELECT COUNT(*) FROM events WHERE event_type='LOGIN' AND created_at>=?",cut),previous=scalar("SELECT COUNT(*) FROM events WHERE event_type='LOGIN' AND created_at>=? AND created_at<?",previousCut,cut);
        out.put("connectionsPreviousPeriod",previous);out.put("connectionChange",previous==0?(current==0?0:100):Math.round((current-previous)*10000.0/previous)/100.0);out.put("periodDays",safeDays);out.put("periodStart",cut);out.put("periodEnd",now);
        long sessionSeconds=0,sessionCount=0;Map<String,Long> openSessions=new HashMap<>();
        try(PreparedStatement p=connection.prepareStatement("SELECT uuid,event_type,created_at FROM events WHERE uuid IS NOT NULL AND created_at>=? AND event_type IN ('LOGIN','DISCONNECT') ORDER BY uuid,created_at,id")){p.setLong(1,cut);try(ResultSet r=p.executeQuery()){while(r.next()){String uuid=r.getString(1),type=r.getString(2);long ts=r.getLong(3);if("LOGIN".equals(type)){openSessions.putIfAbsent(uuid,ts);}else{Long begin=openSessions.remove(uuid);if(begin!=null){sessionSeconds+=Math.max(0,ts-begin);sessionCount++;}}}}}
        out.put("completedSessions",sessionCount);out.put("totalSessionSeconds",sessionSeconds);out.put("averageSessionSeconds",sessionCount==0?0:sessionSeconds/sessionCount);
        String bucketExpression=safeDays<=1?"strftime('%Y-%m-%d %H:00',sampled_at,'unixepoch','localtime')":safeDays<=30?"strftime('%Y-%m-%d',sampled_at,'unixepoch','localtime')":"strftime('%Y-%m',sampled_at,'unixepoch','localtime')";
        List<Map<String,Object>> backend=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT server_name,COUNT(*) samples,AVG(CASE WHEN latency_ms>=0 THEN latency_ms END) avg_latency,MIN(CASE WHEN latency_ms>=0 THEN latency_ms END) min_latency,MAX(CASE WHEN latency_ms>=0 THEN latency_ms END) max_latency,AVG(CASE WHEN status='ONLINE' THEN 1.0 ELSE 0.0 END)*100 availability FROM backend_metrics WHERE sampled_at>=? GROUP BY server_name ORDER BY server_name")){p.setLong(1,cut);try(ResultSet r=p.executeQuery()){while(r.next()){Map<String,Object> m=new LinkedHashMap<>();m.put("server",r.getString(1));m.put("samples",r.getLong(2));double avgLatency=r.getDouble(3);m.put("averageLatencyMs",r.wasNull()?null:Math.round(avgLatency*100.0)/100.0);m.put("minLatencyMs",nullableLong(r,4));m.put("maxLatencyMs",nullableLong(r,5));m.put("availabilityPercent",Math.round(r.getDouble(6)*100.0)/100.0);backend.add(m);}}}out.put("backends",backend);
        List<Map<String,Object>> hourly=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT "+bucketExpression+" bucket,MAX(online_players) players,AVG(online_players) averagePlayers FROM metrics WHERE sampled_at>=? GROUP BY bucket ORDER BY bucket")){p.setLong(1,cut);try(ResultSet r=p.executeQuery()){while(r.next()){Map<String,Object> m=new LinkedHashMap<>();m.put("bucket",r.getString(1));m.put("players",r.getInt(2));m.put("averagePlayers",Math.round(r.getDouble(3)*100.0)/100.0);hourly.add(m);}}}out.put("timeline",hourly);return out;
    }
    private long scalar(String sql,long... args) throws SQLException {try(PreparedStatement p=connection.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setLong(i+1,args[i]);try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private Object nullableLong(ResultSet r,int col)throws SQLException{long n=r.getLong(col);return r.wasNull()?null:n;}
    public synchronized List<Map<String,Object>> auditLogs(int limit) throws SQLException {
        List<Map<String,Object>> out=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT actor,action,target,ip,details,created_at FROM audit ORDER BY id DESC LIMIT ?")){p.setInt(1,Math.max(1,Math.min(500,limit)));try(ResultSet r=p.executeQuery()){while(r.next()){Map<String,Object>m=new LinkedHashMap<>();m.put("actor",r.getString(1));m.put("action",r.getString(2));m.put("target",r.getString(3));m.put("ip",r.getString(4));m.put("details",r.getString(5));m.put("timestamp",r.getLong(6));out.add(m);}}}return out;
    }
    public synchronized void prune(int retentionDays) { long cutoff=Instant.now().minusSeconds(Math.max(1,retentionDays)*86400L).getEpochSecond(); try(PreparedStatement p=connection.prepareStatement("DELETE FROM metrics WHERE sampled_at<?")){p.setLong(1,cutoff);p.executeUpdate();}catch(SQLException ignored){} try(PreparedStatement p=connection.prepareStatement("DELETE FROM events WHERE created_at<?")){p.setLong(1,cutoff);p.executeUpdate();}catch(SQLException ignored){} try(PreparedStatement p=connection.prepareStatement("DELETE FROM backend_metrics WHERE sampled_at<?")){p.setLong(1,cutoff);p.executeUpdate();}catch(SQLException ignored){} }
    public synchronized boolean createApiKey(String name,String keyHash,String createdBy) throws SQLException {
        if(name==null || !name.matches("[A-Za-z0-9 _.-]{3,48}")) return false;
        try(PreparedStatement p=connection.prepareStatement("INSERT INTO api_keys(name,key_hash,created_by,created_at) VALUES(?,?,?,?)")) { p.setString(1,name);p.setString(2,keyHash);p.setString(3,createdBy);p.setLong(4,Instant.now().getEpochSecond());p.executeUpdate();return true; } catch(SQLException e) { return false; }
    }
    public synchronized List<Map<String,Object>> apiKeys() throws SQLException {
        List<Map<String,Object>> out=new ArrayList<>();try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT id,name,created_by,created_at,last_used,revoked FROM api_keys ORDER BY id DESC")){while(r.next()){Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.getLong(1));m.put("name",r.getString(2));m.put("createdBy",r.getString(3));m.put("createdAt",r.getLong(4));m.put("lastUsed",nullableLong(r,5));m.put("revoked",r.getInt(6)!=0);out.add(m);}}return out;
    }
    public synchronized boolean authenticateApiKey(String keyHash) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("SELECT id FROM api_keys WHERE key_hash=? AND revoked=0")){p.setString(1,keyHash);try(ResultSet r=p.executeQuery()){if(!r.next())return false;long id=r.getLong(1);try(PreparedStatement u=connection.prepareStatement("UPDATE api_keys SET last_used=? WHERE id=?")){u.setLong(1,Instant.now().getEpochSecond());u.setLong(2,id);u.executeUpdate();}return true;}}
    }
    public synchronized boolean revokeApiKey(long id) throws SQLException {try(PreparedStatement p=connection.prepareStatement("UPDATE api_keys SET revoked=1 WHERE id=? AND revoked=0")){p.setLong(1,id);return p.executeUpdate()>0;}}
    public static String hashApiKey(String key) throws Exception { return sha256(key); }
    public synchronized void changePassword(String username,String salt,String hash) throws SQLException {try(PreparedStatement p=connection.prepareStatement("UPDATE users SET salt=?,password_hash=? WHERE username=?")){p.setString(1,salt);p.setString(2,hash);p.setString(3,username);p.executeUpdate();}}
    public synchronized boolean createUser(String username,String salt,String hash,String role) throws SQLException {if(!username.matches("[A-Za-z0-9_.-]{3,32}")||!Set.of("ADMINISTRATOR","MODERATOR","VIEWER").contains(role))return false;try(PreparedStatement p=connection.prepareStatement("INSERT INTO users(username,salt,password_hash,role,created_at) VALUES(?,?,?,?,?)")){p.setString(1,username);p.setString(2,salt);p.setString(3,hash);p.setString(4,role);p.setLong(5,Instant.now().getEpochSecond());p.executeUpdate();return true;}catch(SQLException e){return false;}}
    public synchronized boolean disableUser(String username,boolean disabled) throws SQLException {if("owner".equalsIgnoreCase(username))return false;try(PreparedStatement p=connection.prepareStatement("UPDATE users SET disabled=? WHERE username=? AND role!='OWNER'")){p.setInt(1,disabled?1:0);p.setString(2,username);return p.executeUpdate()>0;}}
    private String randomToken(int bytes) { byte[] b=new byte[bytes];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String sha256(String s) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
    private static boolean constantEquals(String a,String b) { return a!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
    @Override public synchronized void close(){if(connection!=null)try{connection.close();}catch(SQLException ignored){}}
    public record UserRecord(String username,String salt,String passwordHash,String role,boolean disabled){}
}
